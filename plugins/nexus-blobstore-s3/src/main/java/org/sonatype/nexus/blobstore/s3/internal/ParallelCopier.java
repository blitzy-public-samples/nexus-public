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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobStoreException;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CopyPartRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.PartETag;
import com.codahale.metrics.annotation.Timed;

import static java.lang.Math.min;

/**
 * Copies a file, using multipart copy in parallel if the file is larger or equal to the chunk size. A normal
 * copyObject request is used instead if only a single chunk would be copied.
 * 
 * This implementation uses Virtual Threads for parallel operations to improve performance and resource utilization.
 *
 * @since 3.19
 */
@Singleton
@Named("parallelCopier")
public class ParallelCopier
    implements S3Copier
{
  protected final int chunkSize;

  /**
   * @param chunkSize - the number of bytes to be processed in one parallel request
   */
  @Inject
  public ParallelCopier(@Named("${nexus.s3.parallelRequests.chunksize:-5242880}") final int chunkSize)
  {
    this.chunkSize = chunkSize;
  }

  @Override
  @Timed
  public void copy(final AmazonS3 s3, final String bucket, final String srcKey, final String destKey) {
    long length = s3.getObjectMetadata(bucket, srcKey).getContentLength();

    try {
      if (length < chunkSize) {
        s3.copyObject(bucket, srcKey, bucket, destKey);
      }
      else {
        copyWithVirtualThreads(s3, bucket, srcKey, destKey, length);
      }
    }
    catch (SdkClientException e) {
      throw new BlobStoreException("Error copying blob", e, null);
    }
  }

  /**
   * Performs multipart copy using Virtual Threads for improved performance and resource utilization.
   */
  private void copyWithVirtualThreads(final AmazonS3 s3, 
                                     final String bucket, 
                                     final String srcKey, 
                                     final String destKey, 
                                     final long length) {
    InitiateMultipartUploadRequest initiateRequest = new InitiateMultipartUploadRequest(bucket, destKey);
    String uploadId = s3.initiateMultipartUpload(initiateRequest).getUploadId();

    try {
      // Calculate number of parts needed
      int numParts = (int) Math.ceil((double) length / chunkSize);
      
      // Create a virtual thread per part using the new Java 21 virtual thread executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<PartETag>> futures = new ArrayList<>(numParts);
        
        // Submit copy tasks for each part
        for (int partNumber = 1; partNumber <= numParts; partNumber++) {
          final int currentPart = partNumber;
          futures.add(executor.submit(() -> copyPart(s3, uploadId, bucket, srcKey, destKey, length, currentPart)));
        }
        
        // Collect all part ETags
        List<PartETag> partETags = futures.stream()
            .map(future -> {
              try {
                return future.get();
              }
              catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Copy operation interrupted", e);
              }
              catch (ExecutionException e) {
                throw new RuntimeException("Error during part copy", e.getCause());
              }
            })
            .collect(Collectors.toList());
        
        // Complete the multipart upload
        s3.completeMultipartUpload(new CompleteMultipartUploadRequest()
            .withBucketName(bucket)
            .withKey(destKey)
            .withUploadId(uploadId)
            .withPartETags(partETags));
      }
      catch (Exception e) {
        s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, destKey, uploadId));
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          throw new BlobStoreException("Copy operation interrupted", e, null);
        }
        throw new BlobStoreException("Error executing parallel copy", e, null);
      }
    }
    catch (Exception e) {
      s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, destKey, uploadId));
      throw new BlobStoreException(
          String.format("Error copying from %s to %s in bucket %s", srcKey, destKey, bucket), e, null);
    }
  }

  /**
   * Copies a single part of the multipart copy operation.
   */
  private PartETag copyPart(final AmazonS3 s3,
                           final String uploadId,
                           final String bucket,
                           final String srcKey,
                           final String destKey,
                           final long size,
                           final int partNumber)
  {
    CopyPartRequest request = new CopyPartRequest()
        .withSourceBucketName(bucket)
        .withSourceKey(srcKey)
        .withDestinationBucketName(bucket)
        .withDestinationKey(destKey)
        .withUploadId(uploadId)
        .withPartNumber(partNumber)
        .withFirstByte(getFirstByte(partNumber, chunkSize))
        .withLastByte(getLastByte(size, partNumber, chunkSize));

    return s3.copyPart(request).getPartETag();
  }

  static long getFirstByte(final long partNumber, final long chunkSize) {
    return (partNumber - 1) * chunkSize;
  }

  static long getLastByte(final long size, final long partNumber, final long chunkSize) {
    return min(partNumber * chunkSize, size) - 1;
  }
}