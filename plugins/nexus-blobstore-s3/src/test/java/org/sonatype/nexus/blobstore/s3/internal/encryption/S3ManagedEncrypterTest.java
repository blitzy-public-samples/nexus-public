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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * Tests for {@link S3ManagedEncrypter} that verify server-side encryption is properly applied
 * to different types of S3 requests.
 */
@ExtendWith(MockitoExtension.class)
class S3ManagedEncrypterTest
{
  private final S3ManagedEncrypter encrypter = new S3ManagedEncrypter();

  @Test
  void shouldApplyServerSideEncryptionToInitiateMultipartUploadRequest() {
    CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
            .bucket("my-bucket")
            .key("my-key")
            .build();

    CreateMultipartUploadRequest modified = encrypter.addEncryption(request);

    Assertions.assertNotNull(modified);
    Assertions.assertEquals(ServerSideEncryption.AES256, modified.serverSideEncryption());
  }

  @Test
  void shouldApplyServerSideEncryptionToAbstractPutObjectRequest() {
    PutObjectRequest request = PutObjectRequest.builder()
            .bucket("my-bucket")
            .key("my-key")
            .build();

    PutObjectRequest modified = encrypter.addEncryption(request);

    Assertions.assertNotNull(modified);
    Assertions.assertEquals(ServerSideEncryption.AES256, modified.serverSideEncryption());
  }
  @Test
  void shouldApplyServerSideEncryptionToCopyObjectRequest() {
    CopyObjectRequest request = CopyObjectRequest.builder()
            .sourceBucket("source-bucket")
            .sourceKey("source-key")
            .destinationBucket("dest-bucket")
            .destinationKey("dest-key")
            .build();

    CopyObjectRequest modified = encrypter.addEncryption(request);

    Assertions.assertNotNull(modified);
    Assertions.assertEquals(ServerSideEncryption.AES256, modified.serverSideEncryption());
  }
}