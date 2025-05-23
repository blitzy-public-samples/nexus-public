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
 * 
 * <p>This interceptor also supports tracking thread type (platform or virtual) for operations that are
 * annotated with {@link MonitoringBlobStoreMetrics#trackThreadType()} set to true.</p>
 *
 * @since 3.38
 */
public class BlobStoreAnalyticsInterceptor
    extends ComponentSupport
    implements MethodInterceptor
{
  /**
   * Determines if the current thread is a virtual thread.
   * 
   * @return true if the current thread is a virtual thread, false otherwise
   */
  private boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
  
  @Override
  public Object invoke(final MethodInvocation invocation) throws Throwable {
    String clazz = invocation.getThis().getClass().getSimpleName();
    Method method = invocation.getMethod();
    String methodName = method.getName();

    MonitoringBlobStoreMetrics metricsAnnotation = method.getAnnotation(MonitoringBlobStoreMetrics.class);
    checkState(metricsAnnotation != null);
    OperationType operationType = metricsAnnotation.operationType();
    boolean trackThreadType = metricsAnnotation.trackThreadType();
    boolean virtualThreadCompatible = metricsAnnotation.virtualThreadCompatible();

    // Log if a virtual thread compatible operation is running on a platform thread
    if (virtualThreadCompatible && trackThreadType && !isVirtualThread()) {
      log.debug("Virtual thread compatible operation running on platform thread: class={}, methodName={}", 
          clazz, methodName);
    }

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

    long start = System.currentTimeMillis();
    try {
      Object result = invocation.proceed();

      // record metrics only in case of successful processing.
      operationMetrics.addSuccessfulRequest();
      operationMetrics.addTimeOnRequests(System.currentTimeMillis() - start);
      
      // Track thread type if requested
      if (trackThreadType) {
        if (isVirtualThread()) {
          // Add virtual thread specific metrics if needed
          log.trace("Operation executed on virtual thread: class={}, methodName={}", clazz, methodName);
        } else {
          // Add platform thread specific metrics if needed
          log.trace("Operation executed on platform thread: class={}, methodName={}", clazz, methodName);
        }
      }

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
  }
}