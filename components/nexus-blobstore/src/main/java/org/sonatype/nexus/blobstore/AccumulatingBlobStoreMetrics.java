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

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.common.math.Math2;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An implementation of {@link BlobStoreMetrics} that supports adding to the blobCount and totalSize fields.
 * This implementation is thread-safe and optimized for use with Java 21 Virtual Threads.
 *
 * @since 3.2.1
 */
public class AccumulatingBlobStoreMetrics
    implements BlobStoreMetrics
{
  private final AtomicLong blobCount;

  private final AtomicLong totalSize;

  private final Map<String, Long> availableSpaceByFileStore;

  private final boolean unlimited;
  
  private final ReadWriteLock availabilityLock = new ReentrantReadWriteLock();
  
  private volatile boolean unavailable = false;

  public AccumulatingBlobStoreMetrics(
      final long blobCount,
      final long totalSize,
      final Map<String, Long> availableSpaceByFileStore,
      final boolean unlimited)
  {
    this.blobCount = new AtomicLong(blobCount);
    this.totalSize = new AtomicLong(totalSize);
    this.availableSpaceByFileStore = checkNotNull(availableSpaceByFileStore);
    this.unlimited = unlimited;
  }

  @Override
  public long getBlobCount() {
    return blobCount.get();
  }

  /**
   * Thread-safe method to add to the blob count.
   * Optimized for concurrent access by Virtual Threads.
   */
  public void addBlobCount(long count) {
    blobCount.addAndGet(count);
  }

  @Override
  public long getTotalSize() {
    return totalSize.get();
  }

  /**
   * Thread-safe method to add to the total size.
   * Optimized for concurrent access by Virtual Threads.
   */
  public void addTotalSize(long size) {
    totalSize.addAndGet(size);
  }

  @Override
  public long getAvailableSpace() {
    availabilityLock.readLock().lock();
    try {
      if (unavailable) {
        return 0L;
      }
      
      // Use a more efficient approach for Virtual Threads
      return availableSpaceByFileStore.values().stream()
          .reduce(Math2::addClamped)
          .orElse(0L);
    } finally {
      availabilityLock.readLock().unlock();
    }
  }

  @Override
  public boolean isUnlimited() {
    return unlimited;
  }

  @Override
  public Map<String, Long> getAvailableSpaceByFileStore() {
    availabilityLock.readLock().lock();
    try {
      return availableSpaceByFileStore;
    } finally {
      availabilityLock.readLock().unlock();
    }
  }

  @Override
  public boolean isUnavailable() {
    return unavailable;
  }
  
  /**
   * Sets the availability status of this metrics instance.
   * Thread-safe and optimized for Virtual Thread access.
   *
   * @param unavailable true if the metrics should be marked as unavailable
   */
  public void setUnavailable(boolean unavailable) {
    availabilityLock.writeLock().lock();
    try {
      this.unavailable = unavailable;
    } finally {
      availabilityLock.writeLock().unlock();
    }
  }
  
  /**
   * Updates the available space for a specific file store.
   * Thread-safe and optimized for Virtual Thread access.
   *
   * @param fileStore the file store identifier
   * @param availableSpace the new available space value
   */
  public void updateAvailableSpace(String fileStore, long availableSpace) {
    checkNotNull(fileStore);
    availabilityLock.writeLock().lock();
    try {
      availableSpaceByFileStore.put(fileStore, availableSpace);
    } finally {
      availabilityLock.writeLock().unlock();
    }
  }
}