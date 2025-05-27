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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.OperationMetrics;
import org.sonatype.nexus.blobstore.api.OperationType;
import org.sonatype.nexus.internal.metrics.VirtualThreadMetrics;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import com.google.common.collect.ImmutableMap;

/**
 * Specialized metrics collector for monitoring BlobStore operations executed within Java 21 Virtual Threads.
 * 
 * <p>This class provides detailed statistics on thread execution patterns, performance characteristics,
 * and resource utilization for BlobStore operations running on Virtual Threads versus platform threads.</p>
 *
 * <p>It integrates with the global Virtual Thread monitoring framework to enable proper identification,
 * tracking, and reporting of blob operations running on Virtual Threads versus platform threads.</p>
 *
 * @since 3.60
 */
@Named("virtual-thread-blobstore")
@Singleton
public class VirtualThreadBlobStoreMetrics
    extends ComponentSupport
    implements MetricSet
{
  private final VirtualThreadMetrics virtualThreadMetrics;
  
  private final Map<OperationType, OperationTypeMetrics> operationMetrics = new ConcurrentHashMap<>();
  
  /**
   * Constructor.
   *
   * @param virtualThreadMetrics the global virtual thread metrics service
   * @param metricRegistry the metric registry to register metrics with
   */
  @Inject
  public VirtualThreadBlobStoreMetrics(
      final VirtualThreadMetrics virtualThreadMetrics,
      final MetricRegistry metricRegistry)
  {
    this.virtualThreadMetrics = virtualThreadMetrics;
    
    // Initialize metrics for each operation type
    for (OperationType type : OperationType.values()) {
      operationMetrics.put(type, new OperationTypeMetrics(type));
    }
    
    // Register this metric set with the registry
    metricRegistry.register(MetricRegistry.name("blobstore", "virtualthread"), this);
    
    log.info("Initialized Virtual Thread metrics for BlobStore operations");
  }

  @Override
  public Map<String, Metric> getMetrics() {
    ImmutableMap.Builder<String, Metric> builder = ImmutableMap.builder();
    
    // Add metrics for each operation type
    for (Map.Entry<OperationType, OperationTypeMetrics> entry : operationMetrics.entrySet()) {
      String opName = entry.getKey().name().toLowerCase();
      OperationTypeMetrics metrics = entry.getValue();
      
      builder.put(opName + ".virtualthread.count", (Gauge<Long>) metrics::getVirtualThreadCount);
      builder.put(opName + ".platformthread.count", (Gauge<Long>) metrics::getPlatformThreadCount);
      builder.put(opName + ".virtualthread.latency", metrics.getVirtualThreadTimer());
      builder.put(opName + ".platformthread.latency", metrics.getPlatformThreadTimer());
      builder.put(opName + ".virtualthread.size", (Gauge<Long>) metrics::getVirtualThreadBlobSize);
      builder.put(opName + ".platformthread.size", (Gauge<Long>) metrics::getPlatformThreadBlobSize);
      builder.put(opName + ".virtualthread.errors", (Gauge<Long>) metrics::getVirtualThreadErrors);
      builder.put(opName + ".platformthread.errors", (Gauge<Long>) metrics::getPlatformThreadErrors);
    }
    
    return builder.build();
  }
  
  /**
   * Records metrics for a BlobStore operation executed on a thread.
   *
   * @param operationType the type of operation being performed
   * @param durationMillis the execution duration in milliseconds
   * @param blobSize the size of the blob in bytes (if applicable, 0 otherwise)
   * @param isError whether the operation resulted in an error
   */
  public void recordOperation(
      final OperationType operationType,
      final long durationMillis,
      final long blobSize,
      final boolean isError)
  {
    boolean isVirtualThread = Thread.currentThread().isVirtual();
    OperationTypeMetrics metrics = operationMetrics.get(operationType);
    
    if (metrics != null) {
      if (isVirtualThread) {
        metrics.recordVirtualThreadOperation(durationMillis, blobSize, isError);
        // Also record in the global virtual thread metrics
        virtualThreadMetrics.recordExecution(durationMillis * 1_000_000); // Convert to nanos
      }
      else {
        metrics.recordPlatformThreadOperation(durationMillis, blobSize, isError);
      }
    }
  }
  
  /**
   * Updates the metrics from an existing OperationMetrics object, detecting the thread type automatically.
   *
   * @param operationType the type of operation
   * @param metrics the operation metrics to incorporate
   */
  public void updateMetrics(final OperationType operationType, final OperationMetrics metrics) {
    boolean isVirtualThread = Thread.currentThread().isVirtual();
    OperationTypeMetrics typeMetrics = operationMetrics.get(operationType);
    
    if (typeMetrics != null) {
      if (isVirtualThread) {
        typeMetrics.updateVirtualThreadMetrics(metrics);
      }
      else {
        typeMetrics.updatePlatformThreadMetrics(metrics);
      }
    }
  }
  
  /**
   * Determines if the current thread is a virtual thread.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
  
  /**
   * Inner class to track metrics for a specific operation type, separated by thread type.
   */
  private static class OperationTypeMetrics {
    private final OperationType operationType;
    private final AtomicLong virtualThreadCount = new AtomicLong();
    private final AtomicLong platformThreadCount = new AtomicLong();
    private final AtomicLong virtualThreadBlobSize = new AtomicLong();
    private final AtomicLong platformThreadBlobSize = new AtomicLong();
    private final AtomicLong virtualThreadErrors = new AtomicLong();
    private final AtomicLong platformThreadErrors = new AtomicLong();
    private final Timer virtualThreadTimer = new Timer();
    private final Timer platformThreadTimer = new Timer();
    
    OperationTypeMetrics(final OperationType operationType) {
      this.operationType = operationType;
    }
    
    void recordVirtualThreadOperation(final long durationMillis, final long blobSize, final boolean isError) {
      virtualThreadCount.incrementAndGet();
      virtualThreadTimer.update(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
      
      if (blobSize > 0) {
        virtualThreadBlobSize.addAndGet(blobSize);
      }
      
      if (isError) {
        virtualThreadErrors.incrementAndGet();
      }
    }
    
    void recordPlatformThreadOperation(final long durationMillis, final long blobSize, final boolean isError) {
      platformThreadCount.incrementAndGet();
      platformThreadTimer.update(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
      
      if (blobSize > 0) {
        platformThreadBlobSize.addAndGet(blobSize);
      }
      
      if (isError) {
        platformThreadErrors.incrementAndGet();
      }
    }
    
    void updateVirtualThreadMetrics(final OperationMetrics metrics) {
      virtualThreadCount.addAndGet(metrics.getSuccessfulRequests());
      virtualThreadErrors.addAndGet(metrics.getErrorRequests());
      virtualThreadBlobSize.addAndGet(metrics.getBlobSize());
      
      // Calculate average time per request for timer
      long successfulRequests = metrics.getSuccessfulRequests();
      if (successfulRequests > 0) {
        long avgTimePerRequest = metrics.getTimeOnRequests() / successfulRequests;
        virtualThreadTimer.update(avgTimePerRequest, java.util.concurrent.TimeUnit.MILLISECONDS);
      }
    }
    
    void updatePlatformThreadMetrics(final OperationMetrics metrics) {
      platformThreadCount.addAndGet(metrics.getSuccessfulRequests());
      platformThreadErrors.addAndGet(metrics.getErrorRequests());
      platformThreadBlobSize.addAndGet(metrics.getBlobSize());
      
      // Calculate average time per request for timer
      long successfulRequests = metrics.getSuccessfulRequests();
      if (successfulRequests > 0) {
        long avgTimePerRequest = metrics.getTimeOnRequests() / successfulRequests;
        platformThreadTimer.update(avgTimePerRequest, java.util.concurrent.TimeUnit.MILLISECONDS);
      }
    }
    
    long getVirtualThreadCount() {
      return virtualThreadCount.get();
    }
    
    long getPlatformThreadCount() {
      return platformThreadCount.get();
    }
    
    long getVirtualThreadBlobSize() {
      return virtualThreadBlobSize.get();
    }
    
    long getPlatformThreadBlobSize() {
      return platformThreadBlobSize.get();
    }
    
    long getVirtualThreadErrors() {
      return virtualThreadErrors.get();
    }
    
    long getPlatformThreadErrors() {
      return platformThreadErrors.get();
    }
    
    Timer getVirtualThreadTimer() {
      return virtualThreadTimer;
    }
    
    Timer getPlatformThreadTimer() {
      return platformThreadTimer;
    }
  }
}