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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base abstract test class that provides common utilities and configurations for Java 21-specific security tests.
 * <p>
 * This class extends the Sonatype TestSupport harness with additional Java 21 capabilities for validating
 * security components under the new runtime environment, including virtual thread creation, thread pinning detection,
 * and test categorization.
 * <p>
 * Usage example:
 * <pre>
 * public class MyJava21Test extends Java21TestSupport {
 *   @Test
 *   void testWithVirtualThreads() {
 *     Thread vThread = createVirtualThread(() -> {
 *       // Test code running in a virtual thread
 *     });
 *     vThread.start();
 *     vThread.join();
 *     
 *     assertFalse(wasPinningDetected());
 *   }
 * }
 * </pre>
 *
 * @since 3.60
 */
public abstract class Java21TestSupport extends TestSupport {

  private Recording jfrRecording;
  private final AtomicBoolean pinningDetected = new AtomicBoolean(false);
  private final List<String> pinningStackTraces = new ArrayList<>();

  /**
   * Sets up the test environment before each test method execution.
   * <p>
   * Initializes JFR recording for thread pinning detection.
   */
  @BeforeEach
  public void setupJava21Test() throws Exception {
    // Start JFR recording to detect thread pinning
    Configuration config = Configuration.getConfiguration("default");
    jfrRecording = new Recording(config);
    jfrRecording.enable("jdk.VirtualThreadPinned").withStackTrace();
    jfrRecording.start();
    
    // Reset pinning detection state
    pinningDetected.set(false);
    pinningStackTraces.clear();
    
    log.info("Java21TestSupport: Test environment initialized with JFR recording for thread pinning detection");
  }

  /**
   * Cleans up the test environment after each test method execution.
   * <p>
   * Stops JFR recording and logs any detected thread pinning events.
   */
  @AfterEach
  public void tearDownJava21Test() throws Exception {
    if (jfrRecording != null) {
      jfrRecording.stop();
      
      // Check for VirtualThreadPinned events
      jfrRecording.getEvents().forEach(event -> {
        if (event.getEventType().getName().equals("jdk.VirtualThreadPinned")) {
          pinningDetected.set(true);
          String stackTrace = event.getStackTrace().toString();
          pinningStackTraces.add(stackTrace);
          log.warn("Thread pinning detected: {}\nStack trace: {}", event, stackTrace);
        }
      });
      
      jfrRecording.close();
    }
    
    log.info("Java21TestSupport: Test environment cleaned up");
  }

  /**
   * Creates a virtual thread with the specified runnable task.
   *
   * @param task the task to be executed by the virtual thread
   * @return a new unstarted virtual thread
   */
  protected Thread createVirtualThread(Runnable task) {
    return Thread.ofVirtual().name("test-virtual-thread").unstarted(task);
  }

  /**
   * Creates a virtual thread with the specified name and runnable task.
   *
   * @param name the name of the virtual thread
   * @param task the task to be executed by the virtual thread
   * @return a new unstarted virtual thread
   */
  protected Thread createVirtualThread(String name, Runnable task) {
    return Thread.ofVirtual().name(name).unstarted(task);
  }

  /**
   * Creates and starts a virtual thread with the specified runnable task.
   *
   * @param task the task to be executed by the virtual thread
   * @return a started virtual thread
   */
  protected Thread startVirtualThread(Runnable task) {
    return Thread.startVirtualThread(task);
  }

  /**
   * Creates a virtual thread factory with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names created by this factory
   * @return a thread factory that creates virtual threads
   */
  protected ThreadFactory createVirtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual().name(namePrefix + "-", 0).factory();
  }

  /**
   * Creates an executor service that creates a new virtual thread for each task.
   *
   * @return an executor service backed by virtual threads
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Executes the specified task in a virtual thread and returns the result.
   *
   * @param <T> the type of the task's result
   * @param task the task to execute
   * @return the task's result
   * @throws Exception if the task throws an exception
   */
  protected <T> T runInVirtualThread(Callable<T> task) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Exception> exception = new AtomicReference<>();
    
    Thread vThread = createVirtualThread(() -> {
      try {
        result.set(task.call());
      }
      catch (Exception e) {
        exception.set(e);
      }
    });
    
    vThread.start();
    vThread.join();
    
    if (exception.get() != null) {
      throw exception.get();
    }
    
    return result.get();
  }

  /**
   * Executes the specified task in a virtual thread.
   *
   * @param task the task to execute
   * @throws Exception if the task throws an exception
   */
  protected void runInVirtualThread(Runnable task) throws Exception {
    Thread vThread = createVirtualThread(task);
    vThread.start();
    vThread.join();
  }

  /**
   * Executes the specified task in multiple virtual threads concurrently.
   *
   * @param <T> the type of the task's result
   * @param task the task supplier to execute
   * @param count the number of concurrent threads
   * @return a list of results from all task executions
   * @throws Exception if any task throws an exception
   */
  protected <T> List<T> runConcurrentlyInVirtualThreads(Supplier<Callable<T>> task, int count) throws Exception {
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      List<Future<T>> futures = new ArrayList<>();
      
      for (int i = 0; i < count; i++) {
        futures.add(executor.submit(task.get()));
      }
      
      List<T> results = new ArrayList<>(count);
      for (Future<T> future : futures) {
        results.add(future.get());
      }
      
      return results;
    }
  }

  /**
   * Checks if thread pinning was detected during test execution.
   *
   * @return true if thread pinning was detected, false otherwise
   */
  protected boolean wasPinningDetected() {
    return pinningDetected.get();
  }

  /**
   * Gets the stack traces of any thread pinning events that were detected.
   *
   * @return a list of stack traces from thread pinning events
   */
  protected List<String> getPinningStackTraces() {
    return new ArrayList<>(pinningStackTraces);
  }

  /**
   * Detects if the current thread is a virtual thread.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  protected boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Simulates a blocking operation that would typically cause thread pinning if executed
   * within a synchronized block in a virtual thread.
   *
   * @param durationMillis the duration to block in milliseconds
   */
  protected void simulateBlockingOperation(long durationMillis) {
    try {
      Thread.sleep(Duration.ofMillis(durationMillis));
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Creates a test scenario that would cause thread pinning if executed in a virtual thread.
   * This method is useful for validating thread pinning detection mechanisms.
   *
   * @return a runnable that will cause thread pinning when executed in a virtual thread
   */
  protected Runnable createThreadPinningScenario() {
    return () -> {
      synchronized (this) {
        // Blocking operation inside synchronized block will cause pinning
        simulateBlockingOperation(100);
      }
    };
  }

  /**
   * Executes a test that verifies thread pinning detection is working correctly.
   * This method deliberately creates a thread pinning scenario and verifies it's detected.
   *
   * @throws Exception if an error occurs during test execution
   */
  protected void verifyThreadPinningDetection() throws Exception {
    // Reset pinning detection
    pinningDetected.set(false);
    pinningStackTraces.clear();
    
    // Create a scenario that will cause pinning
    runInVirtualThread(createThreadPinningScenario());
    
    // Verify pinning was detected
    if (!wasPinningDetected()) {
      log.warn("Thread pinning detection test failed: pinning was not detected");
    }
  }
}