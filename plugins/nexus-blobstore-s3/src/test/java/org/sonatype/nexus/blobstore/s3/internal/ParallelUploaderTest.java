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
package org.sonatype.nexus.blobstore.s3.internal;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStoreException;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.UploadPartResult;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ParallelUploader}.
 */
@ExtendWith(MockitoExtension.class)
class ParallelUploaderTest
    extends TestSupport
{
  private ParallelUploader parallelUploader;

  @Mock
  private AmazonS3 s3;

  @Mock
  private InitiateMultipartUploadResult initiateMultipartUploadResult;

  @BeforeEach
  void setUp() {
    parallelUploader = new ParallelUploader(100, 4);
  }

  /**
   * Verifies that an empty stream is uploaded using putObject instead of multipart upload.
   */
  @Test
  void emptyStreamCausesUpload() {
    InputStream input = new ByteArrayInputStream(new byte[0]);
    parallelUploader.upload(s3, "bucketName", "key", input);

    verify(s3).putObject(any(), any(), any(), any());
    verify(s3, times(0)).initiateMultipartUpload(any());
  }

  /**
   * Verifies that a stream larger than the threshold is uploaded using the multipart API.
   */
  @Test
  void uploadWithMultipartApi() {
    InputStream input = new ByteArrayInputStream(new byte[100]);
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenReturn(new UploadPartResult());

    parallelUploader.upload(s3, "bucketName", "key", input);

    verify(s3).initiateMultipartUpload(any());
    verify(s3).uploadPart(any());
    verify(s3).completeMultipartUpload(any());
    verify(s3, never()).abortMultipartUpload(any());
  }

  /**
   * Verifies that a multipart upload is aborted when an error occurs during upload.
   */
  @Test
  void uploadAbortsMultipartUploadsOnError() {
    InputStream input = new ByteArrayInputStream(new byte[100]);
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenThrow(new SdkClientException(""));

    assertThrows(BlobStoreException.class, () -> parallelUploader.upload(s3, "bucketName", "key", input));

    verify(s3).initiateMultipartUpload(any());
    verify(s3).uploadPart(any());
    verify(s3).abortMultipartUpload(any());
  }

  /**
   * Verifies that a stream smaller than the threshold is uploaded using putObject.
   */
  @Test
  void uploadUsesPutObjectForSmallUploads() {
    InputStream input = new ByteArrayInputStream(new byte[50]);
    parallelUploader.upload(s3, "bucketName", "key", input);

    verify(s3).putObject(any(), any(), any(), any());
    verify(s3, never()).initiateMultipartUpload(any());
  }
}