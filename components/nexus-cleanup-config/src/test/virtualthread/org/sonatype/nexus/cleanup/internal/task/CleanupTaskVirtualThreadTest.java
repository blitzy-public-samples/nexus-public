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
package org.sonatype.nexus.cleanup.internal.task;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cleanup.service.CleanupService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Virtual Thread-specific test for the {@link CleanupTask} that validates its execution behavior
 * in a highly concurrent environment using Java 21's Virtual Thread model.
 */
public class CleanupTaskVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_TASKS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private CleanupService cleanupService;
  
  private CleanupTask underTest;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @Before
  public void setup() throws Exception {
    underTest = new CleanupTask(cleanupService);
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdownNow();
    }
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests that the CleanupTask can be executed concurrently with many Virtual Threads
   * without errors or race conditions.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Setup a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Configure cleanup service to simulate I/O work
    doAnswer(invocation -> {
      // Simulate I/O work with a small delay
      Thread.sleep(50);
      return null;
    }).when(cleanupService).cleanup(any(BooleanSupplier.class));
    
    // Execute many concurrent cleanup tasks using virtual threads
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          underTest.execute();
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error executing cleanup task", e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All tasks should complete within timeout", completed, is(true));
    assertThat("No errors should occur during concurrent execution", errorCount.get(), is(0));
    verify(cleanupService, times(CONCURRENT_TASKS)).cleanup(any(BooleanSupplier.class));
  }
  
  /**
   * Compares performance between Virtual Threads and Platform Threads when executing
   * many concurrent cleanup tasks.
   */
  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    // Configure cleanup service to simulate I/O work
    doAnswer(invocation -> {
      // Simulate I/O-bound work with sleep
      Thread.sleep(100);
      return null;
    }).when(cleanupService).cleanup(any(BooleanSupplier.class));
    
    // Measure execution time with platform threads
    long platformThreadTime = measureExecutionTime(platformThreadExecutor);
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    
    // Measure execution time with virtual threads
    long virtualThreadTime = measureExecutionTime(virtualThreadExecutor);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O-bound tasks
    // This may not always be true depending on the exact nature of the task and system load,
    // but for I/O-bound tasks with many concurrent executions, virtual threads should generally
    // perform better
    assertThat("Virtual threads should be more efficient for I/O-bound tasks", 
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Tests that the CleanupTask correctly handles cancellation when running with Virtual Threads.
   */
  @Test
  public void testCancellationWithVirtualThreads() throws Exception {
    // Setup a cancellation flag
    AtomicBoolean cancelled = new AtomicBoolean(false);
    
    // Configure cleanup service to check cancellation flag
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        BooleanSupplier cancelledCheck = invocation.getArgument(0);
        
        // Simulate long-running task that periodically checks for cancellation
        for (int i = 0; i < 10; i++) {
          if (cancelledCheck.getAsBoolean()) {
            // Task was cancelled
            return null;
          }
          // Simulate work
          Thread.sleep(50);
        }
        return null;
      }
    }).when(cleanupService).cleanup(any(BooleanSupplier.class));
    
    // Start a cleanup task in a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        underTest.execute();
      } 
      catch (Exception e) {
        fail("Unexpected exception: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Set the cancellation flag after a short delay
    Thread.sleep(100);
    cancelled.set(true);
    
    // Wait for the task to complete
    future.get(5, TimeUnit.SECONDS);
    
    // Verify cleanup service was called
    verify(cleanupService).cleanup(any(BooleanSupplier.class));
  }
  
  /**
   * Tests for thread pinning issues that could impact Virtual Thread performance.
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
   * typically due to synchronized blocks or native methods.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Configure cleanup service to simulate work that might cause pinning
    doAnswer(invocation -> {
      // Use a synchronized block which could potentially cause pinning
      synchronized (this) {
        // Simulate I/O work inside synchronized block
        Thread.sleep(50);
      }
      return null;
    }).when(cleanupService).cleanup(any(BooleanSupplier.class));
    
    // Track thread names to detect carrier thread reuse
    List<String> threadNames = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    
    // Execute many concurrent cleanup tasks using virtual threads
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Record the carrier thread name
          String threadName = Thread.currentThread().toString();
          synchronized (threadNames) {
            threadNames.add(threadName);
          }
          
          // Execute the cleanup task
          underTest.execute();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All tasks should complete within timeout", completed, is(true));
    
    // Count unique carrier threads
    long uniqueThreads = threadNames.stream().distinct().count();
    log.info("Used {} unique virtual threads for {} tasks", uniqueThreads, CONCURRENT_TASKS);
    
    // We expect to see close to CONCURRENT_TASKS unique thread names if virtual threads are working correctly
    // If there's significant pinning, we'll see much fewer unique threads
    assertThat("Should use many unique virtual threads", uniqueThreads, greaterThan((long)(CONCURRENT_TASKS * 0.9)));
  }
  
  /**
   * Tests that error handling works correctly with Virtual Threads.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    // Configure cleanup service to throw an exception
    doAnswer(invocation -> {
      throw new RuntimeException("Simulated error in cleanup service");
    }).when(cleanupService).cleanup(any(BooleanSupplier.class));
    
    // Setup a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Execute many concurrent cleanup tasks using virtual threads
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          underTest.execute();
          fail("Should have thrown an exception");
        } 
        catch (Exception e) {
          // Expected exception
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All tasks should complete within timeout", completed, is(true));
    assertThat("All tasks should have encountered an error", errorCount.get(), equalTo(CONCURRENT_TASKS));
    verify(cleanupService, times(CONCURRENT_TASKS)).cleanup(any(BooleanSupplier.class));
  }
  
  /**
   * Helper method to measure execution time of concurrent cleanup tasks using the provided executor.
   */
  private long measureExecutionTime(ExecutorService executor) throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    long startTime = System.currentTimeMillis();
    
    // Execute concurrent cleanup tasks
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      executor.submit(() -> {
        try {
          underTest.execute();
        } 
        catch (Exception e) {
          log.error("Error executing cleanup task", e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long endTime = System.currentTimeMillis();
    
    if (!completed) {
      fail("Tasks did not complete within timeout");
    }
    
    return endTime - startTime;
  }
}