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
package org.sonatype.nexus.testcommon.virtualthread;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

/**
 * Base support class for tests involving Java 21 Virtual Threads.
 * 
 * Provides utilities for creating, managing, and validating Virtual Threads in test environments.
 * This class facilitates testing of Virtual Thread implementations across the Nexus codebase,
 * particularly for I/O-bound operations that can benefit from Virtual Threads.
 * 
 * @since 3.60
 */
public class VirtualThreadTestSupport
    extends TestSupport
{
  /**
   * Checks if the current JVM supports Virtual Threads.
   * This method attempts to create a virtual thread to verify support.
   * 
   * @return true if Virtual Threads are supported, false otherwise
   */
  public boolean isVirtualThreadSupported() {
    try {
      Thread virtualThread = Thread.ofVirtual().start(() -> {});
      virtualThread.join();
      return true;
    }
    catch (Exception e) {
      log("Virtual Threads are not supported in this JVM", e);
      return false;
    }
  }

  /**
   * Creates a ThreadFactory that produces Virtual Threads.
   * This factory can be used with existing executor frameworks and thread pools
   * to enable them to use Virtual Threads instead of platform threads.
   * 
   * @return a ThreadFactory that creates Virtual Threads
   */
  public ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }

  /**
   * Creates a named ThreadFactory that produces Virtual Threads with the specified name prefix.
   * Named threads make it easier to identify specific test operations in logs and thread dumps,
   * which is particularly useful when debugging concurrent test failures.
   * 
   * @param namePrefix the prefix to use for thread names, followed by an incrementing number
   * @return a ThreadFactory that creates named Virtual Threads
   */
  public ThreadFactory createVirtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual().name(namePrefix, 0).factory();
  }

  /**
   * Creates an ExecutorService that creates a new Virtual Thread for each task.
   * This is particularly useful for testing I/O-bound operations that can benefit
   * from Virtual Threads' lightweight nature.
   * 
   * @return an ExecutorService using Virtual Threads
   */
  public ExecutorService createVirtualThreadExecutorService() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Executes a task on a Virtual Thread and waits for its completion.
   * This method is useful for testing code that should run on a Virtual Thread
   * without the overhead of managing thread creation and joining.
   * 
   * @param task the task to execute
   * @throws Exception if the task execution fails or is interrupted
   */
  public void executeOnVirtualThread(Runnable task) throws Exception {
    Thread virtualThread = Thread.ofVirtual().start(task);
    virtualThread.join();
  }

  /**
   * Executes a task on a Virtual Thread with a specific name and waits for its completion.
   * Named threads are useful for debugging and identifying specific test operations
   * in logs and thread dumps.
   * 
   * @param name the name for the Virtual Thread
   * @param task the task to execute
   * @throws Exception if the task execution fails or is interrupted
   */
  public void executeOnVirtualThread(String name, Runnable task) throws Exception {
    Thread virtualThread = Thread.ofVirtual().name(name).start(task);
    virtualThread.join();
  }

  /**
   * Executes a task on a Virtual Thread and returns the result.
   * This method is useful for testing functions that return values and need to be
   * executed on a Virtual Thread.
   * 
   * @param <T> the type of the result
   * @param supplier the supplier that produces the result
   * @return the result of the task
   * @throws Exception if the task execution fails or is interrupted
   */
  public <T> T supplyFromVirtualThread(Supplier<T> supplier) throws Exception {
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      Future<T> future = executor.submit(supplier::get);
      return future.get();
    }
  }

  /**
   * Executes a task on a Virtual Thread with a timeout and returns the result.
   * This method is particularly useful for testing operations that might block or
   * take a long time to complete, ensuring tests don't hang indefinitely.
   * 
   * @param <T> the type of the result
   * @param supplier the supplier that produces the result
   * @param timeout the maximum time to wait for the result
   * @return the result of the task
   * @throws Exception if the task execution fails, is interrupted, or times out
   */
  public <T> T supplyFromVirtualThread(Supplier<T> supplier, Duration timeout) throws Exception {
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      Future<T> future = executor.submit(supplier::get);
      return future.get(timeout.toSeconds(), TimeUnit.SECONDS);
    }
  }

  /**
   * Checks if the current thread is a Virtual Thread.
   * This is useful for validating thread context in tests that need to verify
   * whether code is running on a Virtual Thread or a platform thread.
   * 
   * @return true if the current thread is a Virtual Thread, false otherwise
   */
  public boolean isCurrentThreadVirtual() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Asserts that the current thread is a Virtual Thread.
   * This method is useful in tests that require execution on a Virtual Thread,
   * failing fast if the thread context is incorrect.
   * 
   * @throws AssertionError if the current thread is not a Virtual Thread
   */
  public void assertCurrentThreadIsVirtual() {
    if (!isCurrentThreadVirtual()) {
      throw new AssertionError("Current thread is not a Virtual Thread: " + Thread.currentThread());
    }
  }

  /**
   * Asserts that the current thread is not a Virtual Thread.
   * This method is useful in tests that require execution on a platform thread,
   * failing fast if the thread context is incorrect.
   * 
   * @throws AssertionError if the current thread is a Virtual Thread
   */
  public void assertCurrentThreadIsNotVirtual() {
    if (isCurrentThreadVirtual()) {
      throw new AssertionError("Current thread is a Virtual Thread: " + Thread.currentThread());
    }
  }
}