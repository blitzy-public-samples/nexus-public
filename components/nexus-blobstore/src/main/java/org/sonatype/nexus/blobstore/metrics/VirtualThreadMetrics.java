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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects metrics about Virtual Thread usage in BlobStore operations.
 * This class is thread-safe and optimized for concurrent access from multiple Virtual Threads.
 *
 * @since 3.60
 */
public class VirtualThreadMetrics
{
  private final Map<String, LongAdder> operationCounts = new ConcurrentHashMap<>();
  
  private final AtomicLong totalOperations = new AtomicLong(0);
  
  private final AtomicLong peakConcurrentOperations = new AtomicLong(0);
  
  private final AtomicLong currentConcurrentOperations = new AtomicLong(0);
  
  /**
   * Records an operation performed by a Virtual Thread.
   * This method is thread-safe and can be called concurrently from multiple Virtual Threads.
   *
   * @param operationType the type of operation being performed
   */
  public void recordOperation(String operationType) {
    // Increment the total operations counter
    totalOperations.incrementAndGet();
    
    // Increment the operation-specific counter
    operationCounts.computeIfAbsent(operationType, k -> new LongAdder()).increment();
    
    // Track concurrent operations
    long current = currentConcurrentOperations.incrementAndGet();
    updatePeakConcurrentOperations(current);
    
    // Register a hook to decrement the concurrent operations counter when the Virtual Thread completes
    Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
      currentConcurrentOperations.decrementAndGet();
    });
  }
  
  /**
   * Updates the peak concurrent operations counter if the current value is higher.
   * This method uses atomic operations to ensure thread safety.
   *
   * @param currentValue the current number of concurrent operations
   */
  private void updatePeakConcurrentOperations(long currentValue) {
    long peak;
    do {
      peak = peakConcurrentOperations.get();
      if (currentValue <= peak) {
        return;
      }
    } while (!peakConcurrentOperations.compareAndSet(peak, currentValue));
  }
  
  /**
   * Returns the total number of operations performed by Virtual Threads.
   *
   * @return the total operation count
   */
  public long getTotalOperations() {
    return totalOperations.get();
  }
  
  /**
   * Returns the peak number of concurrent operations performed by Virtual Threads.
   *
   * @return the peak concurrent operation count
   */
  public long getPeakConcurrentOperations() {
    return peakConcurrentOperations.get();
  }
  
  /**
   * Returns the current number of concurrent operations being performed by Virtual Threads.
   *
   * @return the current concurrent operation count
   */
  public long getCurrentConcurrentOperations() {
    return currentConcurrentOperations.get();
  }
  
  /**
   * Returns a map of operation types to their counts.
   *
   * @return a map where keys are operation types and values are operation counts
   */
  public Map<String, Long> getOperationCounts() {
    Map<String, Long> result = new ConcurrentHashMap<>();
    operationCounts.forEach((key, value) -> result.put(key, value.sum()));
    return result;
  }
  
  /**
   * Returns the count for a specific operation type.
   *
   * @param operationType the type of operation
   * @return the count for the specified operation type, or 0 if no operations of that type have been recorded
   */
  public long getOperationCount(String operationType) {
    LongAdder adder = operationCounts.get(operationType);
    return adder != null ? adder.sum() : 0;
  }
  
  /**
   * Completes an operation, decrementing the concurrent operations counter.
   * This should be called when a Virtual Thread operation completes.
   */
  public void completeOperation() {
    currentConcurrentOperations.decrementAndGet();
  }
}