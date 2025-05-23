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

import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.metrics.VirtualThreadMetrics;
import org.sonatype.nexus.common.math.Math2;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An implementation of {@link BlobStoreMetrics} that supports adding to the blobCount and totalSize fields.
 * This implementation is thread-safe and optimized for concurrent access from Virtual Threads.
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

  private volatile boolean unavailable;
  
  private final VirtualThreadMetrics virtualThreadMetrics;

  /**
   * Constructs a new AccumulatingBlobStoreMetrics instance.
   *
   * @param blobCount initial blob count
   * @param totalSize initial total size
   * @param availableSpaceByFileStore map of available space by file store
   * @param unlimited whether the blob store has unlimited capacity
   */
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
    this.unavailable = false;
    this.virtualThreadMetrics = new VirtualThreadMetrics();
  }

  @Override
  public long getBlobCount() {
    return blobCount.get();
  }

  /**
   * Atomically adds the specified value to the blob count.
   * This method is thread-safe and optimized for concurrent access from Virtual Threads.
   *
   * @param delta the value to add
   */
  public void addBlobCount(long delta) {
    // Record this operation if running in a Virtual Thread
    if (Thread.currentThread().isVirtual()) {
      virtualThreadMetrics.recordOperation("addBlobCount");
    }
    blobCount.addAndGet(delta);
  }

  @Override
  public long getTotalSize() {
    return totalSize.get();
  }

  /**
   * Atomically adds the specified value to the total size.
   * This method is thread-safe and optimized for concurrent access from Virtual Threads.
   *
   * @param delta the value to add
   */
  public void addTotalSize(long delta) {
    // Record this operation if running in a Virtual Thread
    if (Thread.currentThread().isVirtual()) {
      virtualThreadMetrics.recordOperation("addTotalSize");
    }
    totalSize.addAndGet(delta);
  }

  @Override
  public long getAvailableSpace() {
    // Using parallel stream for efficient processing with Virtual Threads
    // Record this operation if running in a Virtual Thread
    if (Thread.currentThread().isVirtual()) {
      virtualThreadMetrics.recordOperation("getAvailableSpace");
    }
    
    return availableSpaceByFileStore.values()
        .parallelStream()
        .reduce(Math2::addClamped)
        .orElse(0L);
  }

  @Override
  public boolean isUnlimited() {
    return unlimited;
  }

  @Override
  public Map<String, Long> getAvailableSpaceByFileStore() {
    return availableSpaceByFileStore;
  }

  @Override
  public boolean isUnavailable() {
    return unavailable;
  }

  /**
   * Sets the unavailable status of this metrics instance.
   * This method is thread-safe and can be called from Virtual Threads.
   *
   * @param unavailable true if the metrics should be marked as unavailable
   */
  public void setUnavailable(boolean unavailable) {
    this.unavailable = unavailable;
  }
  
  /**
   * Returns metrics about Virtual Thread usage in this BlobStore.
   * 
   * @return the Virtual Thread metrics
   * @since 3.60
   */
  public VirtualThreadMetrics getVirtualThreadMetrics() {
    return virtualThreadMetrics;
  }
  
  /**
   * Records an operation performed by a Virtual Thread.
   * This method is thread-safe and can be called from any thread.
   * 
   * @param operationType the type of operation being performed
   * @since 3.60
   */
  public void recordVirtualThreadOperation(String operationType) {
    if (Thread.currentThread().isVirtual()) {
      virtualThreadMetrics.recordOperation(operationType);
    }
  }
  
  /**
   * Completes a Virtual Thread operation, updating the metrics accordingly.
   * This method should be called when a Virtual Thread operation completes.
   * 
   * @since 3.60
   */
  public void completeVirtualThreadOperation() {
    if (Thread.currentThread().isVirtual()) {
      virtualThreadMetrics.completeOperation();
    }
  }
}