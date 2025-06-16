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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Tests for {@link NoEncrypter} functionality, verifying that it doesn't modify AWS S3 requests.
 * 
 * This test ensures the NoEncrypter implementation correctly follows the null object pattern
 * by not performing any operations on the S3 request objects passed to it.
 */
@ExtendWith(MockitoExtension.class)
class NoEncrypterTest
{
  private NoEncrypter noEncrypter;

  @BeforeEach
  void setUp() {
    noEncrypter = new NoEncrypter();
  }

  @Test
  void shouldNotModifyInitiateMultipartUploadRequest() {
    CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
            .bucket("bucket")
            .key("key")
            .build();

    CreateMultipartUploadRequest result = noEncrypter.addEncryption(request);

    // Should return same instance or equivalent unmodified request
    Assertions.assertSame(request, result, "NoEncrypter should not modify the request");
  }

  @Test
  void shouldNotModifyAbstractPutObjectRequest() {
    PutObjectRequest request = PutObjectRequest.builder()
            .bucket("bucket")
            .key("key")
            .build();

    PutObjectRequest result = noEncrypter.addEncryption(request);

    Assertions.assertSame(request, result, "NoEncrypter should not modify the request");
  }

  @Test
  void shouldNotModifyCopyObjectRequest() {
    CopyObjectRequest request = CopyObjectRequest.builder()
            .sourceBucket("source")
            .sourceKey("sourceKey")
            .destinationBucket("dest")
            .destinationKey("destKey")
            .build();

    CopyObjectRequest result = noEncrypter.addEncryption(request);

    Assertions.assertSame(request, result, "NoEncrypter should not modify the request");
  }

}