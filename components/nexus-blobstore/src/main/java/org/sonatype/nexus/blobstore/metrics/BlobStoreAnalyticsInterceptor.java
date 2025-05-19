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
import java.util.concurrent.TimeUnit;

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
 * Optimized for both platform threads and virtual threads in Java 21+.
 *
 * @since 3.38
 */
public class BlobStoreAnalyticsInterceptor
    extends ComponentSupport
    implements MethodInterceptor
{
  /**
   * Determines if the current thread is a virtual thread (Java 21+).
   * 
   * @return true if the current thread is a virtual thread, false otherwise
   */
  private boolean isVirtualThread() {
    try {
      // Use reflection to avoid direct dependency on Java 21 API
      Method isVirtualMethod = Thread.class.getMethod("isVirtual");
      return (Boolean) isVirtualMethod.invoke(Thread.currentThread());
    }
    catch (Exception e) {
      // If the method doesn't exist (pre-Java 21) or any other exception occurs,
      // assume it's not a virtual thread
      return false;
    }
  }

  /**
   * Gets the current time in nanoseconds for high-precision timing.
   * 
   * @return the current time in nanoseconds
   */
  private long getCurrentTimeNanos() {
    return System.nanoTime();
  }

  /**
   * Calculates elapsed time in milliseconds from a start time in nanoseconds.
   * 
   * @param startTimeNanos the start time in nanoseconds
   * @return the elapsed time in milliseconds
   */
  private long getElapsedTimeMillis(long startTimeNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
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

    // Check if running in a virtual thread context for optimized handling
    boolean isVirtual = isVirtualThread();
    if (isVirtual && log.isTraceEnabled()) {
      log.trace("Executing in virtual thread context: class={}, methodName={}", clazz, methodName);
    }

    // Use high-precision timing for better accuracy in high-throughput scenarios
    long startTimeNanos = getCurrentTimeNanos();
    try {
      Object result = invocation.proceed();

      // Record metrics only in case of successful processing
      operationMetrics.addSuccessfulRequest();
      
      // Calculate elapsed time with nanosecond precision, then convert to milliseconds
      long elapsedTimeMillis = getElapsedTimeMillis(startTimeNanos);
      operationMetrics.addTimeOnRequests(elapsedTimeMillis);

      // For virtual threads, we can optimize by reducing logging overhead
      if (result instanceof BlobSupport) {
        long totalSize = ((BlobSupport) result).getMetrics().getContentSize();
        operationMetrics.addBlobSize(totalSize);
        
        // Additional debug logging for non-virtual threads only to reduce overhead
        if (!isVirtual && log.isDebugEnabled()) {
          log.debug("Recorded blob metrics for operation={}, size={}, time={}", 
              operationType, totalSize, elapsedTimeMillis);
        }
      }

      return result;
    }
    catch (Exception e) {
      operationMetrics.addErrorRequest();
      
      // Only log detailed error information for non-virtual threads to reduce overhead
      if (!isVirtual && log.isDebugEnabled()) {
        log.debug("Error during blob operation={}, class={}, method={}", 
            operationType, clazz, methodName, e);
      }
      
      throw e;
    }
  }
}