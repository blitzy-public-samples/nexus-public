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

import org.sonatype.nexus.blobstore.api.BlobStoreException;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ProducerConsumerUploader} that verify S3 upload functionality
 * with various input sizes and error conditions.
 */
@ExtendWith(MockitoExtension.class)
class ProducerConsumerUploaderTest
{
  private ProducerConsumerUploader producerConsumerUploader;

  @Mock
  private AmazonS3 s3;

  @Mock
  private MetricRegistry registry;

  @Mock
  private Timer.Context context;

  @Mock
  private Timer timer;

  @Mock
  private InitiateMultipartUploadResult initiateMultipartUploadResult;

  @Mock
  private Timer readChunk;

  @Mock
  private Timer uploadChunk;

  @Mock
  private Timer multiPartUpload;

  @BeforeEach
  void setUp() throws Exception {
    when(initiateMultipartUploadResult.getUploadId()).thenReturn("uploadId");
    when(timer.time()).thenReturn(context);
    when(registry.timer(anyString())).thenReturn(timer);

    when(readChunk.time()).thenReturn(context);
    when(registry.timer("org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.uploader.readChunk")).thenReturn(readChunk);

    when(uploadChunk.time()).thenReturn(context);
    when(registry.timer("org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.uploader.uploadChunk")).thenReturn(uploadChunk);

    when(multiPartUpload.time()).thenReturn(context);
    when(registry.timer("org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.uploader.multiPartUpload")).thenReturn(multiPartUpload);

    producerConsumerUploader = new ProducerConsumerUploader(100, 4, registry);
    producerConsumerUploader.start();
  }

  /**
   * Verifies that an empty input stream is handled correctly by using putObject instead of multipart upload.
   */
  @Test
  void emptyStreamCausesUpload() {
    InputStream input = new ByteArrayInputStream(new byte[0]);

    producerConsumerUploader.upload(s3, "bucketName", "key", input);

    verify(s3).putObject(any(), any(), any(), any());
    verify(s3, never()).initiateMultipartUpload(any());
  }

  /**
   * Verifies that uploads at the threshold size use the multipart API correctly.
   */
  @Test
  void uploadWithMultipartApi() {
    InputStream input = new ByteArrayInputStream(new byte[100]);
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenReturn(new UploadPartResult());

    producerConsumerUploader.upload(s3, "bucketName", "key", input);

    verify(s3).initiateMultipartUpload(any());
    verify(s3).uploadPart(any());
    verify(s3).completeMultipartUpload(any());
    verify(s3, never()).abortMultipartUpload(any());
  }

  /**
   * Verifies that larger uploads correctly use the multipart API and emit the expected metrics.
   */
  @Test
  void largerUploadWithMultipartApiEmitMetrics() {
    InputStream input = new ByteArrayInputStream(new byte[350]);
    when(s3.initiateMultipartUpload(any())).thenReturn(new InitiateMultipartUploadResult());
    when(s3.uploadPart(any())).thenReturn(new UploadPartResult());

    producerConsumerUploader.upload(s3, "bucketName", "key", input);

    verify(s3).initiateMultipartUpload(any());
    verify(s3, times(4)).uploadPart(any());
    verify(s3).completeMultipartUpload(any());
    verify(s3, never()).abortMultipartUpload(any());
    verify(multiPartUpload).time();
    verify(readChunk, times(6)).time();
    verify(uploadChunk, times(4)).time();
  }

  /**
   * Verifies that the uploader correctly aborts multipart uploads when errors occur.
   */
  @Test
  void uploadAbortsMultipartOnError() {
    InputStream input = new ByteArrayInputStream(new byte[100]);
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenThrow(new SdkClientException(""));

    assertThrows(BlobStoreException.class, 
        () -> producerConsumerUploader.upload(s3, "bucketName", "key", input));

    verify(s3).initiateMultipartUpload(any());
    verify(s3).uploadPart(any());
    verify(s3).abortMultipartUpload(any());
  }

  /**
   * Verifies that small uploads use putObject instead of the multipart API.
   */
  @Test
  void uploadUsesPutObjectForSmallUploads() {
    InputStream input = new ByteArrayInputStream(new byte[50]);

    producerConsumerUploader.upload(s3, "bucketName", "key", input);

    verify(s3).putObject(any(), any(), any(), any());
    verify(s3, never()).initiateMultipartUpload(any());
  }
}
