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
package org.sonatype.nexus.common.event;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.google.common.base.Throwables;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.SubscriberExceptionHandler;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

/**
 * Factory to create custom {@link EventBus} instances with behaviour not exposed via the public API.
 *
 * @since 3.2.1
 */
public class EventBusFactory
{
  private EventBusFactory() {
    // empty
  }

  /**
   * Creates a reentrant {@link EventBus} that dispatches events immediately as they appear on the same thread.
   *
   * (The old Guava behaviour used thread-local queues to provide strong non-reentrant ordering.)
   */
  public static EventBus reentrantEventBus(final String name) {
    return newEventBus(name, directExecutor());
  }

  /**
   * Creates a reentrant {@link EventBus} that dispatches events immediately as they appear using the executor.
   *
   * (The old Guava behaviour used a global queue to provide weak non-reentrant ordering before async dispatch.)
   */
  public static EventBus reentrantAsyncEventBus(final String name, final Executor executor) {
    return newEventBus(name, executor);
  }
  
  /**
   * Creates a reentrant {@link EventBus} that dispatches events using Java 21 Virtual Threads.
   * 
   * <p>Virtual Threads provide significant advantages for I/O-bound event processing:</p>
   * <ul>
   *   <li>Extremely lightweight threads (thousands can be created with minimal overhead)</li>
   *   <li>Automatic yielding during blocking I/O operations</li>
   *   <li>No thread pool configuration or tuning required</li>
   *   <li>Simplified concurrency model with improved debugging</li>
   *   <li>Reduced memory footprint compared to platform thread pools</li>
   * </ul>
   * 
   * <p>This executor is ideal for event handlers that perform I/O operations such as:</p>
   * <ul>
   *   <li>Database access</li>
   *   <li>File system operations</li>
   *   <li>Network calls</li>
   *   <li>Remote repository interactions</li>
   * </ul>
   * 
   * <p>Note: For CPU-intensive operations without I/O, traditional thread pools may still be more efficient.</p>
   *
   * @param name The name of the event bus
   * @return A new EventBus that uses Virtual Threads for event dispatch
   * @since 3.60.0
   */
  public static EventBus reentrantVirtualThreadEventBus(final String name) {
    // Check if Virtual Threads are supported in the current runtime
    if (!isVirtualThreadSupported()) {
      throw new UnsupportedOperationException("Virtual Threads are not supported in this Java runtime. Java 21+ is required.");
    }
    return newEventBus(name, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Checks if Virtual Threads are supported in the current Java runtime.
   * 
   * @return true if Virtual Threads are supported, false otherwise
   */
  private static boolean isVirtualThreadSupported() {
    try {
      // Check for the existence of the newVirtualThreadPerTaskExecutor method
      Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
      return true;
    }
    catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static EventBus newEventBus(final String name, final Executor executor) {
    try {
      Class<?> dispatcherClass = EventBus.class.getClassLoader().loadClass("com.google.common.eventbus.Dispatcher");

      // immediate dispatcher means events are always processed in a reentrant fashion
      Method immediateDispatcherMethod = dispatcherClass.getDeclaredMethod("immediate");
      immediateDispatcherMethod.setAccessible(true);

      // EventBus constructor that accepts custom executor is not yet part of the public API
      Constructor<EventBus> eventBusConstructor = EventBus.class.getDeclaredConstructor(
          String.class, Executor.class, dispatcherClass, SubscriberExceptionHandler.class);
      eventBusConstructor.setAccessible(true);

      Object immediateDispatcher = immediateDispatcherMethod.invoke(null);
      SubscriberExceptionHandler exceptionHandler = new Slf4jSubscriberExceptionHandler(name);

      return eventBusConstructor.newInstance(name, executor, immediateDispatcher, exceptionHandler);
    }
    catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw new LinkageError("Unable to create EventBus with custom executor", e);
    }
  }
}
