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

import org.sonatype.nexus.blobstore.api.BlobStoreException;

import com.codahale.metrics.annotation.Timed;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest;
import software.amazon.awssdk.services.s3.model.UploadPartCopyResponse;

import static java.lang.Math.min;

/**
 * Copies a file, using multipart copy in parallel if the file is larger or equal to the chunk size. A normal
 * copyObject request is used instead if only a single chunk would be copied.
 * 
 * <p>This implementation leverages Java 21 Virtual Threads for improved throughput and resource utilization
 * when handling I/O-bound operations.</p>
 *
 * @since 3.19
 */
@Singleton
@Named("parallelCopier")
public class ParallelCopier
    extends ParallelRequester
    implements S3Copier
{
  @Inject
  public ParallelCopier(@Named("${nexus.s3.parallelRequests.chunksize:-5242880}") final int chunkSize,
                        @Named("${nexus.s3.parallelRequests.parallelism:-0}") final int nThreads)
  {
    super(chunkSize, nThreads, "copyThreads");
  }

  @Override
  @Timed
  public void copy(final S3Client s3Client, final String bucket, final String sourcePath, final String destinationPath) {
    // Get the object size to track the end of the copy operation
    HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
        .bucket(bucket)
        .key(sourcePath)
        .build();
    
    try {
      HeadObjectResponse headObjectResponse = s3Client.headObject(headObjectRequest);
      long length = headObjectResponse.contentLength();
      
      if (length < chunkSize) {
        // For small files, use a simple copy operation
        CopyObjectRequest copyRequest = CopyObjectRequest.builder()
            .sourceBucket(bucket)
            .sourceKey(sourcePath)
            .destinationBucket(bucket)
            .destinationKey(destinationPath)
            .build();
        s3Client.copyObject(copyRequest);
      }
      else {
        // For larger files, use multipart copy with Virtual Threads
        final AtomicInteger offset = new AtomicInteger(1);
        parallelRequests(s3Client, bucket, destinationPath,
            () -> (uploadId -> copyParts(s3Client, uploadId, bucket, sourcePath, destinationPath, length, offset)));
      }
    }
    catch (S3Exception e) {
      throw new BlobStoreException(STR."Error copying blob from \{sourcePath} to \{destinationPath}: \{e.getMessage()}", e, null);
    }
  }

  /**
   * Copies parts of an object in parallel using Virtual Threads.
   * Each part is copied independently and can be processed concurrently.
   *
   * @param s3Client the S3Client instance
   * @param uploadId the multipart upload ID
   * @param bucket the S3 bucket name
   * @param sourcePath the source object key
   * @param destinationPath the destination object key
   * @param size the total size of the object
   * @param offset atomic counter for tracking part numbers
   * @return list of completed parts
   */
  private List<CompletedPart> copyParts(final S3Client s3Client,
                                   final String uploadId,
                                   final String bucket,
                                   final String sourcePath,
                                   final String destinationPath,
                                   final long size,
                                   final AtomicInteger offset)
  {
    List<CompletedPart> completedParts = new ArrayList<>();
    int partNumber;

    while (getFirstByte((partNumber = offset.getAndIncrement()), chunkSize) < size) {
      // Using pattern matching for improved readability
      long firstByte = getFirstByte(partNumber, chunkSize);
      long lastByte = getLastByte(size, partNumber, chunkSize);
      
      String copySourceRange = STR."bytes=\{firstByte}-\{lastByte}";
      String copySource = STR."\{bucket}/\{sourcePath}";
      
      UploadPartCopyRequest copyRequest = UploadPartCopyRequest.builder()
          .sourceBucket(bucket)
          .sourceKey(sourcePath)
          .destinationBucket(bucket)
          .destinationKey(destinationPath)
          .uploadId(uploadId)
          .partNumber(partNumber)
          .copySourceRange(copySourceRange)
          .copySource(copySource)
          .build();

      UploadPartCopyResponse response = s3Client.uploadPartCopy(copyRequest);
      
      // Using pattern matching with records
      if (response != null && response.copyPartResult() != null) {
        var result = response.copyPartResult();
        completedParts.add(
            CompletedPart.builder()
                .partNumber(partNumber)
                .eTag(result.eTag())
                .build()
        );
      }
    }

    return completedParts;
  }

  /**
   * Calculates the first byte position for a given part number.
   *
   * @param partNumber the part number (1-based)
   * @param chunkSize the size of each chunk in bytes
   * @return the position of the first byte in the part
   */
  static long getFirstByte(final long partNumber, final long chunkSize) {
    return (partNumber - 1) * chunkSize;
  }

  /**
   * Calculates the last byte position for a given part number.
   *
   * @param size the total size of the object
   * @param partNumber the part number (1-based)
   * @param chunkSize the size of each chunk in bytes
   * @return the position of the last byte in the part
   */
  static long getLastByte(final long size, final long partNumber, final long chunkSize) {
    return min(partNumber * chunkSize, size) - 1;
  }
}