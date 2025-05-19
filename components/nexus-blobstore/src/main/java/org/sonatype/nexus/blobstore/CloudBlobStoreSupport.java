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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
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
   * Executor service using Virtual Threads for non-blocking I/O operations.
   * Virtual Threads are lightweight threads that are managed by the JVM rather than the OS,
   * allowing for much higher concurrency without the overhead of platform threads.
   */
  protected final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  protected CloudBlobStoreSupport(
      final BlobIdLocationResolver blobIdLocationResolver,
      final DryRunPrefix dryRunPrefix)
  {
    super(blobIdLocationResolver, dryRunPrefix);
  }

  /**
   * Writes blob properties to cloud storage using Virtual Threads for improved throughput.
   * This method should be implemented by subclasses to perform the actual write operation.
   *
   * @param blobId the blob ID
   * @param headers the headers to write
   * @return the blob
   */
  protected abstract Blob writeBlobProperties(BlobId blobId, Map<String, String> headers);

  /**
   * Asynchronously writes blob properties to cloud storage using Virtual Threads.
   * This method provides a non-blocking way to write blob properties.
   *
   * @param blobId the blob ID
   * @param headers the headers to write
   * @return a CompletableFuture that will complete with the blob
   */
  protected CompletableFuture<Blob> writeBlobPropertiesAsync(BlobId blobId, Map<String, String> headers) {
    return CompletableFuture.supplyAsync(() -> writeBlobProperties(blobId, headers), virtualThreadExecutor);
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
        .map(__ -> {
          try {
            // Use Virtual Threads for non-blocking I/O operations
            return writeBlobPropertiesAsync(blobId, headers).join();
          } catch (Exception e) {
            log.error("Error making blob permanent using Virtual Thread: {}", blobId.asUniqueString(), e);
            // Propagate the exception with the original cause
            if (e.getCause() != null) {
              throw new RuntimeException("Failed to make blob permanent: " + e.getCause().getMessage(), e.getCause());
            }
            throw new RuntimeException("Failed to make blob permanent: " + e.getMessage(), e);
          }
        })
        // We were given a blob that was already made permanent, so we need to copy it instead.
        .orElseGet(() -> super.makeBlobPermanent(blobId, headers));
  }

  @Override
  public boolean deleteIfTemp(final BlobId blobId) {
    Blob blob = getBlobFromCache(blobId);
    if (blob != null) {
      Map<String, String> headers = blob.getHeaders();
      if (headers == null || headers.containsKey(TEMPORARY_BLOB_HEADER)) {
        // Use Virtual Threads for non-blocking deletion
        CompletableFuture<Boolean> deleteFuture = CompletableFuture.supplyAsync(
            () -> deleteHard(blobId),
            virtualThreadExecutor
        );
        
        try {
          return deleteFuture.join();
        } catch (Exception e) {
          log.error("Error deleting temporary blob using Virtual Thread: {}", blobId.asUniqueString(), e);
          // Propagate the exception with the original cause
          if (e.getCause() != null) {
            throw new RuntimeException("Failed to delete temporary blob: " + e.getCause().getMessage(), e.getCause());
          }
          throw new RuntimeException("Failed to delete temporary blob: " + e.getMessage(), e);
        }
      }
      log.debug("Not deleting. Blob with id: {} is permanent.", blobId.asUniqueString());
    }
    return false;
  }

  /**
   * Retrieves a blob from the cache.
   * Implementations should leverage Virtual Threads for cache access operations.
   *
   * @param blobId the blob ID
   * @return the blob, or null if not found
   */
  public abstract Blob getBlobFromCache(final BlobId blobId);

  /**
   * Asynchronously retrieves a blob from the cache using Virtual Threads.
   * This method provides a non-blocking way to access the cache.
   *
   * @param blobId the blob ID
   * @return a CompletableFuture that will complete with the blob, or null if not found
   */
  protected CompletableFuture<Blob> getBlobFromCacheAsync(final BlobId blobId) {
    return CompletableFuture.supplyAsync(() -> getBlobFromCache(blobId), virtualThreadExecutor);
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