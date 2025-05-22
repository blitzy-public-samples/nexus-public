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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.PartETag;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;

/**
 * Common class to execute parallel requests to S3 for a MultipartUpload operation
 *
 * @since 3.19
 */
public abstract class ParallelRequester
    extends StateGuardLifecycleSupport
{
  protected final int chunkSize;

  private final ExecutorService executorService;

  /**
   * @param chunkSize       - the number of bytes to be processed in one parallel request
   * @param numberOfThreads - ignored parameter, kept for backward compatibility
   * @param threadGroupName - a human readable name for the threads
   */
  public ParallelRequester(final int chunkSize, final int numberOfThreads, final String threadGroupName)
  {
    checkArgument(chunkSize >= 0, "Must use a non-negative chunkSize");
    this.chunkSize = chunkSize;

    // Use virtual threads instead of a fixed thread pool
    this.executorService = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  protected void doStop() {
    executorService.shutdownNow();
  }


  @FunctionalInterface
  protected interface IOFunction<T, R>
  {
    R apply(T v) throws IOException;
  }

  protected void parallelRequests(final AmazonS3 s3,
                                  final String bucket,
                                  final String key,
                                  final Supplier<IOFunction<String, List<PartETag>>> operations)
  {
    InitiateMultipartUploadRequest initiateRequest = new InitiateMultipartUploadRequest(bucket, key);
    String uploadId = s3.initiateMultipartUpload(initiateRequest).getUploadId();

    List<Future<List<PartETag>>> futures = new ArrayList<>();
    try {
      // Submit tasks - with virtual threads we can create as many as needed
      // Keep submitting tasks as long as the operations supplier provides them
      IOFunction<String, List<PartETag>> operation;
      while ((operation = operations.get()) != null) {
        IOFunction<String, List<PartETag>> currentOperation = operation;
        futures.add(executorService.submit(() -> currentOperation.apply(uploadId)));
      }

      List<PartETag> partETags = new ArrayList<>();
      for (Future<List<PartETag>> future : futures) {
        partETags.addAll(future.get());
      }

      s3.completeMultipartUpload(new CompleteMultipartUploadRequest()
          .withBucketName(bucket)
          .withKey(key)
          .withUploadId(uploadId)
          .withPartETags(partETags));
    }
    catch (InterruptedException interrupted) {
      // Cancel all pending tasks
      for (Future<List<PartETag>> future : futures) {
        future.cancel(true);
      }
      s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId));
      // Preserve interrupt status
      Thread.currentThread().interrupt();
    }
    catch (CancellationException | ExecutionException ex) {
      // Cancel all pending tasks
      for (Future<List<PartETag>> future : futures) {
        if (!future.isDone()) {
          future.cancel(true);
        }
      }
      s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId));
      throw new BlobStoreException(
          format("Error executing parallel requests for bucket:%s key:%s with uploadId:%s", bucket, key, uploadId), ex,
          null);
    }
  }
}