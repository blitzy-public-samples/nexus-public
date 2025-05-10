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
 *
 * @since 3.20
 */
public class MemoryBlobSession
    extends TransactionSupport
    implements BlobSession<Transaction>
{
  private static final Logger log = LoggerFactory.getLogger(MemoryBlobSession.class);

  private final BlobStore blobStore;

  // Thread-safe sets using ConcurrentHashMap for Virtual Thread compatibility
  private final Set<BlobId> creates = ConcurrentHashMap.newKeySet();
  private final Set<BlobId> deletes = ConcurrentHashMap.newKeySet();

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
    // Process deletes in a way that's compatible with Virtual Threads
    deleteChangeSet(deletes, "committing " + reason());
    resetState();
  }

  @Override
  protected void doRollback() {
    // Process creates in a way that's compatible with Virtual Threads
    deleteChangeSet(creates, "rolling back " + reason());
    resetState();
  }

  @Override
  public void close() {
    if (!creates.isEmpty() || !deletes.isEmpty()) {
      log.warn("Uncommitted changes on close");
      rollback(); // match data-store behaviour: roll back uncommitted changes on close
    }
  }

  /**
   * Deletes a set of blobs, handling each operation independently to maintain integrity
   * in a Virtual Thread environment.
   *
   * @param changeSet the set of BlobIds to delete
   * @param reason the reason for deletion
   */
  private void deleteChangeSet(final Set<BlobId> changeSet, final String reason) {
    // Process each blob deletion independently to maintain integrity with Virtual Threads
    for (BlobId blobId : changeSet) {
      try {
        blobStore.delete(blobId, reason);
      }
      catch (Throwable e) { // NOSONAR: ignore all errors during commit/rollback
        // ...because we can't roll back any associated DB changes at this point
        log.warn("Problem deleting {}:{} while {}", storeName(), blobId, reason, e);
      }
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
   * Resets the transaction state by clearing the thread-safe sets.
   * This method is safe to call from any thread context including Virtual Threads.
   */
  private void resetState() {
    creates.clear();
    deletes.clear();
  }
}