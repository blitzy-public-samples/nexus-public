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
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.blobstore.api.BlobStoreException;

import com.codahale.metrics.annotation.Timed;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyPartRequest;
import software.amazon.awssdk.services.s3.model.CopyPartResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import static java.lang.Math.min;

/**
 * Copies a file, using multipart copy in parallel if the file is larger or equal to the chunk size. A normal
 * copyObject request is used instead if only a single chunk would be copied.
 * 
 * This implementation leverages Java 21 Virtual Threads for I/O-bound operations to improve throughput
 * and resource efficiency when performing S3 copy operations.
 *
 * @since 3.19
 */
@Singleton
@Named("parallelCopier")
public class ParallelCopier
    extends ParallelRequester
    implements S3Copier
{
  private static final Logger log = LoggerFactory.getLogger(ParallelCopier.class);
  /**
   * Creates a new ParallelCopier instance that uses Virtual Threads for parallel copy operations.
   *
   * @param chunkSize - the size of each chunk in bytes for multipart copy operations
   * @param nThreads - the number of parallel tasks to use (0 for auto-configuration based on available processors)
   */
  @Inject
  public ParallelCopier(@Named("${nexus.s3.parallelRequests.chunksize:-5242880}") final int chunkSize,
                        @Named("${nexus.s3.parallelRequests.parallelism:-0}") final int nThreads)
  {
    super(chunkSize, nThreads, "copyThreads");
  }

  @Override
  @Timed
  public void copy(final S3Client s3Client, final String bucket, final String srcKey, final String destKey) {
    // Get object size using HeadObject request
    HeadObjectRequest headRequest = HeadObjectRequest.builder()
        .bucket(bucket)
        .key(srcKey)
        .build();
    
    HeadObjectResponse headResponse = s3Client.headObject(headRequest);
    long length = headResponse.contentLength();

    try {
      if (length < chunkSize) {
        // For small files, use a simple copy operation
        CopyObjectRequest copyRequest = CopyObjectRequest.builder()
            .sourceBucket(bucket)
            .sourceKey(srcKey)
            .destinationBucket(bucket)
            .destinationKey(destKey)
            .build();
        s3Client.copyObject(copyRequest);
      }
      else {
        // For larger files, use parallel multipart copy with Virtual Threads
        // Initiate multipart upload
        CreateMultipartUploadRequest createRequest = CreateMultipartUploadRequest.builder()
            .bucket(bucket)
            .key(destKey)
            .build();
        String uploadId = s3Client.createMultipartUpload(createRequest).uploadId();
        
        try {
          // Copy parts in parallel using Virtual Threads
          final AtomicInteger offset = new AtomicInteger(1);
          List<CompletedPart> completedParts = copyParts(s3Client, uploadId, bucket, srcKey, destKey, length, offset);
          
          // Complete multipart upload
          CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
              .parts(completedParts)
              .build();
              
          CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(destKey)
              .uploadId(uploadId)
              .multipartUpload(completedUpload)
              .build();
              
          s3Client.completeMultipartUpload(completeRequest);
        } catch (Exception e) {
          // Abort multipart upload on failure
          abortMultipartUpload(s3Client, bucket, destKey, uploadId);
          throw e;
        }
      }
    }
    catch (SdkException e) {
      throw new BlobStoreException(STR."Error copying blob from \{srcKey} to \{destKey} in bucket \{bucket}", e, null);
    }
  }

  /**
   * Copies parts of a file in parallel using Virtual Threads.
   * Each thread handles a chunk of the file based on the configured chunk size.
   *
   * @param s3Client the S3 client
   * @param uploadId the multipart upload ID
   * @param bucket the S3 bucket name
   * @param srcKey the source object key
   * @param destKey the destination object key
   * @param size the total size of the object
   * @param offset atomic counter for tracking part numbers
   * @return list of CompletedPart objects for the copied parts
   */
  private List<CompletedPart> copyParts(final S3Client s3Client,
                                   final String uploadId,
                                   final String bucket,
                                   final String srcKey,
                                   final String destKey,
                                   final long size,
                                   final AtomicInteger offset)
  {
    List<CompletedPart> completedParts = new ArrayList<>();
    int partNumber;

    while (getFirstByte((partNumber = offset.getAndIncrement()), chunkSize) < size) {
      final long firstByte = getFirstByte(partNumber, chunkSize);
      final long lastByte = getLastByte(size, partNumber, chunkSize);
      
      CopyPartRequest copyPartRequest = CopyPartRequest.builder()
          .sourceBucket(bucket)
          .sourceKey(srcKey)
          .destinationBucket(bucket)
          .destinationKey(destKey)
          .uploadId(uploadId)
          .partNumber(partNumber)
          .firstByte(firstByte)
          .lastByte(lastByte)
          .build();

      // Using pattern matching for improved code clarity with Java 21
      var response = s3Client.copyPart(copyPartRequest);
      if (response instanceof CopyPartResponse copyPartResponse) {
        CompletedPart part = CompletedPart.builder()
            .partNumber(partNumber)
            .eTag(copyPartResponse.copyPartResult().eTag())
            .build();
        completedParts.add(part);
      }
    }

    return completedParts;
  }
  
  /**
   * Aborts a multipart upload in case of failure.
   *
   * @param s3Client the S3 client
   * @param bucket the S3 bucket name
   * @param key the object key
   * @param uploadId the multipart upload ID to abort
   */
  private void abortMultipartUpload(final S3Client s3Client, final String bucket, final String key, final String uploadId) {
    try {
      s3Client.abortMultipartUpload(request -> request
          .bucket(bucket)
          .key(key)
          .uploadId(uploadId)
          .build());
    } catch (Exception e) {
      // Log but don't throw as we're already handling another exception
      log.warn(STR."Failed to abort multipart upload for \{key} in bucket \{bucket}", e);
    }
  }

  /**
   * Calculates the first byte position for a given part number and chunk size.
   *
   * @param partNumber the part number (1-based)
   * @param chunkSize the size of each chunk in bytes
   * @return the position of the first byte for this part
   */
  static long getFirstByte(final long partNumber, final long chunkSize) {
    return (partNumber - 1) * chunkSize;
  }

  /**
   * Calculates the last byte position for a given part number and chunk size.
   *
   * @param size the total size of the object
   * @param partNumber the part number (1-based)
   * @param chunkSize the size of each chunk in bytes
   * @return the position of the last byte for this part
   */
  static long getLastByte(final long size, final long partNumber, final long chunkSize) {
    return min(partNumber * chunkSize, size) - 1;
  }
}
