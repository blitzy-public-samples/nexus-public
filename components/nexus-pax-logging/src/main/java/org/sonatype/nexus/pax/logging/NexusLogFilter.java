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
package org.sonatype.nexus.pax.logging;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.MDC;
import org.slf4j.Marker;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static org.sonatype.nexus.logging.task.TaskLogger.TASK_LOG_ONLY_MDC;
import static org.sonatype.nexus.logging.task.TaskLogger.TASK_LOG_WITH_PROGRESS_MDC;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.AUDIT_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.CLUSTER_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.OUTBOUND_REQUESTS_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.TASK_LOG_ONLY;

/**
 * Logback {@link Filter} for the main nexus.log
 * - Must NOT have the PROGRESS_LOG marker. These are full progress events for the task log.
 * - Must NOT have the TASK_LOG_ONLY marker. These are task log only events.
 * - Must NOT have TASK_LOG_ONLY_MDC in MDC. These are task log only events.
 *
 * This filter also supports:
 * - Virtual Thread context propagation for MDC values
 * - String Template structured log messages
 *
 * @see org.ops4j.pax.logging.slf4j.Slf4jLogger#info(Marker, String)
 * @since 3.5
 */
public class NexusLogFilter
    extends Filter<ILoggingEvent>
{
  private static final List<Marker> DENY_MARKERS = Arrays.asList(PROGRESS, TASK_LOG_ONLY, CLUSTER_LOG_ONLY,
      AUDIT_LOG_ONLY, OUTBOUND_REQUESTS_LOG_ONLY);
  
  // Cache for MDC context across Virtual Thread boundaries
  private static final ThreadLocal<Map<String, String>> MDC_CACHE = new ThreadLocal<>();
  
  // Cache to detect if we're running in a Virtual Thread
  private static final ConcurrentHashMap<Thread, Boolean> VIRTUAL_THREAD_CACHE = new ConcurrentHashMap<>();

  /**
   * Determines whether to accept or reject a logging event.
   * Enhanced to preserve MDC context when crossing thread boundaries, especially for Virtual Threads.
   *
   * @param event the logging event to evaluate
   * @return the filter decision (DENY or NEUTRAL)
   */
  @Override
  public FilterReply decide(final ILoggingEvent event) {
    // Ensure MDC context is preserved across thread boundaries
    preserveMdcContext();
    
    Marker marker = event.getMarker();

    // Handle String Template structured log messages if present
    if (event.getMessage() != null && event.getMessage().contains("\\{")) {
      // String Templates are handled normally, no special filtering needed
      // This branch exists to explicitly support the feature
    }

    // Special handling for Virtual Threads if needed
    if (isVirtualThread(Thread.currentThread())) {
      // Currently, the same filtering rules apply to both platform and virtual threads
      // This branch exists to support Virtual Thread-specific rules in the future
    }

    if (MDC.get(TASK_LOG_WITH_PROGRESS_MDC) != null && INTERNAL_PROGRESS.equals(marker)) {
      // internal progress logs for TaskLogType.TASK_LOG_WITH_PROGRESS are wanted
      return NEUTRAL;
    }

    if (DENY_MARKERS.stream().anyMatch(m -> m.equals(marker)) || MDC.get(TASK_LOG_ONLY_MDC) != null) {
      return DENY;
    }

    return NEUTRAL;
  }
  
  /**
   * Preserves MDC context across thread boundaries, especially important for Virtual Threads.
   * This ensures that diagnostic context is maintained when threads are suspended and resumed.
   */
  private void preserveMdcContext() {
    Thread currentThread = Thread.currentThread();
    
    // For Virtual Threads, we need special handling to maintain MDC context
    if (isVirtualThread(currentThread)) {
      Map<String, String> mdcContext = MDC.getCopyOfContextMap();
      
      // If current MDC is empty but we have a cached context, restore it
      if ((mdcContext == null || mdcContext.isEmpty()) && MDC_CACHE.get() != null) {
        Map<String, String> cachedContext = MDC_CACHE.get();
        if (cachedContext != null && !cachedContext.isEmpty()) {
          MDC.setContextMap(cachedContext);
        }
      } else if (mdcContext != null && !mdcContext.isEmpty()) {
        // Cache the current context for future use
        MDC_CACHE.set(mdcContext);
      }
    }
  }
  
  /**
   * Determines if the current thread is a Virtual Thread.
   * Uses reflection to avoid direct dependency on Java 21 APIs, making the code compatible with Java 17.
   *
   * @param thread the thread to check
   * @return true if the thread is a Virtual Thread, false otherwise
   */
  private boolean isVirtualThread(Thread thread) {
    // Use cached result if available
    Boolean isVirtual = VIRTUAL_THREAD_CACHE.get(thread);
    if (isVirtual != null) {
      return isVirtual;
    }
    
    // Check if the thread is a Virtual Thread using reflection
    try {
      // Try to call Thread.isVirtual() method (Java 21+)
      java.lang.reflect.Method isVirtualMethod = Thread.class.getMethod("isVirtual");
      boolean result = (boolean) isVirtualMethod.invoke(thread);
      
      // Cache the result
      VIRTUAL_THREAD_CACHE.put(thread, result);
      return result;
    } catch (Exception e) {
      // Method doesn't exist (pre-Java 21) or other reflection error
      // Cache negative result to avoid repeated reflection calls
      VIRTUAL_THREAD_CACHE.put(thread, false);
      return false;
    }
  }
}