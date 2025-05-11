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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.nexus.blobstore.api.BlobStoreException;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CopyPartResult;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ParallelCopier} that verify the S3 multipart copy functionality.
 */
@ExtendWith(MockitoExtension.class)
class ParallelCopierTest
{
  private ParallelCopier copier;

  @Mock
  private AmazonS3 s3;

  @Mock
  private InitiateMultipartUploadResult initiateMultipartUploadResult;

  @BeforeEach
  void setUp() {
    when(initiateMultipartUploadResult.getUploadId()).thenReturn("uploadId");
    copier = new ParallelCopier(100, 4);
  }

  /**
   * Verifies that the first and last byte calculations for multipart copy operations are correct.
   */
  @Test
  void calcFirstAndLastBytesProperly() {
    assertThat(ParallelCopier.getFirstByte(1, 500), is(0L));
    assertThat(ParallelCopier.getLastByte(1700, 1, 500), is(499L));
    assertThat(ParallelCopier.getFirstByte(2, 500), is(500L));
    assertThat(ParallelCopier.getLastByte(1700, 2, 500), is(999L));
    assertThat(ParallelCopier.getFirstByte(3, 500), is(1000L));
    assertThat(ParallelCopier.getLastByte(1700, 3, 500), is(1499L));
    assertThat(ParallelCopier.getFirstByte(4, 500), is(1500L));
    assertThat(ParallelCopier.getLastByte(1700, 4, 500), is(1699L));
    assertThat(ParallelCopier.getFirstByte(5, 500), is(2000L));
    assertThat(ParallelCopier.getLastByte(1700, 5, 500), is(1699L));
  }

  /**
   * Verifies that the copy operation correctly uses the multipart API for objects larger than the threshold.
   */
  @Test
  void copyWithMultipartApi() {
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.getObjectMetadata("bucketName", "source")).thenReturn(new ObjectMetadata() {{
      setContentLength(101);
    }});
    when(s3.copyPart(any())).thenReturn(new CopyPartResult());

    copier.copy(s3, "bucketName", "source", "destination");

    verify(s3).initiateMultipartUpload(any());
    verify(s3).getObjectMetadata("bucketName", "source");
    verify(s3, times(2)).copyPart(any());
    verify(s3).completeMultipartUpload(any());
    verify(s3, never()).abortMultipartUpload(any());
  }

  /**
   * Verifies that the copy operation aborts the multipart upload when an error occurs.
   */
  @Test
  void copyAbortsMultipartOnError() {
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.getObjectMetadata("bucketName", "source")).thenReturn(new ObjectMetadata() {{
      setContentLength(101);
    }});
    when(s3.copyPart(any())).thenThrow(new SdkClientException(""));

    assertThrows(BlobStoreException.class, () -> copier.copy(s3, "bucketName", "source", "destination"));

    verify(s3).initiateMultipartUpload(any());
    verify(s3).getObjectMetadata("bucketName", "source");
    verify(s3, atLeastOnce()).copyPart(any());
    verify(s3).abortMultipartUpload(any());
  }

  /**
   * Verifies that the copy operation correctly splits the object into multiple parts for large objects.
   */
  @Test
  void copySplitsParts() {
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.getObjectMetadata("bucketName", "source")).thenReturn(new ObjectMetadata() {{
      setContentLength(345);
    }});
    when(s3.copyPart(any())).thenReturn(new CopyPartResult());

    copier.copy(s3, "bucketName", "source", "destination");

    verify(s3).initiateMultipartUpload(any());
    verify(s3).getObjectMetadata("bucketName", "source");
    verify(s3, times(4)).copyPart(any());
    verify(s3).completeMultipartUpload(any());
    verify(s3, never()).abortMultipartUpload(any());
  }

  /**
   * Verifies that the copy operation uses the simple copyObject API for small objects below the threshold.
   */
  @Test
  void copyUsesCopyObjectForSmallCopies() {
    when(s3.getObjectMetadata("bucketName", "source")).thenReturn(new ObjectMetadata() {{
      setContentLength(99);
    }});

    copier.copy(s3, "bucketName", "source", "destination");

    verify(s3).getObjectMetadata("bucketName", "source");
    verify(s3).copyObject(any(), any(), any(), any());
    verify(s3, never()).initiateMultipartUpload(any());
  }
}
