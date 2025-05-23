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
package org.sonatype.nexus.logging;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.mockito.Mock;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Tests to verify MDC (Mapped Diagnostic Context) context propagation with Java 21 Virtual Threads.
 * 
 * These tests validate that thread-local diagnostic data is correctly maintained when using the
 * new lightweight threading model introduced in Java 21. Virtual Threads are a key feature of Java 21
 * that enable high-throughput concurrent applications by providing a more efficient threading model.
 * 
 * The tests in this class ensure that:
 * - MDC context is correctly propagated from platform threads to virtual threads
 * - MDC context integrity is maintained across multiple virtual thread operations
 * - MDC context is properly isolated between different threads
 * - MDC context is maintained during I/O operations that might cause thread unmounting/remounting
 * - MDC context is properly cleaned up after virtual thread completion
 * - High concurrency scenarios with many virtual threads maintain correct MDC context
 *
 * These tests are critical for ensuring that logging context behaves correctly in concurrent scenarios
 * when using Java 21's Virtual Threads in Nexus Repository.
 */
public class VirtualThreadMDCTest
    extends TestSupport
{
  @Mock
  private Logger mockLogger;

  /**
   * Tests basic MDC context propagation to a single virtual thread.
   * 
   * This test verifies that MDC context set in the platform thread is correctly
   * propagated to a virtual thread when it executes.
   */
  @Test
  public void testBasicMdcPropagationToVirtualThread() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    String mainThread = Thread.currentThread().getName();
    AtomicBoolean tested = new AtomicBoolean(false);
    AtomicReference<String> virtualThreadMdcValue = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    // Set MDC in the current thread
    MDC.put("testKey", "testValue");
    
    try {
      // Create and start a virtual thread
      Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
        try {
          // Verify we're in a different thread
          assertThat(Thread.currentThread().getName(), is("test-virtual-thread"));
          assertThat(Thread.currentThread().isVirtual(), is(true));
          
          // Capture the MDC value in the virtual thread
          virtualThreadMdcValue.set(MDC.get("testKey"));
          tested.set(true);
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for the virtual thread to complete
      assertTrue("Virtual thread did not complete in time", latch.await(1, TimeUnit.SECONDS));
      virtualThread.join(1000);
      
      // Verify the MDC value was correctly propagated
      assertThat("MDC context was not propagated to virtual thread", 
          virtualThreadMdcValue.get(), equalTo("testValue"));
      assertThat("Test did not run in virtual thread", tested.get(), is(true));
    } finally {
      MDC.remove("testKey");
    }
  }

  /**
   * Tests MDC context propagation across multiple nested virtual threads.
   * 
   * This test verifies that MDC context is correctly propagated through a chain of
   * virtual threads, where each thread spawns another.
   */
  @Test
  public void testNestedVirtualThreadsMdcPropagation() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    final int NESTING_DEPTH = 5;
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> deepestMdcValue = new AtomicReference<>();
    
    // Set MDC in the current thread
    MDC.put("nestedKey", "nestedValue");
    
    try {
      // Create a chain of nested virtual threads
      createNestedVirtualThread(0, NESTING_DEPTH, deepestMdcValue, latch);
      
      // Wait for the deepest thread to complete
      assertTrue("Nested virtual threads did not complete in time", latch.await(2, TimeUnit.SECONDS));
      
      // Verify the MDC value was correctly propagated to the deepest thread
      assertThat("MDC context was not propagated through nested virtual threads", 
          deepestMdcValue.get(), equalTo("nestedValue"));
    } finally {
      MDC.remove("nestedKey");
    }
  }

  /**
   * Tests MDC context propagation with virtual threads from a thread pool.
   * 
   * This test verifies that MDC context is correctly propagated when using
   * virtual threads created by a thread pool executor.
   */
  @Test
  public void testVirtualThreadPoolMdcPropagation() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    final int THREAD_COUNT = 10;
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicBoolean allThreadsHadCorrectMdc = new AtomicBoolean(true);
    
    // Set MDC in the current thread
    MDC.put("poolKey", "poolValue");
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple tasks to the virtual thread executor
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Verify the MDC value in each virtual thread
            String mdcValue = MDC.get("poolKey");
            if (!"poolValue".equals(mdcValue)) {
              mockLogger.error("Task {} had incorrect MDC value: {}", taskId, mdcValue);
              allThreadsHadCorrectMdc.set(false);
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue("Not all virtual threads completed in time", latch.await(2, TimeUnit.SECONDS));
      
      // Verify all threads had the correct MDC value
      assertThat("MDC context was not correctly propagated to all virtual threads", 
          allThreadsHadCorrectMdc.get(), is(true));
    } finally {
      MDC.remove("poolKey");
    }
  }

  /**
   * Tests MDC context cleanup after virtual thread completion.
   * 
   * This test verifies that MDC context is properly cleared after a virtual thread completes,
   * preventing context leakage between tasks.
   */
  @Test
  public void testMdcContextCleanupAfterVirtualThreadCompletion() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // First task: set MDC value and verify it exists
      Future<?> future1 = executor.submit(() -> {
        MDC.put("cleanupKey", "cleanupValue");
        assertThat(MDC.get("cleanupKey"), equalTo("cleanupValue"));
      });
      future1.get(1, TimeUnit.SECONDS);
      
      // Second task: verify the MDC value from the first task is not present
      Future<?> future2 = executor.submit(() -> {
        assertThat("MDC context leaked between virtual threads", 
            MDC.get("cleanupKey"), is(nullValue()));
      });
      future2.get(1, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests MDC context integrity during I/O operations that might cause virtual thread unmounting.
   * 
   * This test verifies that MDC context is maintained even when a virtual thread is unmounted
   * and remounted on a different carrier thread due to blocking I/O operations.
   * 
   * In Java 21, virtual threads can be unmounted from their carrier thread during blocking operations
   * like I/O or sleep, and later remounted on the same or different carrier thread. This test ensures
   * that MDC context is preserved across these unmounting/remounting events.
   */
  @Test
  public void testMdcContextIntegrityDuringIoOperations() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(1);
    AtomicReference<String> mdcBeforeSleep = new AtomicReference<>();
    AtomicReference<String> mdcAfterSleep = new AtomicReference<>();
    AtomicReference<String> mdcAfterIo = new AtomicReference<>();
    
    // Create and start a virtual thread that performs a blocking operation
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Set MDC in the virtual thread
        MDC.put("ioKey", "ioValue");
        
        // Capture MDC before sleep
        mdcBeforeSleep.set(MDC.get("ioKey"));
        startLatch.countDown();
        
        // Perform blocking I/O operation (simulated with sleep)
        // This should cause the virtual thread to be unmounted and later remounted
        Thread.sleep(500);
        
        // Capture MDC after sleep
        mdcAfterSleep.set(MDC.get("ioKey"));
        
        // Perform another blocking operation - file I/O
        // This is more likely to cause unmounting/remounting than sleep
        try {
          // Simple file I/O operation
          java.nio.file.Files.createTempFile("vthread-test", ".tmp").toFile().deleteOnExit();
          
          // Capture MDC after I/O
          mdcAfterIo.set(MDC.get("ioKey"));
        } catch (Exception e) {
          mockLogger.error("Error during file I/O operation", e);
        }
      } catch (InterruptedException e) {
        mockLogger.error("Virtual thread was interrupted", e);
      } finally {
        completionLatch.countDown();
      }
    });
    
    // Wait for the thread to start and set initial MDC
    assertTrue("Virtual thread did not start in time", startLatch.await(1, TimeUnit.SECONDS));
    
    // Wait for the thread to complete after I/O operations
    assertTrue("Virtual thread did not complete in time", completionLatch.await(2, TimeUnit.SECONDS));
    virtualThread.join(1000);
    
    // Verify MDC context was maintained across the I/O operations
    assertThat("MDC context before I/O operation was incorrect", 
        mdcBeforeSleep.get(), equalTo("ioValue"));
    assertThat("MDC context was not maintained across sleep operation", 
        mdcAfterSleep.get(), equalTo("ioValue"));
    assertThat("MDC context was not maintained across file I/O operation", 
        mdcAfterIo.get(), equalTo("ioValue"));
  }

  /**
   * Tests MDC context propagation with high concurrency using many virtual threads.
   * 
   * This test verifies that MDC context is correctly propagated when using a large number
   * of concurrent virtual threads, simulating high load scenarios. Virtual threads are designed
   * to support high concurrency with minimal overhead, making them ideal for I/O-bound operations
   * in Nexus Repository.
   * 
   * This test creates thousands of virtual threads, each with its own unique MDC context,
   * to verify that context isolation and propagation work correctly at scale.
   */
  @Test
  public void testHighConcurrencyMdcPropagation() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    // Use a high thread count to test scalability
    // Virtual threads are designed to support thousands or even millions of threads
    final int THREAD_COUNT = 1000;
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicBoolean allThreadsHadCorrectMdc = new AtomicBoolean(true);
    
    // Set unique MDC values for each thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit many tasks to the virtual thread executor
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        final String expectedValue = "value-" + threadId;
        
        // Set thread-specific MDC in the platform thread before submission
        MDC.put("concurrentKey", expectedValue);
        
        executor.submit(() -> {
          try {
            // Verify the MDC value in the virtual thread matches what was set
            String actualValue = MDC.get("concurrentKey");
            if (!expectedValue.equals(actualValue)) {
              mockLogger.error("Thread {} expected MDC value '{}' but got '{}'", 
                  threadId, expectedValue, actualValue);
              allThreadsHadCorrectMdc.set(false);
            }
            
            // Add a small delay to increase the likelihood of thread scheduling interactions
            if (threadId % 10 == 0) {
              Thread.sleep(1);
            }
          } catch (Exception e) {
            mockLogger.error("Error in virtual thread {}", threadId, e);
            allThreadsHadCorrectMdc.set(false);
          } finally {
            latch.countDown();
          }
        });
        
        // Clear MDC after submission to ensure each task gets its own value
        MDC.remove("concurrentKey");
      }
      
      // Wait for all threads to complete
      assertTrue("Not all virtual threads completed in time", latch.await(5, TimeUnit.SECONDS));
      
      // Verify all threads had their correct MDC values
      assertThat("MDC context was not correctly propagated in high concurrency scenario", 
          allThreadsHadCorrectMdc.get(), is(true));
    }
  }

  /**
   * Tests interaction between platform threads and virtual threads for MDC propagation.
   * 
   * This test verifies that MDC context is correctly propagated when switching between
   * platform threads and virtual threads.
   */
  @Test
  public void testPlatformAndVirtualThreadInteraction() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    CountDownLatch platformLatch = new CountDownLatch(1);
    CountDownLatch virtualLatch = new CountDownLatch(1);
    AtomicReference<String> platformThreadMdcValue = new AtomicReference<>();
    AtomicReference<String> virtualThreadMdcValue = new AtomicReference<>();
    
    // Set MDC in the current thread
    MDC.put("interactionKey", "interactionValue");
    
    try {
      // Create and start a platform thread
      Thread platformThread = new Thread(() -> {
        try {
          // Verify MDC in platform thread
          platformThreadMdcValue.set(MDC.get("interactionKey"));
          
          // Create and start a virtual thread from this platform thread
          Thread virtualThread = Thread.ofVirtual().start(() -> {
            try {
              // Verify MDC in virtual thread
              virtualThreadMdcValue.set(MDC.get("interactionKey"));
            } finally {
              virtualLatch.countDown();
            }
          });
          
          // Wait for virtual thread to complete
          virtualThread.join(1000);
        } catch (InterruptedException e) {
          mockLogger.error("Thread was interrupted", e);
        } finally {
          platformLatch.countDown();
        }
      });
      
      platformThread.start();
      
      // Wait for both threads to complete
      assertTrue("Platform thread did not complete in time", platformLatch.await(2, TimeUnit.SECONDS));
      assertTrue("Virtual thread did not complete in time", virtualLatch.await(2, TimeUnit.SECONDS));
      platformThread.join(1000);
      
      // Verify MDC was correctly propagated to both thread types
      assertThat("MDC context was not propagated to platform thread", 
          platformThreadMdcValue.get(), equalTo("interactionValue"));
      assertThat("MDC context was not propagated from platform thread to virtual thread", 
          virtualThreadMdcValue.get(), equalTo("interactionValue"));
    } finally {
      MDC.remove("interactionKey");
    }
  }

  /**
   * Tests that MDC context changes in a virtual thread don't affect the parent thread.
   * 
   * This test verifies that changes to the MDC context within a virtual thread
   * remain isolated and don't affect the parent thread's context.
   */
  @Test
  public void testMdcContextIsolationBetweenThreads() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> parentAfterChildModification = new AtomicReference<>();
    
    // Set MDC in the parent thread
    MDC.put("parentKey", "parentValue");
    
    try {
      // Create and start a virtual thread that modifies its MDC
      Thread virtualThread = Thread.ofVirtual().start(() -> {
        try {
          // Verify initial MDC value inherited from parent
          assertThat(MDC.get("parentKey"), equalTo("parentValue"));
          
          // Modify MDC in the virtual thread
          MDC.put("parentKey", "childModifiedValue");
          MDC.put("childKey", "childValue");
          
          // Verify our changes took effect in the virtual thread
          assertThat(MDC.get("parentKey"), equalTo("childModifiedValue"));
          assertThat(MDC.get("childKey"), equalTo("childValue"));
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for the virtual thread to complete
      assertTrue("Virtual thread did not complete in time", latch.await(1, TimeUnit.SECONDS));
      virtualThread.join(1000);
      
      // Capture the parent thread's MDC value after child modification
      parentAfterChildModification.set(MDC.get("parentKey"));
      
      // Verify parent's MDC was not affected by child's modifications
      assertThat("Child thread's MDC modifications affected parent thread", 
          parentAfterChildModification.get(), equalTo("parentValue"));
      assertThat("Child thread's new MDC key leaked to parent thread", 
          MDC.get("childKey"), is(nullValue()));
    } finally {
      MDC.remove("parentKey");
    }
  }

  /**
   * Tests MDC context propagation with structured concurrency using StructuredTaskScope.
   * 
   * This test verifies that MDC context is correctly propagated when using Java 21's
   * structured concurrency features, which provide a more structured approach to managing
   * concurrent tasks with clear parent-child relationships and lifecycle management.
   */
  @Test
  public void testStructuredConcurrencyMdcPropagation() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    // Skip if StructuredTaskScope is not available (it's a preview feature in Java 21)
    try {
      Class.forName("java.util.concurrent.StructuredTaskScope");
    } catch (ClassNotFoundException e) {
      mockLogger.info("Skipping test as StructuredTaskScope is not available");
      return;
    }
    
    // Set MDC in the current thread
    MDC.put("structuredKey", "structuredValue");
    
    try {
      // Use reflection to work with StructuredTaskScope (preview API)
      // This allows the code to compile and run on both Java 17 and Java 21
      Class<?> scopeClass = Class.forName("java.util.concurrent.StructuredTaskScope");
      Object scope = scopeClass.getConstructor().newInstance();
      
      // Create atomic references to store results from tasks
      AtomicReference<String> task1MdcValue = new AtomicReference<>();
      AtomicReference<String> task2MdcValue = new AtomicReference<>();
      
      // Fork two tasks using reflection
      java.lang.reflect.Method forkMethod = scopeClass.getMethod("fork", java.util.function.Supplier.class);
      
      // Task 1: Simple MDC check
      forkMethod.invoke(scope, (java.util.function.Supplier<Void>) () -> {
        task1MdcValue.set(MDC.get("structuredKey"));
        return null;
      });
      
      // Task 2: MDC check with sleep (to trigger potential unmounting)
      forkMethod.invoke(scope, (java.util.function.Supplier<Void>) () -> {
        try {
          Thread.sleep(100);
          task2MdcValue.set(MDC.get("structuredKey"));
        } catch (InterruptedException e) {
          mockLogger.error("Task interrupted", e);
        }
        return null;
      });
      
      // Join all tasks
      java.lang.reflect.Method joinMethod = scopeClass.getMethod("join");
      joinMethod.invoke(scope);
      
      // Close the scope
      java.lang.reflect.Method closeMethod = scopeClass.getMethod("close");
      closeMethod.invoke(scope);
      
      // Verify MDC was correctly propagated to both tasks
      assertThat("MDC context was not propagated to first structured task", 
          task1MdcValue.get(), equalTo("structuredValue"));
      assertThat("MDC context was not propagated to second structured task", 
          task2MdcValue.get(), equalTo("structuredValue"));
    } catch (Exception e) {
      mockLogger.error("Error testing structured concurrency", e);
      throw e;
    } finally {
      MDC.remove("structuredKey");
    }
  }

  /**
   * Helper method to create a chain of nested virtual threads.
   * 
   * @param currentDepth The current nesting depth
   * @param maxDepth The maximum nesting depth
   * @param deepestMdcValue Reference to store the MDC value from the deepest thread
   * @param latch Latch to signal completion of the deepest thread
   */
  private void createNestedVirtualThread(
      int currentDepth, 
      int maxDepth, 
      AtomicReference<String> deepestMdcValue, 
      CountDownLatch latch) throws Exception 
  {
    if (currentDepth >= maxDepth) {
      // We've reached the maximum depth, capture the MDC value
      deepestMdcValue.set(MDC.get("nestedKey"));
      latch.countDown();
      return;
    }
    
    // Create a virtual thread at this nesting level
    Thread virtualThread = Thread.ofVirtual().name("nested-thread-" + currentDepth).start(() -> {
      try {
        // Verify MDC is propagated to this level
        assertThat(MDC.get("nestedKey"), equalTo("nestedValue"));
        
        // Create the next level of nesting
        try {
          createNestedVirtualThread(currentDepth + 1, maxDepth, deepestMdcValue, latch);
        } catch (Exception e) {
          mockLogger.error("Error creating nested virtual thread at depth {}", currentDepth + 1, e);
        }
      } catch (Exception e) {
        mockLogger.error("Error in virtual thread at depth {}", currentDepth, e);
      }
    });
    
    // Wait for this thread to complete before returning
    virtualThread.join(1000);
  }

  /**
   * Tests MDC context propagation with a custom ThreadFactory that creates virtual threads.
   * 
   * This test verifies that MDC context is correctly propagated when using a custom ThreadFactory
   * that creates virtual threads, which is a common pattern in enterprise applications.
   */
  @Test
  public void testCustomVirtualThreadFactoryMdcPropagation() throws Exception {
    // Skip test if not running on Java 21+
    if (!isVirtualThreadSupported()) {
      mockLogger.info("Skipping test as Virtual Threads are not supported in this Java version");
      return;
    }
    
    final int THREAD_COUNT = 10;
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicBoolean allThreadsHadCorrectMdc = new AtomicBoolean(true);
    
    // Create a custom ThreadFactory that creates virtual threads
    ThreadFactory virtualThreadFactory = new ThreadFactory() {
      private final AtomicInteger threadCount = new AtomicInteger(0);
      
      @Override
      public Thread newThread(Runnable r) {
        return Thread.ofVirtual()
            .name("custom-vthread-" + threadCount.incrementAndGet())
            .unstarted(r);
      }
    };
    
    // Create an ExecutorService using our custom ThreadFactory
    try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT, virtualThreadFactory)) {
      // Set MDC in the current thread
      MDC.put("factoryKey", "factoryValue");
      
      // Submit tasks to the executor
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            // Verify the MDC value in the virtual thread
            String mdcValue = MDC.get("factoryKey");
            if (!"factoryValue".equals(mdcValue)) {
              mockLogger.error("Thread had incorrect MDC value: {}", mdcValue);
              allThreadsHadCorrectMdc.set(false);
            }
            
            // Verify this is actually a virtual thread
            if (!Thread.currentThread().isVirtual()) {
              mockLogger.error("Thread is not a virtual thread");
              allThreadsHadCorrectMdc.set(false);
            }
          } catch (Exception e) {
            mockLogger.error("Error in virtual thread", e);
            allThreadsHadCorrectMdc.set(false);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue("Not all virtual threads completed in time", latch.await(2, TimeUnit.SECONDS));
      
      // Verify all threads had the correct MDC value
      assertThat("MDC context was not correctly propagated with custom thread factory", 
          allThreadsHadCorrectMdc.get(), is(true));
      
      // Clean up MDC
      MDC.remove("factoryKey");
    }
  }

  /**
   * Checks if virtual threads are supported in the current Java runtime.
   * 
   * @return true if virtual threads are supported, false otherwise
   */
  private boolean isVirtualThreadSupported() {
    try {
      // Check if Thread.ofVirtual() method exists (Java 21+)
      Thread.class.getMethod("ofVirtual");
      // Also verify we can actually create a virtual thread
      Thread vThread = Thread.ofVirtual().unstarted(() -> {});
      return true;
    } catch (NoSuchMethodException | UnsupportedOperationException e) {
      return false;
    }
  }
}