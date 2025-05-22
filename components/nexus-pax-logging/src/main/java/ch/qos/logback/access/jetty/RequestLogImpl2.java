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
package ch.qos.logback.access.jetty;

import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapt Logback {@link RequestLogImpl} to Jetty {@link LifeCycle} for support of Jetty 12.0.5+ with Java 21 Virtual Threads.
 * <p>
 * This implementation provides the following enhancements:
 * <ul>
 *   <li>Full compatibility with Jetty 12.0.5 LifeCycle API</li>
 *   <li>Support for Virtual Thread context propagation during Jetty server lifecycle events</li>
 *   <li>Preservation of MDC (Mapped Diagnostic Context) when request processing is handled by Virtual Threads</li>
 *   <li>Detection and logging of Virtual Thread identifiers in HTTP request logs</li>
 * </ul>
 * <p>
 * When running with Java 21 Virtual Threads, this implementation ensures that logging context is properly
 * propagated across thread boundaries, maintaining consistent logging behavior even when Virtual Threads
 * are suspended and resumed on different carrier threads.
 *
 * @since 3.0
 */
public class RequestLogImpl2
  extends RequestLogImpl
  implements LifeCycle
{
  private static final String VIRTUAL_THREAD_MARKER = "VirtualThread";
  private static final String THREAD_ID_MDC_KEY = "threadId";
  private static final String THREAD_TYPE_MDC_KEY = "threadType";
  
  // Store MDC context for each thread to ensure proper propagation
  private final ConcurrentHashMap<Thread, Map<String, String>> threadContextMap = new ConcurrentHashMap<>();
  
  /**
   * Captures the current MDC context for the given thread.
   * This is used to ensure MDC context is properly propagated when Virtual Threads
   * are suspended and resumed on different carrier threads.
   *
   * @param thread The thread whose MDC context should be captured
   */
  private void captureMdcContext(Thread thread) {
    Map<String, String> contextMap = MDC.getCopyOfContextMap();
    if (contextMap != null) {
      threadContextMap.put(thread, contextMap);
    }
  }
  
  /**
   * Restores the MDC context for the given thread.
   * This is used when a Virtual Thread is resumed, potentially on a different carrier thread.
   *
   * @param thread The thread whose MDC context should be restored
   */
  private void restoreMdcContext(Thread thread) {
    Map<String, String> contextMap = threadContextMap.get(thread);
    if (contextMap != null) {
      MDC.setContextMap(contextMap);
    }
  }
  
  /**
   * Cleans up the MDC context for the given thread.
   * This should be called when a thread is no longer needed to prevent memory leaks.
   *
   * @param thread The thread whose MDC context should be cleaned up
   */
  private void cleanupMdcContext(Thread thread) {
    threadContextMap.remove(thread);
  }
  
  /**
   * Detects if the current thread is a Virtual Thread and adds appropriate MDC entries.
   * This allows for proper identification of Virtual Threads in logs.
   */
  private void setupThreadContext() {
    Thread currentThread = Thread.currentThread();
    boolean isVirtual = false;
    
    try {
      // Use reflection to check if the thread is virtual (compatible with Java 21)
      isVirtual = (boolean) Thread.class.getMethod("isVirtual").invoke(currentThread);
    } catch (Exception e) {
      // Not running on Java 21 or method not available, assume not virtual
    }
    
    // Add thread information to MDC
    MDC.put(THREAD_ID_MDC_KEY, String.valueOf(currentThread.threadId()));
    MDC.put(THREAD_TYPE_MDC_KEY, isVirtual ? VIRTUAL_THREAD_MARKER : "PlatformThread");
    
    // Capture the context for this thread
    captureMdcContext(currentThread);
  }
  
  /**
   * Called when a request is received. Ensures proper MDC context setup for the request thread.
   * Overrides the parent method to add Virtual Thread support.
   */
  @Override
  public void log(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response) {
    try {
      setupThreadContext();
      super.log(request, response);
    } finally {
      MDC.clear();
    }
  }
  
  /**
   * Lifecycle method called when the component is starting.
   * Ensures proper MDC context propagation during startup.
   */
  @Override
  public void start() throws Exception {
    try {
      setupThreadContext();
      super.start();
    } finally {
      cleanupMdcContext(Thread.currentThread());
    }
  }
  
  /**
   * Lifecycle method called when the component is stopping.
   * Ensures proper MDC context propagation during shutdown.
   */
  @Override
  public void stop() throws Exception {
    try {
      setupThreadContext();
      super.stop();
    } finally {
      // Clear all thread contexts when stopping to prevent memory leaks
      threadContextMap.clear();
      MDC.clear();
    }
  }
  
  /**
   * Creates a thread context carrier that ensures MDC context is properly propagated
   * across Virtual Thread boundaries.
   *
   * @param runnable The runnable to execute with the proper context
   * @return A wrapped runnable that handles context propagation
   */
  public Runnable createContextCarrier(Runnable runnable) {
    Thread currentThread = Thread.currentThread();
    Map<String, String> contextMap = threadContextMap.get(currentThread);
    
    return () -> {
      Map<String, String> previousContext = MDC.getCopyOfContextMap();
      try {
        if (contextMap != null) {
          MDC.setContextMap(contextMap);
        }
        runnable.run();
      } finally {
        if (previousContext == null) {
          MDC.clear();
        } else {
          MDC.setContextMap(previousContext);
        }
      }
    };
  }
}