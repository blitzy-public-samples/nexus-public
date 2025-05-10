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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.common.log.DryRunPrefix;

/**
 * Support class for Cloud blob stores.
 * 
 * Implements Virtual Threads for cloud storage operations to improve throughput and scalability.
 *
 * @see CloudBlobPropertiesSupport
 * @since 3.37
 */
public abstract class CloudBlobStoreSupport<T extends AttributesLocation>
    extends BlobStoreSupport<T>
{
  protected CloudBlobStoreSupport(
      final BlobIdLocationResolver blobIdLocationResolver,
      final DryRunPrefix dryRunPrefix)
  {
    super(blobIdLocationResolver, dryRunPrefix);
  }

  /**
   * Executes a cloud storage operation in a Virtual Thread.
   * 
   * @param <R> the return type of the operation
   * @param operation the operation to execute
   * @return the result of the operation
   */
  protected <R> R executeInVirtualThread(Callable<R> operation) {
    try {
      // Create and start a virtual thread to execute the operation
      Future<R> future = Thread.ofVirtual()
          .name("cloud-blob-operation-" + Thread.currentThread().getName())
          .start(operation);
      
      // Wait for the operation to complete and return the result
      return future.get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Cloud blob operation interrupted", e);
    }
    catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new RuntimeException("Cloud blob operation failed", cause);
    }
  }

  /**
   * Executes a cloud storage operation in a Virtual Thread with a supplier.
   * 
   * @param <R> the return type of the operation
   * @param supplier the supplier to execute
   * @return the result of the operation
   */
  protected <R> R executeInVirtualThread(Supplier<R> supplier) {
    return executeInVirtualThread(() -> supplier.get());
  }

  /**
   * Executes a cloud storage operation in a Virtual Thread with no return value.
   * 
   * @param runnable the runnable to execute
   */
  protected void executeInVirtualThread(Runnable runnable) {
    executeInVirtualThread(() -> {
      runnable.run();
      return null;
    });
  }

  /**
   * Writes blob properties using a Virtual Thread for improved throughput.
   * 
   * @param blobId the blob ID
   * @param headers the headers to write
   * @return the blob
   */
  protected abstract Blob writeBlobProperties(BlobId blobId, Map<String, String> headers);

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

    // Execute the makeBlobPermanent operation in a Virtual Thread for improved throughput
    return executeInVirtualThread(() -> 
      Optional.ofNullable(get(blobId))
          .map(Blob::getHeaders)
          .filter(blobHeaders -> blobHeaders.containsKey(TEMPORARY_BLOB_HEADER))
          .map(__ -> writeBlobProperties(blobId, headers))
          // We were given a blob that was already made permanent, so we need to copy it instead.
          .orElseGet(() -> super.makeBlobPermanent(blobId, headers))
    );
  }

  @Override
  public boolean deleteIfTemp(final BlobId blobId) {
    // Execute the deleteIfTemp operation in a Virtual Thread for improved throughput
    return executeInVirtualThread(() -> {
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
   * Implementations should consider using Virtual Threads for this operation.
   * 
   * @param blobId the blob ID
   * @return the blob, or null if not found
   */
  public abstract Blob getBlobFromCache(final BlobId blobId);

  private Map<String, String> removeTemporaryBlobHeaderIfPresent(final Map<String, String> headers) {
    Map<String, String> headersCopy = headers;
    if (headersCopy.containsKey(TEMPORARY_BLOB_HEADER)) {
      headersCopy = new HashMap<>(headers);
      headersCopy.remove(TEMPORARY_BLOB_HEADER);
    }
    return headersCopy;
  }
}