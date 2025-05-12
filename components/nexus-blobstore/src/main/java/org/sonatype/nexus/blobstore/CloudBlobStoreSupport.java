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
package org.sonatype.nexus.blobstore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.common.log.DryRunPrefix;

/**
 * Support class for Cloud blob stores.
 *
 * @see CloudBlobPropertiesSupport
 * @since 3.37
 */
public abstract class CloudBlobStoreSupport<T extends AttributesLocation>
    extends BlobStoreSupport<T>
{
  /**
   * Executor for running cloud storage operations using Virtual Threads.
   * Virtual Threads are lightweight threads that are managed by the JVM and are particularly
   * well-suited for I/O-bound operations like cloud storage access.
   */
  private static final Executor VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  protected CloudBlobStoreSupport(
      final BlobIdLocationResolver blobIdLocationResolver,
      final DryRunPrefix dryRunPrefix)
  {
    super(blobIdLocationResolver, dryRunPrefix);
  }

  /**
   * Writes blob properties to the cloud storage.
   * This is an abstract method that must be implemented by concrete subclasses.
   *
   * @param blobId the blob identifier
   * @param headers the headers to write
   * @return the blob
   */
  protected abstract Blob writeBlobProperties(BlobId blobId, Map<String, String> headers);

  /**
   * Executes a cloud operation using a Virtual Thread.
   * This method is used to run I/O-bound operations asynchronously with minimal resource overhead.
   *
   * @param operation the operation to execute
   * @param <T> the return type of the operation
   * @return the result of the operation
   */
  protected <T> CompletableFuture<T> executeCloudOperationAsync(Callable<T> operation) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return operation.call();
      }
      catch (Exception e) {
        if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        }
        throw new RuntimeException("Error executing cloud operation", e);
      }
    }, VIRTUAL_THREAD_EXECUTOR);
  }

  /**
   * Executes a cloud operation using a Virtual Thread and waits for the result.
   * This method provides a synchronous wrapper around the asynchronous execution.
   *
   * @param operation the operation to execute
   * @param <T> the return type of the operation
   * @return the result of the operation
   */
  protected <T> T executeCloudOperation(Callable<T> operation) {
    try {
      return executeCloudOperationAsync(operation).join();
    }
    catch (Exception e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new RuntimeException("Error executing cloud operation", e);
    }
  }

  /**
   * Asynchronously writes blob properties to the cloud storage using a Virtual Thread.
   * This method improves throughput for cloud storage operations by using lightweight threads.
   *
   * @param blobId the blob identifier
   * @param headers the headers to write
   * @return a CompletableFuture that will complete with the blob
   */
  protected CompletableFuture<Blob> writeBlobPropertiesAsync(BlobId blobId, Map<String, String> headers) {
    return executeCloudOperationAsync(() -> writeBlobProperties(blobId, headers));
  }

  @Override
  protected BlobId getBlobId(final Map<String, String> headers, final BlobId assignedBlobId) {
    return super.getBlobId(removeTemporaryBlobHeaderIfPresent(headers), assignedBlobId);
  }

  @Override
  public final Blob makeBlobPermanent(final BlobId blobId, final Map<String, String> headers) {
    if (headers.containsKey(TEMPORARY_BLOB_HEADER)) {
      throw new IllegalArgumentException(
          String.format("Permanent blob headers must not contain entry with '%s' key.", TEMPORARY_BLOB_HEADER));
    }

    return Optional.ofNullable(get(blobId))
        .map(Blob::getHeaders)
        .filter(blobHeaders -> blobHeaders.containsKey(TEMPORARY_BLOB_HEADER))
        .map(__ -> executeCloudOperation(() -> writeBlobProperties(blobId, headers)))
        // We were given a blob that was already made permanent, so we need to copy it instead.
        .orElseGet(() -> super.makeBlobPermanent(blobId, headers));
  }

  /**
   * Asynchronously makes a blob permanent using a Virtual Thread.
   * This method improves throughput for blob permanence operations.
   *
   * @param blobId the blob identifier
   * @param headers the headers to apply
   * @return a CompletableFuture that will complete with the permanent blob
   */
  public CompletableFuture<Blob> makeBlobPermanentAsync(final BlobId blobId, final Map<String, String> headers) {
    if (headers.containsKey(TEMPORARY_BLOB_HEADER)) {
      CompletableFuture<Blob> future = new CompletableFuture<>();
      future.completeExceptionally(new IllegalArgumentException(
          String.format("Permanent blob headers must not contain entry with '%s' key.", TEMPORARY_BLOB_HEADER)));
      return future;
    }

    return CompletableFuture.supplyAsync(() -> {
      Blob blob = get(blobId);
      if (blob == null) {
        return super.makeBlobPermanent(blobId, headers);
      }
      
      Map<String, String> blobHeaders = blob.getHeaders();
      if (blobHeaders != null && blobHeaders.containsKey(TEMPORARY_BLOB_HEADER)) {
        return executeCloudOperation(() -> writeBlobProperties(blobId, headers));
      }
      
      // We were given a blob that was already made permanent, so we need to copy it instead.
      return super.makeBlobPermanent(blobId, headers);
    }, VIRTUAL_THREAD_EXECUTOR);
  }

  @Override
  public boolean deleteIfTemp(final BlobId blobId) {
    return executeCloudOperation(() -> {
      Blob blob = getBlobFromCache(blobId);
      if (blob != null) {
        Map<String, String> headers = blob.getHeaders();
        if (headers == null || headers.containsKey(TEMPORARY_BLOB_HEADER)) {
          return deleteHard(blobId);
        }
        log.debug("Not deleting. Blob with id: {} is permanent.", blobId.asUniqueString());
      }
      return false;
    });
  }

  /**
   * Asynchronously deletes a temporary blob using a Virtual Thread.
   * This method optimizes temporary blob cleanup operations.
   *
   * @param blobId the blob identifier
   * @return a CompletableFuture that will complete with true if the blob was deleted, false otherwise
   */
  public CompletableFuture<Boolean> deleteIfTempAsync(final BlobId blobId) {
    return executeCloudOperationAsync(() -> {
      Blob blob = getBlobFromCache(blobId);
      if (blob != null) {
        Map<String, String> headers = blob.getHeaders();
        if (headers == null || headers.containsKey(TEMPORARY_BLOB_HEADER)) {
          return deleteHard(blobId);
        }
        log.debug("Not deleting. Blob with id: {} is permanent.", blobId.asUniqueString());
      }
      return false;
    });
  }

  /**
   * Gets a blob from the cache.
   * This is an abstract method that must be implemented by concrete subclasses.
   *
   * @param blobId the blob identifier
   * @return the blob, or null if not found
   */
  public abstract Blob getBlobFromCache(final BlobId blobId);

  /**
   * Asynchronously gets a blob from the cache using a Virtual Thread.
   * This method improves throughput for blob retrieval operations.
   *
   * @param blobId the blob identifier
   * @return a CompletableFuture that will complete with the blob, or null if not found
   */
  public CompletableFuture<Blob> getBlobFromCacheAsync(final BlobId blobId) {
    return executeCloudOperationAsync(() -> getBlobFromCache(blobId));
  }

  private Map<String, String> removeTemporaryBlobHeaderIfPresent(final Map<String, String> headers) {
    Map<String, String> headersCopy = headers;
    if (headersCopy.containsKey(TEMPORARY_BLOB_HEADER)) {
      headersCopy = new HashMap<>(headers);
      headersCopy.remove(TEMPORARY_BLOB_HEADER);
    }
    return headersCopy;
  }
}