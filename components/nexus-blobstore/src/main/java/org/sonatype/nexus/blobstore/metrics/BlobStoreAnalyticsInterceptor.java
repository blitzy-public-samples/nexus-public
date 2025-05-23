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

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.BlobSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.OperationMetrics;
import org.sonatype.nexus.blobstore.api.OperationType;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import static com.google.common.base.Preconditions.checkState;

/**
 * A method interceptor which monitor blob store operations (see {@link OperationType}) of the annotated method.
 * Optimized for both platform threads and virtual threads in Java 21.
 *
 * @since 3.38
 */
public class BlobStoreAnalyticsInterceptor
    extends ComponentSupport
    implements MethodInterceptor
{
  // Cache of VirtualThreadBlobStoreMetrics instances per BlobStore to avoid repeated lookups
  private final Map<BlobStore, VirtualThreadBlobStoreMetrics> virtualThreadMetricsCache = new ConcurrentHashMap<>();

  /**
   * Determines if the current thread is a virtual thread.
   * Uses Java 21's Thread.currentThread().isVirtual() method.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  private boolean isVirtualThread() {
    try {
      // Use reflection to avoid compilation errors on Java versions before 21
      Method isVirtualMethod = Thread.class.getMethod("isVirtual");
      return (Boolean) isVirtualMethod.invoke(Thread.currentThread());
    } catch (Exception e) {
      // If the method doesn't exist or fails, we're not on a virtual thread
      return false;
    }
  }

  /**
   * Gets or creates a VirtualThreadBlobStoreMetrics instance for the given BlobStore.
   *
   * @param blobStore the BlobStore to get metrics for
   * @return the VirtualThreadBlobStoreMetrics instance
   */
  private VirtualThreadBlobStoreMetrics getVirtualThreadMetrics(BlobStore blobStore) {
    return virtualThreadMetricsCache.computeIfAbsent(blobStore, k -> new VirtualThreadBlobStoreMetrics());
  }

  @Override
  public Object invoke(final MethodInvocation invocation) throws Throwable {
    String clazz = invocation.getThis().getClass().getSimpleName();
    Method method = invocation.getMethod();
    String methodName = method.getName();

    MonitoringBlobStoreMetrics metricsAnnotation = method.getAnnotation(MonitoringBlobStoreMetrics.class);
    checkState(metricsAnnotation != null);
    OperationType operationType = metricsAnnotation.operationType();

    BlobStore blobStore;
    OperationMetrics operationMetrics;
    if (invocation.getThis() instanceof BlobStore) {
      blobStore = (BlobStore) invocation.getThis();
      operationMetrics = blobStore.getOperationMetricsDelta().get(operationType);
    }
    else {
      log.info("Can't monitor operation metrics for class={}, methodName={}", clazz, methodName);
      return invocation.proceed();
    }

    // Check if we're running in a virtual thread context
    boolean isVirtual = isVirtualThread();
    VirtualThreadBlobStoreMetrics virtualMetrics = null;
    
    if (isVirtual) {
      virtualMetrics = getVirtualThreadMetrics(blobStore);
    }

    // Use nanoTime for more precise timing in high-throughput scenarios
    long startTime = isVirtual ? System.nanoTime() : System.currentTimeMillis();
    
    try {
      Object result = invocation.proceed();

      // Record metrics based on thread type
      if (isVirtual && virtualMetrics != null) {
        // For virtual threads, use specialized metrics collection
        long blobSize = 0;
        if (result instanceof BlobSupport) {
          blobSize = ((BlobSupport) result).getMetrics().getContentSize();
        }
        virtualMetrics.recordSuccessfulOperation(operationType, blobSize, System.nanoTime() - startTime);
        
        // Also update standard metrics for consistency
        operationMetrics.addSuccessfulRequest();
        operationMetrics.addTimeOnRequests((System.nanoTime() - startTime) / 1_000_000); // Convert nanos to millis
        if (blobSize > 0) {
          operationMetrics.addBlobSize(blobSize);
        }
      } else {
        // For platform threads, use standard metrics collection
        operationMetrics.addSuccessfulRequest();
        operationMetrics.addTimeOnRequests(System.currentTimeMillis() - startTime);

        if (result instanceof BlobSupport) {
          long totalSize = ((BlobSupport) result).getMetrics().getContentSize();
          operationMetrics.addBlobSize(totalSize);
        }
      }

      return result;
    }
    catch (Exception e) {
      // Record error metrics based on thread type
      if (isVirtual && virtualMetrics != null) {
        virtualMetrics.recordErrorOperation(operationType);
      }
      operationMetrics.addErrorRequest();
      throw e;
    }
  }
}