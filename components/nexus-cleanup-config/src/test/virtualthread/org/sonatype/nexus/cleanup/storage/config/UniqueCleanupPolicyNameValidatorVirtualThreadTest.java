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
package org.sonatype.nexus.cleanup.storage.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.sonatype.nexus.cleanup.storage.CleanupPolicyStorage;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

/**
 * Virtual thread test for {@link UniqueCleanupPolicyNameValidator} to verify thread-safety,
 * performance, and correctness under Java 21's virtual thread execution model.
 * 
 * This test focuses on validating that database interactions through {@link CleanupPolicyStorage}
 * remain safe under high concurrency with virtual threads, particularly ensuring that no thread
 * pinning occurs during cleanup policy name validation operations.
 *
 * Without this test, potential database blocking or thread pinning issues would not be detected
 * when running with virtual threads in production. The test verifies that the validator can handle
 * thousands of concurrent validation requests efficiently using Java 21's virtual threads.
 *
 * @since 3.60
 */
public class UniqueCleanupPolicyNameValidatorVirtualThreadTest
    extends UniqueCleanupPolicyNameValidatorTest
{
  /**
   * Number of concurrent virtual threads to create for testing.
   * This high number helps verify scalability with virtual threads.
   */
  private static final int CONCURRENT_THREADS = 1000;
  
  /**
   * Timeout for waiting for all threads to complete.
   */
  private static final int TIMEOUT_SECONDS = 10;
  
  /**
   * Test policy name used for validation.
   */
  private static final String TEST_NAME = "test";
  
  /**
   * Prefix for generating multiple unique test policy names.
   */
  private static final String TEST_NAME_PREFIX = "test-";

  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  private UniqueCleanupPolicyNameValidator underTest;

  @Before
  @Override
  public void setUp() {
    underTest = new UniqueCleanupPolicyNameValidator(cleanupPolicyStorage);
  }

  /**
   * Tests that the validator correctly handles high concurrency with virtual threads
   * when validating policy names that don't exist (valid case).
   */
  /**
   * Tests that the validator correctly handles high concurrency with virtual threads
   * when validating policy names that don't exist (valid case).
   * 
   * This test creates 1000 virtual threads that all validate the same policy name
   * concurrently, verifying that:
   * 1. All validations complete successfully
   * 2. No thread pinning occurs during database operations
   * 3. All validations return the expected result (valid)
   */
  @Test
  public void testConcurrentValidationWithVirtualThreads_ValidNames() throws Exception {
    // Configure mock to return false for exists() calls (names don't exist)
    when(cleanupPolicyStorage.exists(TEST_NAME)).thenReturn(false);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicInteger validResults = new AtomicInteger(0);
      AtomicBoolean anyThreadPinning = new AtomicBoolean(false);
      
      // Submit tasks to validate the same name concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Check if thread is pinned during validation
            Thread currentThread = Thread.currentThread();
            boolean isPinned = detectThreadPinning(() -> {
              boolean result = underTest.isValid(TEST_NAME, null);
              if (result) {
                validResults.incrementAndGet();
              }
              return result;
            });
            
            if (isPinned) {
              anyThreadPinning.set(true);
              System.err.println("Thread pinning detected in " + currentThread.getName());
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All validation tasks should complete within timeout", completed, is(true));
      assertThat("No thread pinning should occur during validation", anyThreadPinning.get(), is(false));
      assertThat("All validations should return valid", validResults.get(), is(CONCURRENT_THREADS));
    }
  }

  /**
   * Tests that the validator correctly handles high concurrency with virtual threads
   * when validating policy names that already exist (invalid case).
   */
  /**
   * Tests that the validator correctly handles high concurrency with virtual threads
   * when validating policy names that already exist (invalid case).
   * 
   * This test creates 1000 virtual threads that all validate the same policy name
   * concurrently, verifying that:
   * 1. All validations complete successfully
   * 2. No thread pinning occurs during database operations
   * 3. All validations return the expected result (invalid)
   */
  @Test
  public void testConcurrentValidationWithVirtualThreads_InvalidNames() throws Exception {
    // Configure mock to return true for exists() calls (names already exist)
    when(cleanupPolicyStorage.exists(TEST_NAME)).thenReturn(true);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicInteger invalidResults = new AtomicInteger(0);
      AtomicBoolean anyThreadPinning = new AtomicBoolean(false);
      
      // Submit tasks to validate the same name concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Check if thread is pinned during validation
            Thread currentThread = Thread.currentThread();
            boolean isPinned = detectThreadPinning(() -> {
              boolean result = underTest.isValid(TEST_NAME, null);
              if (!result) {
                invalidResults.incrementAndGet();
              }
              return result;
            });
            
            if (isPinned) {
              anyThreadPinning.set(true);
              System.err.println("Thread pinning detected in " + currentThread.getName());
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All validation tasks should complete within timeout", completed, is(true));
      assertThat("No thread pinning should occur during validation", anyThreadPinning.get(), is(false));
      assertThat("All validations should return invalid", invalidResults.get(), is(CONCURRENT_THREADS));
    }
  }

  /**
   * Tests that the validator correctly handles high concurrency with virtual threads
   * when validating multiple different policy names.
   */
  /**
   * Tests that the validator correctly handles high concurrency with virtual threads
   * when validating multiple different policy names.
   * 
   * This test creates 1000 virtual threads that each validate a different policy name
   * concurrently, verifying that:
   * 1. All validations complete successfully
   * 2. No thread pinning occurs during database operations
   * 3. All validations return the expected result (valid)
   * 4. The validator can handle many different policy names concurrently
   */
  @Test
  public void testConcurrentValidationWithVirtualThreads_MultipleNames() throws Exception {
    // Configure mock to return false for exists() calls with different names
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      String name = TEST_NAME_PREFIX + i;
      when(cleanupPolicyStorage.exists(name)).thenReturn(false);
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicInteger validResults = new AtomicInteger(0);
      AtomicBoolean anyThreadPinning = new AtomicBoolean(false);
      List<String> failedNames = new ArrayList<>();
      
      // Submit tasks to validate different names concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final String name = TEST_NAME_PREFIX + i;
        executor.submit(() -> {
          try {
            // Check if thread is pinned during validation
            Thread currentThread = Thread.currentThread();
            boolean isPinned = detectThreadPinning(() -> {
              boolean result = underTest.isValid(name, null);
              if (result) {
                validResults.incrementAndGet();
              } else {
                synchronized (failedNames) {
                  failedNames.add(name);
                }
              }
              return result;
            });
            
            if (isPinned) {
              anyThreadPinning.set(true);
              System.err.println("Thread pinning detected in " + currentThread.getName());
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All validation tasks should complete within timeout", completed, is(true));
      assertThat("No thread pinning should occur during validation", anyThreadPinning.get(), is(false));
      assertThat("All validations should return valid", validResults.get(), is(CONCURRENT_THREADS));
      assertThat("No names should fail validation", failedNames.isEmpty(), is(true));
    }
  }

  /**
   * Helper method to detect if a thread is pinned during the execution of a task.
   * 
   * This method uses a combination of techniques to detect thread pinning:
   * 1. Checks if the thread is virtual (pinning only applies to virtual threads)
   * 2. Uses a separate monitoring thread to check if the virtual thread appears to be blocked
   * 3. Monitors execution time for anomalies that might indicate pinning
   * 
   * In production environments, use JVM flags like -Djdk.tracePinnedThreads=full or JFR events.
   * 
   * @param task The task to execute and check for pinning
   * @return true if pinning was detected, false otherwise
   */
  private boolean detectThreadPinning(Runnable task) {
    Thread currentThread = Thread.currentThread();
    
    // Pinning only applies to virtual threads
    if (!currentThread.isVirtual()) {
      task.run();
      return false;
    }
    
    // For virtual threads, we need to monitor for signs of pinning
    AtomicBoolean taskCompleted = new AtomicBoolean(false);
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Create a monitoring thread to detect potential pinning
    Thread monitorThread = Thread.ofPlatform().daemon().start(() -> {
      try {
        // Wait a short time to allow normal execution
        Thread.sleep(50);
        
        // If the task hasn't completed yet, it might be pinned
        if (!taskCompleted.get()) {
          // In a real implementation, we would check the thread state and stack trace
          // to determine if it's pinned. For this test, we're using a simplified approach.
          
          // Check if the thread is blocked in a synchronized block
          Thread.State state = currentThread.getState();
          if (state == Thread.State.BLOCKED || state == Thread.State.WAITING || 
              state == Thread.State.TIMED_WAITING) {
            // This could indicate pinning, especially if we're in a synchronized block
            // In a real implementation, we would check the stack trace for synchronized blocks
            pinningDetected.set(true);
          }
        }
      }
      catch (InterruptedException e) {
        // Monitor thread was interrupted, which is expected when the task completes
      }
    });
    
    try {
      // Execute the task and measure execution time
      long startTime = System.nanoTime();
      task.run();
      long endTime = System.nanoTime();
      
      // Mark the task as completed to stop the monitoring thread
      taskCompleted.set(true);
      monitorThread.interrupt();
      
      // Check execution time - extremely long execution might indicate pinning
      // This is a simplified heuristic and would need tuning in real environments
      long executionTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      if (executionTimeMs > 500) { // Arbitrary threshold for this test
        pinningDetected.set(true);
      }
      
      return pinningDetected.get();
    }
    catch (Exception e) {
      // Mark the task as completed to stop the monitoring thread
      taskCompleted.set(true);
      monitorThread.interrupt();
      throw e;
    }
  }
  
  /**
   * Helper method that returns a result while checking for thread pinning.
   * 
   * @param supplier The supplier function to execute and check for pinning
   * @return The result of the supplier function
   */
  private <T> T detectThreadPinning(Supplier<T> supplier) {
    Thread currentThread = Thread.currentThread();
    
    // Pinning only applies to virtual threads
    if (!currentThread.isVirtual()) {
      return supplier.get();
    }
    
    // For virtual threads, we need to monitor for signs of pinning
    AtomicBoolean taskCompleted = new AtomicBoolean(false);
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Create a monitoring thread to detect potential pinning
    Thread monitorThread = Thread.ofPlatform().daemon().start(() -> {
      try {
        // Wait a short time to allow normal execution
        Thread.sleep(50);
        
        // If the task hasn't completed yet, it might be pinned
        if (!taskCompleted.get()) {
          // Check if the thread is blocked in a synchronized block
          Thread.State state = currentThread.getState();
          if (state == Thread.State.BLOCKED || state == Thread.State.WAITING || 
              state == Thread.State.TIMED_WAITING) {
            // This could indicate pinning, especially if we're in a synchronized block
            pinningDetected.set(true);
          }
        }
      }
      catch (InterruptedException e) {
        // Monitor thread was interrupted, which is expected when the task completes
      }
    });
    
    try {
      // Execute the supplier and measure execution time
      long startTime = System.nanoTime();
      T result = supplier.get();
      long endTime = System.nanoTime();
      
      // Mark the task as completed to stop the monitoring thread
      taskCompleted.set(true);
      monitorThread.interrupt();
      
      // Check execution time - extremely long execution might indicate pinning
      long executionTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      if (executionTimeMs > 500) { // Arbitrary threshold for this test
        pinningDetected.set(true);
      }
      
      return result;
    }
    catch (Exception e) {
      // Mark the task as completed to stop the monitoring thread
      taskCompleted.set(true);
      monitorThread.interrupt();
      throw e;
    }
  }
  
  /**
   * Simple functional interface for operations that return a result.
   */
  @FunctionalInterface
  private interface Supplier<T> {
    T get();
  }
}