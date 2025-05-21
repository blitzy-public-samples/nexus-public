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
package org.sonatype.java21;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base abstract test class that provides common utilities and configurations for Java 21-specific security tests.
 * <p>
 * This class extends {@link AbstractSecurityTest} and adds support for Java 21 features, particularly Virtual Threads,
 * to facilitate testing security components under the new runtime environment.
 * <p>
 * Key features include:
 * <ul>
 *   <li>Virtual Thread creation and management</li>
 *   <li>Thread pinning detection</li>
 *   <li>Test categorization for Java 21 features</li>
 * </ul>
 *
 * @since 3.60
 */
@Tag("java21")
public abstract class Java21TestSupport
    extends AbstractSecurityTest
{
  /**
   * Thread pinning detector for identifying when Virtual Threads get pinned to platform threads.
   */
  protected ThreadPinningDetector pinningDetector;

  /**
   * Current test information.
   */
  protected TestInfo testInfo;

  /**
   * Setup method that runs before each test.
   * Initializes the thread pinning detector and enables JVM thread pinning detection.
   */
  @BeforeEach
  public void setupJava21Test(TestInfo testInfo) {
    this.testInfo = testInfo;
    this.pinningDetector = new ThreadPinningDetector();
    
    // Enable JVM thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
  }

  /**
   * Cleanup method that runs after each test.
   * Resets thread pinning detection settings.
   */
  @AfterEach
  public void cleanupJava21Test() {
    System.clearProperty("jdk.tracePinnedThreads");
  }

  /**
   * Creates a new Virtual Thread.
   *
   * @param name the name of the thread
   * @param runnable the code to be executed by the thread
   * @return the created Virtual Thread
   */
  protected Thread createVirtualThread(String name, Runnable runnable) {
    return Thread.ofVirtual().name(name).start(runnable);
  }

  /**
   * Creates a Virtual Thread factory with the specified name pattern.
   *
   * @param namePattern the pattern for naming threads created by this factory
   * @return a ThreadFactory that creates Virtual Threads
   */
  protected ThreadFactory createVirtualThreadFactory(String namePattern) {
    return Thread.ofVirtual().name(namePattern).factory();
  }

  /**
   * Creates an ExecutorService that creates a new Virtual Thread for each task.
   *
   * @return an ExecutorService using Virtual Threads
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Executes a task on a Virtual Thread and waits for its completion.
   *
   * @param task the task to execute
   * @param timeout the maximum time to wait for the task to complete
   * @param <T> the type of the result
   * @return the result of the task
   * @throws Exception if the task execution fails or times out
   */
  protected <T> T runWithVirtualThread(Supplier<T> task, Duration timeout) throws Exception {
    CompletableFuture<T> future = CompletableFuture.supplyAsync(task, createVirtualThreadExecutor());
    return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Executes a task on a Virtual Thread and waits for its completion.
   *
   * @param task the task to execute
   * @param timeout the maximum time to wait for the task to complete
   * @throws Exception if the task execution fails or times out
   */
  protected void runWithVirtualThread(Runnable task, Duration timeout) throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(task, createVirtualThreadExecutor());
    future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Checks if the current JVM supports Virtual Threads.
   *
   * @return true if Virtual Threads are supported, false otherwise
   */
  protected boolean isVirtualThreadSupported() {
    try {
      Thread.ofVirtual().start(() -> {}).join();
      return true;
    }
    catch (UnsupportedOperationException e) {
      return false;
    }
  }

  /**
   * Checks if the current thread is a Virtual Thread.
   *
   * @return true if the current thread is a Virtual Thread, false otherwise
   */
  protected boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Detects if a Virtual Thread is pinned to a platform thread during the execution of a task.
   *
   * @param task the task to execute and check for thread pinning
   * @return true if thread pinning was detected, false otherwise
   */
  protected boolean detectThreadPinning(Runnable task) {
    return pinningDetector.detectPinning(task);
  }

  /**
   * Creates a task that will intentionally cause thread pinning for testing purposes.
   * This is useful for validating that thread pinning detection is working correctly.
   *
   * @return a Runnable that will cause thread pinning when executed on a Virtual Thread
   */
  protected Runnable createPinningTask() {
    return () -> {
      synchronized (this) {
        try {
          // This will cause pinning because synchronized blocks pin Virtual Threads
          Thread.sleep(100);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
  }
}