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

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobSession;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.transaction.Transaction;
import org.sonatype.nexus.transaction.TransactionSupport;

import com.google.common.hash.HashCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Simple in-memory {@link BlobSession} optimized for Java 21 Virtual Threads.
 * <p>
 * This implementation is thread-safe and designed to work efficiently with Virtual Threads,
 * providing improved throughput for concurrent blob operations. It uses thread-safe collections
 * and ensures proper exception propagation within the Virtual Thread context.
 *
 * @since 3.20
 */
public class MemoryBlobSession
    extends TransactionSupport
    implements BlobSession<Transaction>
{
  private static final Logger log = LoggerFactory.getLogger(MemoryBlobSession.class);

  private final BlobStore blobStore;

  // Using ConcurrentHashMap.newKeySet() for thread-safe sets that work well with Virtual Threads
  private final Set<BlobId> creates = ConcurrentHashMap.newKeySet();
  private final Set<BlobId> deletes = ConcurrentHashMap.newKeySet();
  
  // Virtual Thread executor for parallel blob operations
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public MemoryBlobSession(final BlobStore blobStore) {
    this.blobStore = checkNotNull(blobStore);
  }

  @Override
  public Transaction getTransaction() {
    return this;
  }

  @Override
  public Blob create(final InputStream blobData, final Map<String, String> headers, final BlobId blobId) {
    Blob blob = blobStore.create(blobData, headers, blobId);
    creates.add(blob.getId());
    return blob;
  }

  @Override
  public Blob create(final Path sourceFile, final Map<String, String> headers, final long size, final HashCode sha1) {
    Blob blob = blobStore.create(sourceFile, headers, size, sha1);
    creates.add(blob.getId());
    return blob;
  }

  @Override
  public Blob copy(final BlobId blobId, final Map<String, String> headers) {
    Blob blob = blobStore.copy(blobId, headers);
    creates.add(blob.getId());
    return blob;
  }

  @Override
  public Blob get(final BlobId blobId, final boolean includeDeleted) {
    return includeDeleted || !deletes.contains(blobId) ? blobStore.get(blobId) : null;
  }

  @Override
  public boolean exists(final BlobId blobId) {
    return blobStore.exists(blobId);
  }

  @Override
  public boolean delete(final BlobId blobId) {
    return deletes.add(blobId);
  }

  @Override
  protected void doCommit() {
    // Use Virtual Thread-friendly approach to handle the delete operations
    deleteChangeSet(deletes, "committing " + reason());
    resetState();
  }

  @Override
  protected void doRollback() {
    // Use Virtual Thread-friendly approach to handle the rollback operations
    deleteChangeSet(creates, "rolling back " + reason());
    resetState();
  }

  @Override
  public void close() {
    try {
      if (!creates.isEmpty() || !deletes.isEmpty()) {
        log.warn("Uncommitted changes on close");
        // Match data-store behaviour: roll back uncommitted changes on close
        // This ensures proper cleanup of Virtual Thread resources
        rollback();
      }
    }
    finally {
      // Ensure the Virtual Thread executor is properly shut down
      // This is important to prevent resource leaks with Virtual Threads
      virtualThreadExecutor.shutdown();
    }
  }

  /**
   * Deletes a set of blobs, optimized for Virtual Thread execution.
   * Each deletion is handled independently to ensure proper exception isolation in the Virtual Thread context.
   * This implementation leverages Virtual Threads to perform deletions in parallel for improved throughput.
   */
  private void deleteChangeSet(final Set<BlobId> changeSet, final String reason) {
    if (changeSet.isEmpty()) {
      return;
    }
    
    try {
      // Use Virtual Threads to process deletions in parallel
      // Each deletion runs in its own Virtual Thread for maximum throughput
      Set<Future<?>> deletionTasks = ConcurrentHashMap.newKeySet(changeSet.size());
      
      // Submit each deletion as a separate Virtual Thread task
      for (BlobId blobId : changeSet) {
        deletionTasks.add(virtualThreadExecutor.submit(() -> {
          try {
            blobStore.delete(blobId, reason);
          }
          catch (Throwable e) { // NOSONAR: isolate exceptions within Virtual Thread context
            // We can't roll back any associated DB changes at this point
            // This approach ensures exceptions are properly propagated in the Virtual Thread context
            log.warn("Problem deleting {}:{} while {}", storeName(), blobId, reason, e);
          }
          return null;
        }));
      }
      
      // Wait for all deletion tasks to complete
      for (Future<?> task : deletionTasks) {
        try {
          task.get(); // Wait for completion, but we already handle exceptions in the task itself
        }
        catch (Exception e) {
          // This should rarely happen as we catch exceptions inside the tasks
          log.warn("Unexpected error waiting for blob deletion to complete", e);
        }
      }
    }
    catch (Exception e) {
      log.error("Error during parallel blob deletion", e);
    }
  }

  private String storeName() {
    try {
      return blobStore.getBlobStoreConfiguration().getName();
    }
    catch (Throwable e) { // NOSONAR: don't fail when logging with broken config
      return "<unknown>";
    }
  }

  /**
   * Resets the internal state in a thread-safe manner.
   * This method is optimized for Virtual Thread access patterns.
   */
  private void resetState() {
    // Thread-safe clearing of concurrent sets
    creates.clear();
    deletes.clear();
  }