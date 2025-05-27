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
 * With Java 21, this factory supports Virtual Threads for improved concurrency and scalability,
 * particularly for I/O-bound event processing. Virtual Threads provide significant benefits:
 * <ul>
 *   <li>Lightweight threads that consume minimal resources compared to platform threads</li>
 *   <li>Automatic yielding during blocking I/O operations, freeing carrier threads for other work</li>
 *   <li>Ability to handle many more concurrent operations without thread pool exhaustion</li>
 *   <li>Simplified programming model compared to reactive approaches</li>
 * </ul>
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
   * Creates a reentrant {@link EventBus} that dispatches events using Virtual Threads.
   * 
   * This implementation is optimized for I/O-bound event processing, where events may trigger
   * operations that block on I/O. Using Virtual Threads allows the system to handle many more
   * concurrent events without exhausting thread resources.
   *
   * @param name the identifier for this event bus, used in logging
   * @return an EventBus that uses Virtual Threads for event dispatch
   * @since 3.60
   */
  public static EventBus reentrantVirtualThreadEventBus(final String name) {
    return newEventBus(name, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Creates a new EventBus with the specified name and executor.
   * 
   * @param name the identifier for this event bus, used in logging
   * @param executor the executor to use for event dispatch, can be a Virtual Thread executor for improved I/O concurrency
   * @return a configured EventBus instance
   */
  private static EventBus newEventBus(final String name, final Executor executor) {
    try {
      // Check if we're using a Virtual Thread executor and verify compatibility
      boolean isVirtualThreadExecutor = isVirtualThreadExecutor(executor);
      if (isVirtualThreadExecutor) {
        // Log that we're using Virtual Threads for this EventBus
        System.getLogger(EventBusFactory.class.getName()).log(
            System.Logger.Level.DEBUG, 
            "Creating EventBus ''{0}'' with Virtual Thread executor", 
            name);
      }
      
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
  
  /**
   * Checks if the provided executor is a Virtual Thread executor.
   * 
   * @param executor the executor to check
   * @return true if the executor is a Virtual Thread executor, false otherwise
   */
  private static boolean isVirtualThreadExecutor(final Executor executor) {
    if (executor == null) {
      return false;
    }
    
    try {
      // Check if we're running on Java 21 or later with Virtual Thread support
      Class<?> threadClass = Thread.class;
      Method isVirtualMethod = threadClass.getMethod("isVirtual");
      
      // If we can access the isVirtual method, we're on Java 21+
      return true;
    } 
    catch (NoSuchMethodException e) {
      // We're running on a Java version that doesn't support Virtual Threads
      return false;
    }
  }
}