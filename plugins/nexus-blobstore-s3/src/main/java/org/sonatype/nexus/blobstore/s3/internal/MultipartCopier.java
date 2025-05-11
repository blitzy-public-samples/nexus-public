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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStoreException;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CopyPartRequest;
import com.amazonaws.services.s3.model.CopyPartResult;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;

import static java.lang.Math.min;
import static java.util.stream.Collectors.toList;
import static com.google.common.base.Preconditions.checkState;

/**
 * Copies a file, using multipart copy if the file is larger or equal to the chunk size. A normal copyObject request is
 * used instead if only a single chunk would be copied.
 *
 * This implementation is optimized for Java 21 and leverages Virtual Threads for I/O-bound operations to improve
 * throughput and concurrency. Virtual Threads provide lightweight concurrency for I/O-bound operations without the
 * overhead of traditional platform threads, allowing for higher throughput when performing S3 operations.
 *
 * The implementation uses Java 21 features:
 * - Virtual Threads for non-blocking I/O operations
 * - String templates for more readable logging statements
 * - Parallel execution of copy part operations using a Virtual Thread per task executor
 *
 * @since 3.15
 */
@Named("multipart-copier")
public class MultipartCopier
    extends ComponentSupport
    implements S3Copier
{

  private final int chunkSize;

  @Inject
  public MultipartCopier(@Named("${nexus.s3.multipartupload.chunksize:-5242880}") final int chunkSize) {
    this.chunkSize = chunkSize;
  }

  @Override
  public void copy(final AmazonS3 s3, final String bucket, final String sourcePath, final String destinationPath) {
    ObjectMetadata metadataResult = s3.getObjectMetadata(bucket, sourcePath);
    long length = metadataResult.getContentLength();

    try {
      if (length < chunkSize) {
        copySinglePart(s3, bucket, sourcePath, destinationPath);
      }
      else {
        copyMultiPart(s3, bucket, sourcePath, destinationPath, length);
      }
    }
    catch(SdkClientException e) {
      throw new BlobStoreException("Error copying blob", e, null);
    }
  }

  private void copySinglePart(final AmazonS3 s3,
                              final String bucket,
                              final String sourcePath,
                              final String destinationPath) {
    // Use a Virtual Thread for this I/O-bound operation
    Thread.startVirtualThread(() -> {
      s3.copyObject(bucket, sourcePath, bucket, destinationPath);
    }).join();
  }

  private void copyMultiPart(final AmazonS3 s3,
                             final String bucket,
                             final String sourcePath,
                             final String destinationPath,
                             final long length) {
    checkState(length > 0);
    String uploadId = null;
    try {
      long remaining = length;
      long offset = 0;

      InitiateMultipartUploadRequest initiateRequest = new InitiateMultipartUploadRequest(bucket, destinationPath);
      uploadId = s3.initiateMultipartUpload(initiateRequest).getUploadId();

      log.debug(STR."Starting multipart copy \{uploadId} to key \{destinationPath} from key \{sourcePath}");

      List<CopyPartResult> results = new ArrayList<>();
      
      // Use a Virtual Thread per task executor for parallel part copying
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Callable<CopyPartResult>> tasks = new ArrayList<>();
        
        for (int partNumber = 1; remaining > 0; partNumber++) {
          long partSize = min(remaining, chunkSize);
          log.trace(STR."Preparing chunk \{partNumber} for \{uploadId} from byte \{offset} to \{offset + partSize - 1}, size \{partSize}");
          
          CopyPartRequest part = new CopyPartRequest()
              .withSourceBucketName(bucket)
              .withSourceKey(sourcePath)
              .withDestinationBucketName(bucket)
              .withDestinationKey(destinationPath)
              .withUploadId(uploadId)
              .withPartNumber(partNumber)
              .withFirstByte(offset)
              .withLastByte(offset + partSize - 1);
          
          final int currentPartNumber = partNumber;
          tasks.add(() -> {
            CopyPartResult result = s3.copyPart(part);
            log.trace(STR."Completed chunk \{currentPartNumber} for \{uploadId}");
            return result;
          });
          
          offset += partSize;
          remaining -= partSize;
        }
        
        // Execute all copy part tasks in parallel using Virtual Threads
        List<Future<CopyPartResult>> futures = executor.invokeAll(tasks);
        results = futures.stream()
            .map(future -> {
              try {
                return future.get();
              }
              catch (Exception e) {
                throw new RuntimeException("Error during parallel part copy", e);
              }
            })
            .collect(toList());
      }
      
      CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest()
          .withBucketName(bucket)
          .withKey(destinationPath)
          .withUploadId(uploadId)
          .withPartETags(results.stream().map(r -> new PartETag(r.getPartNumber(), r.getETag())).collect(toList()));
      s3.completeMultipartUpload(compRequest);
      log.debug(STR."Copy \{uploadId} complete");
    }
    catch(SdkClientException e) {
      if (uploadId != null) {
        try {
          s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, destinationPath, uploadId));
        }
        catch(Exception inner) {
          log.error(STR."Error aborting S3 multipart copy to bucket \{bucket} with key \{destinationPath}",
              log.isDebugEnabled() ? inner : null);
        }
      }
      throw e;
    }
  }
}