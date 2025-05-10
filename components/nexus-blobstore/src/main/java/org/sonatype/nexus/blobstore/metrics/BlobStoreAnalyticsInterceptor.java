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
 * This implementation supports both platform threads and Java 21 virtual threads, ensuring accurate metrics
 * collection even when operations span across virtual thread yields.
 *
 * @since 3.38
 */
public class BlobStoreAnalyticsInterceptor
    extends ComponentSupport
    implements MethodInterceptor
{
  // Thread-local storage for operation start times, maintained across virtual thread yields
  private static final ThreadLocal<ConcurrentHashMap<String, Long>> OPERATION_START_TIMES = 
      ThreadLocal.withInitial(ConcurrentHashMap::new);

  @Override
  public Object invoke(final MethodInvocation invocation) throws Throwable {
    String clazz = invocation.getThis().getClass().getSimpleName();
    Method method = invocation.getMethod();
    String methodName = method.getName();
    String operationKey = clazz + "." + methodName;

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

    // Use nanoTime for more precise timing, especially important for virtual threads
    // that may yield during execution
    long startNanos = System.nanoTime();
    
    // Store the start time in thread-local storage to maintain it across virtual thread yields
    OPERATION_START_TIMES.get().put(operationKey, startNanos);
    
    try {
      Object result = invocation.proceed();

      // Retrieve the start time from thread-local storage to ensure correct timing
      // even if the virtual thread yielded during execution
      Long storedStartTime = OPERATION_START_TIMES.get().remove(operationKey);
      long elapsedNanos = System.nanoTime() - (storedStartTime != null ? storedStartTime : startNanos);
      
      // Convert nanoseconds to milliseconds for the metrics
      long elapsedMillis = elapsedNanos / 1_000_000;

      // Record metrics only in case of successful processing
      operationMetrics.addSuccessfulRequest();
      operationMetrics.addTimeOnRequests(elapsedMillis);

      if (result instanceof BlobSupport) {
        long totalSize = ((BlobSupport) result).getMetrics().getContentSize();
        operationMetrics.addBlobSize(totalSize);
      }

      return result;
    }
    catch (Exception e) {
      operationMetrics.addErrorRequest();
      throw e;
    }
    finally {
      // Clean up thread-local storage to prevent memory leaks
      OPERATION_START_TIMES.get().remove(operationKey);
    }
  }
}