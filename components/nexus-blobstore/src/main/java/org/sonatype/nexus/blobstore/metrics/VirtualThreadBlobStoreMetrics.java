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

import org.sonatype.nexus.blobstore.api.OperationMetrics;
import org.sonatype.nexus.blobstore.api.OperationType;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Specialized metrics collection for Virtual Threads to optimize performance in high-throughput scenarios.
 * This class provides thread-safe metrics collection with reduced overhead for Virtual Thread operations.
 *
 * @since 3.60
 */
public class VirtualThreadBlobStoreMetrics {
  
  private final Map<OperationType, OperationMetrics> operationMetrics;
  private final AtomicLong lastUpdateTimestamp;
  
  /**
   * Constructs a new instance with initialized metrics for all operation types.
   */
  public VirtualThreadBlobStoreMetrics() {
    this.operationMetrics = new EnumMap<>(OperationType.class);
    for (OperationType type : OperationType.values()) {
      this.operationMetrics.put(type, new OperationMetrics());
    }
    this.lastUpdateTimestamp = new AtomicLong(System.nanoTime());
  }
  
  /**
   * Gets the operation metrics for the specified operation type.
   *
   * @param operationType the operation type
   * @return the operation metrics
   */
  public OperationMetrics getMetrics(OperationType operationType) {
    return operationMetrics.get(operationType);
  }
  
  /**
   * Records a successful operation with the given size and duration.
   *
   * @param operationType the operation type
   * @param blobSize the size of the blob in bytes
   * @param durationNanos the duration of the operation in nanoseconds
   */
  public void recordSuccessfulOperation(OperationType operationType, long blobSize, long durationNanos) {
    OperationMetrics metrics = operationMetrics.get(operationType);
    metrics.addSuccessfulRequest();
    metrics.addTimeOnRequests(durationNanos / 1_000_000); // Convert nanos to millis
    if (blobSize > 0) {
      metrics.addBlobSize(blobSize);
    }
    lastUpdateTimestamp.set(System.nanoTime());
  }
  
  /**
   * Records an error operation.
   *
   * @param operationType the operation type
   */
  public void recordErrorOperation(OperationType operationType) {
    OperationMetrics metrics = operationMetrics.get(operationType);
    metrics.addErrorRequest();
    lastUpdateTimestamp.set(System.nanoTime());
  }
  
  /**
   * Gets the timestamp of the last update in nanoseconds.
   *
   * @return the last update timestamp
   */
  public long getLastUpdateTimestamp() {
    return lastUpdateTimestamp.get();
  }
  
  /**
   * Gets a map of all operation metrics.
   *
   * @return a map of operation type to operation metrics
   */
  public Map<OperationType, OperationMetrics> getAllMetrics() {
    return new EnumMap<>(operationMetrics);
  }
}