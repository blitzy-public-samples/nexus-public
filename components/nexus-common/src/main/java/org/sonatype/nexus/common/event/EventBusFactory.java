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
import java.lang.reflect.InaccessibleObjectException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.SubscriberExceptionHandler;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.lang.StringTemplate.STR;

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
   * Creates a reentrant {@link EventBus} that dispatches events using Java 21 virtual threads.
   * This provides optimal performance for I/O-bound event handlers with minimal resource overhead.
   *
   * @since 3.60.0
   */
  public static EventBus reentrantVirtualThreadEventBus(final String name) {
    return newEventBus(name, Executors.newVirtualThreadPerTaskExecutor());
  }

  private static EventBus newEventBus(final String name, final Executor executor) {
    try {
      // Load the Dispatcher class using the EventBus's ClassLoader to ensure compatibility
      Class<?> dispatcherClass = EventBus.class.getClassLoader().loadClass("com.google.common.eventbus.Dispatcher");

      // Get the immediate dispatcher method - events are processed in a reentrant fashion
      Method immediateDispatcherMethod = dispatcherClass.getDeclaredMethod("immediate");
      try {
        immediateDispatcherMethod.setAccessible(true);
      } catch (InaccessibleObjectException e) {
        // Handle Java 21 module system restrictions with more detailed error message
        throw new ReflectiveOperationException(STR"Failed to access Guava internal method: \{e.getMessage()}. " + 
            "This may be due to Java 21 module system restrictions.", e);
      }

      // Get the EventBus constructor that accepts a custom executor (not part of the public API)
      Constructor<EventBus> eventBusConstructor = EventBus.class.getDeclaredConstructor(
          String.class, Executor.class, dispatcherClass, SubscriberExceptionHandler.class);
      try {
        eventBusConstructor.setAccessible(true);
      } catch (InaccessibleObjectException e) {
        // Handle Java 21 module system restrictions with more detailed error message
        throw new ReflectiveOperationException(STR"Failed to access EventBus constructor: \{e.getMessage()}. " + 
            "This may be due to Java 21 module system restrictions.", e);
      }

      // Create the immediate dispatcher and exception handler
      Object immediateDispatcher = immediateDispatcherMethod.invoke(null);
      SubscriberExceptionHandler exceptionHandler = new Slf4jSubscriberExceptionHandler(name);

      // Create and return the EventBus instance
      return eventBusConstructor.newInstance(name, executor, immediateDispatcher, exceptionHandler);
    }
    catch (ReflectiveOperationException e) {
      // Use Java's built-in exception handling for reflective operations
      throw new LinkageError(STR"Unable to create EventBus with custom executor: \{e.getMessage()}", e);
    }
    catch (Exception e) {
      // Handle any other exceptions
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new LinkageError(STR"Unexpected error creating EventBus with custom executor: \{e.getMessage()}", e);
    }
  }
}