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
package org.sonatype.nexus.blobstore.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.OperationType;

import org.sonatype.goodies.common.ComponentSupport;

/**
 * Specialized metrics collector for monitoring BlobStore operations executed within Java 21 Virtual Threads.
 * Provides detailed statistics on thread execution patterns, performance characteristics, and resource utilization.
 * 
 * This class enables integration between the BlobStore metrics system and the global Virtual Thread monitoring
 * framework, allowing for proper identification, tracking, and reporting of blob operations running on
 * Virtual Threads versus platform threads.
 *
 * @since 3.60
 */
@Named
@Singleton
public class VirtualThreadBlobStoreMetrics extends ComponentSupport
{
  /**
   * Metrics for a specific operation type when executed on a Virtual Thread
   */
  public static class OperationMetrics
  {
    private final LongAdder operationCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder totalBytes = new LongAdder();
    private final LongAdder totalTimeNanos = new LongAdder();
    private final AtomicLong maxTimeNanos = new AtomicLong(0);
    private final LongAdder schedulingDelayNanos = new LongAdder();
    private final AtomicLong maxSchedulingDelayNanos = new AtomicLong(0);
    private final LongAdder pinnedCount = new LongAdder();
    private final LongAdder pinnedTimeNanos = new LongAdder();
    private final AtomicLong maxPinnedTimeNanos = new AtomicLong(0);
    
    /**
     * Records a successful operation execution
     *
     * @param executionTimeNanos the execution time in nanoseconds
     * @param bytes the number of bytes processed (if applicable, 0 otherwise)
     * @param schedulingDelayNanos the delay between operation submission and start in nanoseconds
     */
    public void recordSuccess(long executionTimeNanos, long bytes, long schedulingDelayNanos) {
      operationCount.increment();
      totalTimeNanos.add(executionTimeNanos);
      updateMax(maxTimeNanos, executionTimeNanos);
      
      if (bytes > 0) {
        totalBytes.add(bytes);
      }
      
      if (schedulingDelayNanos > 0) {
        this.schedulingDelayNanos.add(schedulingDelayNanos);
        updateMax(maxSchedulingDelayNanos, schedulingDelayNanos);
      }
    }
    
    /**
     * Records an operation that resulted in an error
     *
     * @param executionTimeNanos the execution time in nanoseconds before the error occurred
     */
    public void recordError(long executionTimeNanos) {
      errorCount.increment();
      totalTimeNanos.add(executionTimeNanos);
      updateMax(maxTimeNanos, executionTimeNanos);
    }
    
    /**
     * Records a pinning event where a Virtual Thread was pinned to its carrier thread
     *
     * @param pinnedTimeNanos the duration of the pinning in nanoseconds
     */
    public void recordPinning(long pinnedTimeNanos) {
      pinnedCount.increment();
      this.pinnedTimeNanos.add(pinnedTimeNanos);
      updateMax(maxPinnedTimeNanos, pinnedTimeNanos);
    }
    
    /**
     * @return the total number of operations executed
     */
    public long getOperationCount() {
      return operationCount.sum();
    }
    
    /**
     * @return the number of operations that resulted in errors
     */
    public long getErrorCount() {
      return errorCount.sum();
    }
    
    /**
     * @return the total number of bytes processed
     */
    public long getTotalBytes() {
      return totalBytes.sum();
    }
    
    /**
     * @return the total execution time in nanoseconds
     */
    public long getTotalTimeNanos() {
      return totalTimeNanos.sum();
    }
    
    /**
     * @return the maximum execution time in nanoseconds for any single operation
     */
    public long getMaxTimeNanos() {
      return maxTimeNanos.get();
    }
    
    /**
     * @return the average execution time in nanoseconds, or 0 if no operations have been executed
     */
    public double getAverageTimeNanos() {
      long count = operationCount.sum();
      return count > 0 ? (double) totalTimeNanos.sum() / count : 0;
    }
    
    /**
     * @return the total scheduling delay in nanoseconds
     */
    public long getSchedulingDelayNanos() {
      return schedulingDelayNanos.sum();
    }
    
    /**
     * @return the maximum scheduling delay in nanoseconds
     */
    public long getMaxSchedulingDelayNanos() {
      return maxSchedulingDelayNanos.get();
    }
    
    /**
     * @return the average scheduling delay in nanoseconds, or 0 if no operations have been executed
     */
    public double getAverageSchedulingDelayNanos() {
      long count = operationCount.sum();
      return count > 0 ? (double) schedulingDelayNanos.sum() / count : 0;
    }
    
    /**
     * @return the number of times Virtual Threads were pinned during this operation
     */
    public long getPinnedCount() {
      return pinnedCount.sum();
    }
    
    /**
     * @return the total time spent pinned in nanoseconds
     */
    public long getPinnedTimeNanos() {
      return pinnedTimeNanos.sum();
    }
    
    /**
     * @return the maximum time spent pinned in nanoseconds for any single operation
     */
    public long getMaxPinnedTimeNanos() {
      return maxPinnedTimeNanos.get();
    }
    
    /**
     * @return the average time spent pinned in nanoseconds, or 0 if no pinning has occurred
     */
    public double getAveragePinnedTimeNanos() {
      long count = pinnedCount.sum();
      return count > 0 ? (double) pinnedTimeNanos.sum() / count : 0;
    }
    
    /**
     * @return the percentage of operations that experienced pinning
     */
    public double getPinningPercentage() {
      long count = operationCount.sum();
      return count > 0 ? (double) pinnedCount.sum() / count * 100 : 0;
    }
    
    /**
     * Updates the maximum value in an AtomicLong if the new value is larger
     */
    private void updateMax(AtomicLong maxValue, long newValue) {
      long current;
      do {
        current = maxValue.get();
        if (newValue <= current) {
          break;
        }
      } while (!maxValue.compareAndSet(current, newValue));
    }
  }
  
  // Maps BlobStore ID -> Operation Type -> Metrics
  private final Map<String, Map<OperationType, OperationMetrics>> metricsMap = new ConcurrentHashMap<>();
  
  // Thread-local for tracking operation start times
  private final ThreadLocal<Instant> operationStartTime = new ThreadLocal<>();
  
  /**
   * Determines if the current thread is a Virtual Thread
   *
   * @return true if the current thread is a Virtual Thread, false otherwise
   */
  public boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
  
  /**
   * Records the start of a BlobStore operation on a Virtual Thread
   *
   * @return the start time, or null if the current thread is not a Virtual Thread
   */
  @Nullable
  public Instant recordOperationStart() {
    if (!isVirtualThread()) {
      return null;
    }
    
    Instant start = Instant.now();
    operationStartTime.set(start);
    return start;
  }
  
  /**
   * Records the completion of a BlobStore operation on a Virtual Thread
   *
   * @param blobStore the BlobStore that performed the operation
   * @param operationType the type of operation performed
   * @param bytes the number of bytes processed (if applicable, 0 otherwise)
   * @param error true if the operation resulted in an error, false otherwise
   * @param schedulingDelayNanos the delay between operation submission and start in nanoseconds (if known, 0 otherwise)
   * @return the duration of the operation, or null if the current thread is not a Virtual Thread or no start time was recorded
   */
  @Nullable
  public Duration recordOperationEnd(BlobStore blobStore, OperationType operationType, long bytes, boolean error, long schedulingDelayNanos) {
    if (!isVirtualThread()) {
      return null;
    }
    
    Instant start = operationStartTime.get();
    if (start == null) {
      log.debug("No operation start time recorded for {} operation on {}", operationType, blobStore.getBlobStoreConfiguration().getName());
      return null;
    }
    
    try {
      Instant end = Instant.now();
      Duration duration = Duration.between(start, end);
      long durationNanos = duration.toNanos();
      
      OperationMetrics metrics = getOrCreateMetrics(blobStore, operationType);
      if (error) {
        metrics.recordError(durationNanos);
      } else {
        metrics.recordSuccess(durationNanos, bytes, schedulingDelayNanos);
      }
      
      return duration;
    } finally {
      operationStartTime.remove();
    }
  }
  
  /**
   * Records a pinning event where a Virtual Thread was pinned to its carrier thread
   *
   * @param blobStore the BlobStore where the pinning occurred
   * @param operationType the type of operation being performed when pinning occurred
   * @param pinnedTimeNanos the duration of the pinning in nanoseconds
   */
  public void recordPinning(BlobStore blobStore, OperationType operationType, long pinnedTimeNanos) {
    if (!isVirtualThread()) {
      return;
    }
    
    OperationMetrics metrics = getOrCreateMetrics(blobStore, operationType);
    metrics.recordPinning(pinnedTimeNanos);
    
    if (log.isDebugEnabled()) {
      log.debug("Virtual Thread pinned for {}ms during {} operation on {}", 
          pinnedTimeNanos / 1_000_000.0, 
          operationType, 
          blobStore.getBlobStoreConfiguration().getName());
    }
  }
  
  /**
   * Gets metrics for all operations on all BlobStores
   *
   * @return a map of BlobStore ID to operation metrics
   */
  public Map<String, Map<OperationType, OperationMetrics>> getAllMetrics() {
    return metricsMap;
  }
  
  /**
   * Gets metrics for all operations on a specific BlobStore
   *
   * @param blobStore the BlobStore to get metrics for
   * @return a map of operation type to metrics, or null if no metrics exist for the BlobStore
   */
  @Nullable
  public Map<OperationType, OperationMetrics> getBlobStoreMetrics(BlobStore blobStore) {
    return metricsMap.get(blobStore.getBlobStoreConfiguration().getName());
  }
  
  /**
   * Gets metrics for a specific operation type on a specific BlobStore
   *
   * @param blobStore the BlobStore to get metrics for
   * @param operationType the operation type to get metrics for
   * @return the metrics for the specified operation, or null if no metrics exist
   */
  @Nullable
  public OperationMetrics getOperationMetrics(BlobStore blobStore, OperationType operationType) {
    Map<OperationType, OperationMetrics> blobStoreMetrics = getBlobStoreMetrics(blobStore);
    return blobStoreMetrics != null ? blobStoreMetrics.get(operationType) : null;
  }
  
  /**
   * Gets or creates metrics for a specific operation type on a specific BlobStore
   *
   * @param blobStore the BlobStore to get or create metrics for
   * @param operationType the operation type to get or create metrics for
   * @return the metrics for the specified operation
   */
  private OperationMetrics getOrCreateMetrics(BlobStore blobStore, OperationType operationType) {
    String blobStoreId = blobStore.getBlobStoreConfiguration().getName();
    return metricsMap
        .computeIfAbsent(blobStoreId, k -> new ConcurrentHashMap<>())
        .computeIfAbsent(operationType, k -> new OperationMetrics());
  }
  
  /**
   * Resets all metrics
   */
  public void resetAllMetrics() {
    metricsMap.clear();
  }
  
  /**
   * Resets metrics for a specific BlobStore
   *
   * @param blobStore the BlobStore to reset metrics for
   */
  public void resetBlobStoreMetrics(BlobStore blobStore) {
    metricsMap.remove(blobStore.getBlobStoreConfiguration().getName());
  }
}