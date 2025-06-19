/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.blobstore.s3.internal.encryption;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.model.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class KMSEncrypterTest {

  @Test
  void constructorHandlesKmsId() {
    assertThat(new KMSEncrypter(Optional.empty()).getKmsKeyId(), nullValue());
    assertThat(new KMSEncrypter(Optional.of("")).getKmsKeyId(), nullValue());
    assertThat(new KMSEncrypter(Optional.of(" ")).getKmsKeyId(), nullValue());
    assertThat(new KMSEncrypter(Optional.of("   ")).getKmsKeyId(), nullValue());
    assertThat(new KMSEncrypter(Optional.of("aProperKeyId")).getKmsKeyId(), is("aProperKeyId"));
  }

  @Test
  void supplyingNoKmsIdAddsCorrectKmsParameters() {
    KMSEncrypter kmsEncrypter = new KMSEncrypter();

    CreateMultipartUploadRequest uploadRequest = CreateMultipartUploadRequest.builder().build();
    CreateMultipartUploadRequest encryptedUploadRequest = kmsEncrypter.addEncryption(uploadRequest);
    assertThat(encryptedUploadRequest.serverSideEncryption(), is(ServerSideEncryption.AWS_KMS));
    assertThat(encryptedUploadRequest.ssekmsKeyId(), nullValue());

    PutObjectRequest putRequest = PutObjectRequest.builder().build();
    PutObjectRequest encryptedPutRequest = kmsEncrypter.addEncryption(putRequest);
    assertThat(encryptedPutRequest.serverSideEncryption(), is(ServerSideEncryption.AWS_KMS));
    assertThat(encryptedPutRequest.ssekmsKeyId(), nullValue());

    CopyObjectRequest copyRequest = CopyObjectRequest.builder().build();
    CopyObjectRequest encryptedCopyRequest = kmsEncrypter.addEncryption(copyRequest);
    assertThat(encryptedCopyRequest.serverSideEncryption(), is(ServerSideEncryption.AWS_KMS));
    assertThat(encryptedCopyRequest.ssekmsKeyId(), nullValue());
  }

  @Test
  void addsCorrectKmsParametersWithKeyId() {
    KMSEncrypter kmsEncrypter = new KMSEncrypter(Optional.of("FakeKeyId"));

    CreateMultipartUploadRequest uploadRequest = CreateMultipartUploadRequest.builder().build();
    CreateMultipartUploadRequest encryptedUploadRequest = kmsEncrypter.addEncryption(uploadRequest);
    assertThat(encryptedUploadRequest.serverSideEncryption(), is(ServerSideEncryption.AWS_KMS));
    assertThat(encryptedUploadRequest.ssekmsKeyId(), is("FakeKeyId"));

    PutObjectRequest putRequest = PutObjectRequest.builder().build();
    PutObjectRequest encryptedPutRequest = kmsEncrypter.addEncryption(putRequest);
    assertThat(encryptedPutRequest.serverSideEncryption(), is(ServerSideEncryption.AWS_KMS));
    assertThat(encryptedPutRequest.ssekmsKeyId(), is("FakeKeyId"));

    CopyObjectRequest copyRequest = CopyObjectRequest.builder().build();
    CopyObjectRequest encryptedCopyRequest = kmsEncrypter.addEncryption(copyRequest);
    assertThat(encryptedCopyRequest.serverSideEncryption(), is(ServerSideEncryption.AWS_KMS));
    assertThat(encryptedCopyRequest.ssekmsKeyId(), is("FakeKeyId"));
  }
}
