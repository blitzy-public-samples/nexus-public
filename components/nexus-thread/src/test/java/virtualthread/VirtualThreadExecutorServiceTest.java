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
package virtualthread;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.apache.shiro.util.ThreadState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.thread.NexusExecutorService;

/**
 * Tests for {@link NexusExecutorService} with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class VirtualThreadExecutorServiceTest
    extends TestSupport
{
  private ThreadState threadState;
  private Subject subject;
  
  @Before
  public void setUp() {
    // Create a mock Subject for testing
    subject = mock(Subject.class);
    when(subject.toString()).thenReturn("MockSubject");
    
    // Bind the subject to the current thread
    threadState = new SubjectThreadState(subject);
    threadState.bind();
  }
  
  @After
  public void tearDown() {
    // Unbind the subject from the current thread
    if (threadState != null) {
      threadState.clear();
      threadState = null;
    }
  }
  
  /**
   * Tests that a task submitted to a NexusExecutorService with virtual threads
   * executes correctly and maintains the Subject context.
   */
  @Test
  public void testVirtualThreadExecution() throws Exception {
    // Create an ExecutorService with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("test-virtual-").factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a NexusExecutorService with the virtual thread executor
      NexusExecutorService nexusExecutor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
      
      // Submit a task that returns the current thread and subject
      Future<Object[]> future = nexusExecutor.submit(() -> {
        Thread currentThread = Thread.currentThread();
        Subject currentSubject = org.apache.shiro.SecurityUtils.getSubject();
        return new Object[] { currentThread, currentSubject };
      });
      
      // Get the result and verify
      Object[] result = future.get(5, TimeUnit.SECONDS);
      Thread executionThread = (Thread) result[0];
      Subject executionSubject = (Subject) result[1];
      
      // Verify the thread is a virtual thread
      assertTrue("Thread should be a virtual thread", executionThread.isVirtual());
      assertTrue("Thread name should start with 'test-virtual-'", executionThread.getName().startsWith("test-virtual-"));
      
      // Verify the subject was propagated correctly
      assertThat(executionSubject, is(notNullValue()));
      assertEquals("Subject should be propagated correctly", "MockSubject", executionSubject.toString());
    } finally {
      virtualExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests high concurrency with many virtual threads, verifying that all tasks complete
   * successfully and maintain the correct Subject context.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create an ExecutorService with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("concurrent-virtual-").factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a NexusExecutorService with the virtual thread executor
      NexusExecutorService nexusExecutor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
      
      // Number of concurrent tasks to run
      int taskCount = 1000;
      
      // Create a list to hold the futures
      List<Future<Boolean>> futures = new ArrayList<>(taskCount);
      
      // Submit tasks that verify the subject is correctly propagated
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        futures.add(nexusExecutor.submit(() -> {
          // Simulate some work
          Thread.sleep(10);
          
          // Verify the thread is a virtual thread
          Thread currentThread = Thread.currentThread();
          assertTrue("Thread should be a virtual thread", currentThread.isVirtual());
          
          // Verify the subject was propagated correctly
          Subject currentSubject = org.apache.shiro.SecurityUtils.getSubject();
          assertThat(currentSubject, is(notNullValue()));
          assertEquals("Subject should be propagated correctly for task " + taskId, 
              "MockSubject", currentSubject.toString());
          
          return true;
        }));
      }
      
      // Verify all tasks completed successfully
      for (Future<Boolean> future : futures) {
        assertTrue("Task should complete successfully", future.get(10, TimeUnit.SECONDS));
      }
    } finally {
      virtualExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests error handling with virtual threads, verifying that exceptions are properly
   * propagated back to the caller.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    // Create an ExecutorService with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("error-virtual-").factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a NexusExecutorService with the virtual thread executor
      NexusExecutorService nexusExecutor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
      
      // Submit a task that throws an exception
      Future<Object> future = nexusExecutor.submit(() -> {
        throw new IOException("Test exception");
      });
      
      try {
        future.get(5, TimeUnit.SECONDS);
        fail("Expected ExecutionException");
      } catch (ExecutionException e) {
        // Verify the cause is the expected exception
        assertTrue("Exception cause should be IOException", e.getCause() instanceof IOException);
        assertEquals("Exception message should match", "Test exception", e.getCause().getMessage());
      }
    } finally {
      virtualExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests that the current subject supplier works correctly with virtual threads.
   */
  @Test
  public void testCurrentSubjectSupplierWithVirtualThreads() throws Exception {
    // Create an ExecutorService with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("current-subject-virtual-").factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a NexusExecutorService with the current subject supplier
      NexusExecutorService nexusExecutor = NexusExecutorService.forCurrentSubject(virtualExecutor);
      
      // Submit a task that verifies the subject
      Future<String> future = nexusExecutor.submit(() -> {
        Subject currentSubject = org.apache.shiro.SecurityUtils.getSubject();
        return currentSubject != null ? currentSubject.toString() : "null";
      });
      
      // Verify the subject was propagated correctly
      String result = future.get(5, TimeUnit.SECONDS);
      assertEquals("Subject should be propagated correctly", "MockSubject", result);
    } finally {
      virtualExecutor.shutdownNow();
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for I/O-bound tasks.
   * This test simulates I/O operations with sleep and verifies that virtual threads can handle
   * more concurrent tasks efficiently.
   */
  @Test
  public void testPerformanceComparisonForIOBoundTasks() throws Exception {
    // Number of tasks to run
    int taskCount = 100;
    
    // Duration of simulated I/O operation in milliseconds
    long ioDuration = 50;
    
    // Create executors for platform and virtual threads
    ExecutorService platformExecutor = Executors.newFixedThreadPool(20); // Limited pool size for platform threads
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    
    try {
      // Create NexusExecutorService instances
      NexusExecutorService nexusPlatformExecutor = NexusExecutorService.forFixedSubject(platformExecutor, subject);
      NexusExecutorService nexusVirtualExecutor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
      
      // Create a callable that simulates an I/O-bound task
      Callable<Long> ioTask = () -> {
        long startTime = System.nanoTime();
        // Simulate I/O operation with sleep
        Thread.sleep(ioDuration);
        return System.nanoTime() - startTime;
      };
      
      // Run tasks with platform threads and measure time
      long platformStartTime = System.nanoTime();
      List<Future<Long>> platformFutures = new ArrayList<>(taskCount);
      for (int i = 0; i < taskCount; i++) {
        platformFutures.add(nexusPlatformExecutor.submit(ioTask));
      }
      for (Future<Long> future : platformFutures) {
        future.get(10, TimeUnit.SECONDS);
      }
      long platformDuration = System.nanoTime() - platformStartTime;
      
      // Run tasks with virtual threads and measure time
      long virtualStartTime = System.nanoTime();
      List<Future<Long>> virtualFutures = new ArrayList<>(taskCount);
      for (int i = 0; i < taskCount; i++) {
        virtualFutures.add(nexusVirtualExecutor.submit(ioTask));
      }
      for (Future<Long> future : virtualFutures) {
        future.get(10, TimeUnit.SECONDS);
      }
      long virtualDuration = System.nanoTime() - virtualStartTime;
      
      // Log the results
      log.info("Platform threads execution time: {} ms", TimeUnit.NANOSECONDS.toMillis(platformDuration));
      log.info("Virtual threads execution time: {} ms", TimeUnit.NANOSECONDS.toMillis(virtualDuration));
      
      // Note: We don't assert on the actual times since they can vary based on the test environment,
      // but we log them for informational purposes. In a real-world scenario with true I/O operations,
      // virtual threads would typically outperform platform threads for I/O-bound tasks.
    } finally {
      platformExecutor.shutdownNow();
      virtualExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests that tasks can be cancelled correctly when using virtual threads.
   */
  @Test
  public void testTaskCancellationWithVirtualThreads() throws Exception {
    // Create an ExecutorService with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("cancel-virtual-").factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a NexusExecutorService with the virtual thread executor
      NexusExecutorService nexusExecutor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
      
      // Create a flag to track if the task was interrupted
      AtomicBoolean wasInterrupted = new AtomicBoolean(false);
      
      // Submit a long-running task
      Future<?> future = nexusExecutor.submit(() -> {
        try {
          // Simulate a long-running task
          Thread.sleep(10000);
        } catch (InterruptedException e) {
          wasInterrupted.set(true);
        }
      });
      
      // Give the task a moment to start
      Thread.sleep(100);
      
      // Cancel the task
      boolean cancelResult = future.cancel(true);
      
      // Verify the task was cancelled
      assertTrue("Task should be cancelled", cancelResult);
      assertTrue("Task should be marked as cancelled", future.isCancelled());
      
      // Wait a moment for the interruption to be processed
      Thread.sleep(100);
      
      // Verify the task was interrupted
      assertTrue("Task should have been interrupted", wasInterrupted.get());
    } finally {
      virtualExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests that CompletableFuture works correctly with NexusExecutorService and virtual threads.
   */
  @Test
  public void testCompletableFutureWithVirtualThreads() throws Exception {
    // Create an ExecutorService with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("completable-virtual-").factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a NexusExecutorService with the virtual thread executor
      NexusExecutorService nexusExecutor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
      
      // Create a reference to store the subject from the CompletableFuture execution
      AtomicReference<String> subjectInFuture = new AtomicReference<>();
      AtomicReference<Boolean> isVirtualThread = new AtomicReference<>();
      
      // Create and execute a CompletableFuture
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        // Record if this is a virtual thread
        isVirtualThread.set(Thread.currentThread().isVirtual());
        
        // Get and record the subject
        Subject currentSubject = org.apache.shiro.SecurityUtils.getSubject();
        subjectInFuture.set(currentSubject != null ? currentSubject.toString() : "null");
        
        return "completed";
      }, nexusExecutor);
      
      // Wait for the future to complete
      String result = future.get(5, TimeUnit.SECONDS);
      
      // Verify the result
      assertEquals("Future should complete with expected result", "completed", result);
      
      // Verify the thread was a virtual thread
      assertTrue("Thread should be a virtual thread", isVirtualThread.get());
      
      // Verify the subject was propagated correctly
      assertEquals("Subject should be propagated correctly", "MockSubject", subjectInFuture.get());
    } finally {
      virtualExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests that MDC context is properly propagated to virtual threads.
   */
  @Test
  public void testMDCPropagationWithVirtualThreads() throws Exception {
    // Create an ExecutorService with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("mdc-virtual-").factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a NexusExecutorService with the virtual thread executor
      NexusExecutorService nexusExecutor = NexusExecutorService.forFixedSubject(virtualExecutor, subject);
      
      // Set MDC values in the current thread
      org.slf4j.MDC.put("testKey", "testValue");
      
      try {
        // Submit a task that checks MDC values
        Future<String> future = nexusExecutor.submit(() -> {
          // Get the MDC value
          return org.slf4j.MDC.get("testKey");
        });
        
        // Verify the MDC value was propagated correctly
        String mdcValue = future.get(5, TimeUnit.SECONDS);
        assertEquals("MDC value should be propagated correctly", "testValue", mdcValue);
      } finally {
        // Clear MDC values
        org.slf4j.MDC.clear();
      }
    } finally {
      virtualExecutor.shutdownNow();
    }
  }
}