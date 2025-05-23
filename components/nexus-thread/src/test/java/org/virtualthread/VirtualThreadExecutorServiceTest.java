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
package org.virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;
import org.sonatype.nexus.thread.NexusExecutorService;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ExecutorService} implementation using Java 21 Virtual Threads in Nexus.
 * 
 * Verifies that the Virtual Thread ExecutorService correctly propagates security contexts,
 * MDC logging context, and thread names when submitting tasks to Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
public class VirtualThreadExecutorServiceTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadExecutorServiceTest.class);
  
  private static final String MDC_TEST_KEY = "testKey";
  private static final String MDC_TEST_VALUE = "testValue";
  private static final String THREAD_NAME_PREFIX = "nexus-vt";
  private static final int CONCURRENT_TASKS = 1000;
  private static final int TASK_DURATION_MS = 10;
  
  @Mock
  private Subject subject;
  
  private ExecutorService virtualThreadExecutor;
  private NexusExecutorService nexusVirtualThreadExecutor;
  
  @BeforeEach
  public void setup() {
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create a Nexus executor service that wraps the virtual thread executor
    // and binds tasks to the provided subject
    nexusVirtualThreadExecutor = NexusExecutorService.forFixedSubject(virtualThreadExecutor, subject);
    
    // Set up MDC context for the test
    MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    // Clean up MDC context
    MDC.clear();
    
    // Clean up thread context
    ThreadContext.remove();
    
    // Shutdown executor services
    nexusVirtualThreadExecutor.shutdown();
    virtualThreadExecutor.shutdown();
    
    // Wait for termination
    nexusVirtualThreadExecutor.awaitTermination(1, TimeUnit.SECONDS);
    virtualThreadExecutor.awaitTermination(1, TimeUnit.SECONDS);
  }
  
  /**
   * Test that a Callable executed by the virtual thread executor has the correct Subject bound to it.
   */
  @Test
  public void testCallableSubjectPropagation() throws Exception {
    // Set up a reference to capture the subject from the virtual thread
    AtomicReference<Subject> threadSubject = new AtomicReference<>();
    
    // Create a callable that captures the current subject
    Callable<String> callable = () -> {
      threadSubject.set(ThreadContext.getSubject());
      return "success";
    };
    
    // Execute the callable and wait for the result
    Future<String> future = nexusVirtualThreadExecutor.submit(callable);
    String result = future.get();
    
    // Verify the result and that the subject was correctly propagated
    assertThat(result, is("success"));
    assertThat(threadSubject.get(), is(subject));
  }
  
  /**
   * Test that a Runnable executed by the virtual thread executor has the correct Subject bound to it.
   */
  @Test
  public void testRunnableSubjectPropagation() throws Exception {
    // Set up a reference to capture the subject from the virtual thread
    AtomicReference<Subject> threadSubject = new AtomicReference<>();
    AtomicBoolean executed = new AtomicBoolean(false);
    
    // Create a runnable that captures the current subject
    Runnable runnable = () -> {
      threadSubject.set(ThreadContext.getSubject());
      executed.set(true);
    };
    
    // Execute the runnable and wait for completion
    Future<?> future = nexusVirtualThreadExecutor.submit(runnable);
    future.get();
    
    // Verify that the runnable executed and the subject was correctly propagated
    assertThat(executed.get(), is(true));
    assertThat(threadSubject.get(), is(subject));
  }
  
  /**
   * Test that MDC context is correctly propagated to virtual threads.
   */
  @Test
  public void testMdcPropagation() throws Exception {
    // Set up a reference to capture the MDC context from the virtual thread
    AtomicReference<String> threadMdcValue = new AtomicReference<>();
    
    // Create a callable that captures the MDC value
    Callable<String> callable = () -> {
      threadMdcValue.set(MDC.get(MDC_TEST_KEY));
      return "success";
    };
    
    // Execute the callable and wait for the result
    Future<String> future = nexusVirtualThreadExecutor.submit(callable);
    future.get();
    
    // Verify that the MDC context was correctly propagated
    assertThat(threadMdcValue.get(), is(MDC_TEST_VALUE));
  }
  
  /**
   * Test that the FakeAlmightySubject can be used with virtual threads.
   */
  @Test
  public void testWithFakeAlmightySubject() throws Exception {
    // Create a new executor service with the FakeAlmightySubject
    NexusExecutorService almightyExecutor = 
        NexusExecutorService.forFixedSubject(virtualThreadExecutor, FakeAlmightySubject.TASK_SUBJECT);
    
    try {
      // Set up a reference to capture the subject from the virtual thread
      AtomicReference<Subject> threadSubject = new AtomicReference<>();
      
      // Create a callable that captures the current subject
      Callable<String> callable = () -> {
        threadSubject.set(ThreadContext.getSubject());
        return "success";
      };
      
      // Execute the callable and wait for the result
      Future<String> future = almightyExecutor.submit(callable);
      String result = future.get();
      
      // Verify the result and that the subject was correctly propagated
      assertThat(result, is("success"));
      assertThat(threadSubject.get(), is(FakeAlmightySubject.TASK_SUBJECT));
    }
    finally {
      almightyExecutor.shutdown();
      almightyExecutor.awaitTermination(1, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Test that virtual threads correctly handle exceptions in submitted tasks.
   */
  @Test
  public void testExceptionHandling() {
    // Create a callable that throws an exception
    Callable<String> callable = () -> {
      throw new RuntimeException("Test exception");
    };
    
    // Execute the callable
    Future<String> future = nexusVirtualThreadExecutor.submit(callable);
    
    // Verify that the exception is properly propagated
    ExecutionException exception = assertThrows(ExecutionException.class, () -> future.get());
    assertThat(exception.getCause().getMessage(), is("Test exception"));
  }
  
  /**
   * Test concurrent execution of many lightweight tasks to verify virtual thread scalability.
   */
  @Test
  public void testConcurrentExecution() throws Exception {
    // Create a countdown latch to track task completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    
    // Create a counter to track successful executions
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Submit many concurrent tasks
    List<Future<String>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      final int taskId = i;
      futures.add(nexusVirtualThreadExecutor.submit(() -> {
        try {
          // Simulate some work
          Thread.sleep(TASK_DURATION_MS);
          // Verify this is running on a virtual thread
          assertThat(Thread.currentThread().isVirtual(), is(true));
          // Verify subject propagation
          assertThat(ThreadContext.getSubject(), is(subject));
          // Verify MDC propagation
          assertThat(MDC.get(MDC_TEST_KEY), is(MDC_TEST_VALUE));
          
          successCounter.incrementAndGet();
          return "Task " + taskId + " completed";
        }
        finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all tasks to complete
    boolean allCompleted = latch.await(5, TimeUnit.SECONDS);
    assertThat("All tasks should complete within the timeout", allCompleted, is(true));
    
    // Verify all tasks completed successfully
    assertThat(successCounter.get(), is(CONCURRENT_TASKS));
    
    // Verify all futures completed
    for (Future<String> future : futures) {
      assertThat(future.isDone(), is(true));
      assertThat(future.get(), startsWith("Task "));
    }
  }
  
  /**
   * Test that virtual threads have appropriate thread names.
   */
  @Test
  public void testThreadNaming() throws Exception {
    // Set up a reference to capture the thread name
    AtomicReference<String> threadName = new AtomicReference<>();
    
    // Create a custom executor with a specific thread name prefix
    ExecutorService namedExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name(THREAD_NAME_PREFIX + "-", 0).factory());
    
    NexusExecutorService namedNexusExecutor = 
        NexusExecutorService.forFixedSubject(namedExecutor, subject);
    
    try {
      // Submit a task that captures its thread name
      Future<String> future = namedNexusExecutor.submit(() -> {
        threadName.set(Thread.currentThread().getName());
        return "success";
      });
      
      future.get();
      
      // Verify the thread name has the expected prefix
      assertThat(threadName.get(), startsWith(THREAD_NAME_PREFIX));
    }
    finally {
      namedNexusExecutor.shutdown();
      namedExecutor.shutdown();
      namedNexusExecutor.awaitTermination(1, TimeUnit.SECONDS);
      namedExecutor.awaitTermination(1, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Test that all MDC context entries are correctly propagated to virtual threads.
   */
  @Test
  public void testFullMdcContextPropagation() throws Exception {
    // Set up multiple MDC entries
    MDC.put("key1", "value1");
    MDC.put("key2", "value2");
    MDC.put("key3", "value3");
    
    // Set up a reference to capture the full MDC context
    AtomicReference<Map<String, String>> threadMdcContext = new AtomicReference<>();
    
    // Create a callable that captures the full MDC context
    Callable<String> callable = () -> {
      threadMdcContext.set(MDC.getCopyOfContextMap());
      return "success";
    };
    
    // Execute the callable and wait for the result
    Future<String> future = nexusVirtualThreadExecutor.submit(callable);
    future.get();
    
    // Verify that all MDC entries were correctly propagated
    Map<String, String> mdcContext = threadMdcContext.get();
    assertThat(mdcContext, notNullValue());
    assertThat(mdcContext.get(MDC_TEST_KEY), is(MDC_TEST_VALUE));
    assertThat(mdcContext.get("key1"), is("value1"));
    assertThat(mdcContext.get("key2"), is("value2"));
    assertThat(mdcContext.get("key3"), is("value3"));
    
    // Clean up the additional MDC entries
    MDC.remove("key1");
    MDC.remove("key2");
    MDC.remove("key3");
  }
  
  /**
   * Test performance comparison between platform threads and virtual threads.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    // Number of tasks for performance test
    final int taskCount = 500;
    
    // Create a platform thread executor for comparison
    ExecutorService platformExecutor = Executors.newFixedThreadPool(20);
    NexusExecutorService nexusPlatformExecutor = 
        NexusExecutorService.forFixedSubject(platformExecutor, subject);
    
    try {
      // Measure execution time with platform threads
      long platformStart = System.currentTimeMillis();
      List<CompletableFuture<Void>> platformFutures = new ArrayList<>();
      
      for (int i = 0; i < taskCount; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Simulate I/O-bound work
            Thread.sleep(TASK_DURATION_MS);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }, nexusPlatformExecutor);
        
        platformFutures.add(future);
      }
      
      // Wait for all platform thread tasks to complete
      CompletableFuture.allOf(platformFutures.toArray(new CompletableFuture[0])).get();
      long platformDuration = System.currentTimeMillis() - platformStart;
      
      // Measure execution time with virtual threads
      long virtualStart = System.currentTimeMillis();
      List<CompletableFuture<Void>> virtualFutures = new ArrayList<>();
      
      for (int i = 0; i < taskCount; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Simulate I/O-bound work
            Thread.sleep(TASK_DURATION_MS);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }, nexusVirtualThreadExecutor);
        
        virtualFutures.add(future);
      }
      
      // Wait for all virtual thread tasks to complete
      CompletableFuture.allOf(virtualFutures.toArray(new CompletableFuture[0])).get();
      long virtualDuration = System.currentTimeMillis() - virtualStart;
      
      // Log the performance comparison
      log.info("Platform threads execution time: {} ms", platformDuration);
      log.info("Virtual threads execution time: {} ms", virtualDuration);
      
      // Virtual threads should generally be more efficient for this workload
      // but we don't make a hard assertion since performance can vary by environment
      // Just log the results for analysis
    }
    finally {
      nexusPlatformExecutor.shutdown();
      platformExecutor.shutdown();
      nexusPlatformExecutor.awaitTermination(1, TimeUnit.SECONDS);
      platformExecutor.awaitTermination(1, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Test that the UnhandledExceptionHandler is properly invoked for uncaught exceptions.
   */
  @Test
  public void testUnhandledExceptionHandler() throws Exception {
    // Create a custom exception handler
    AtomicReference<Throwable> caughtException = new AtomicReference<>();
    Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
      caughtException.set(throwable);
    };
    
    // Create a virtual thread factory with the custom exception handler
    Thread.Builder.OfVirtual virtualBuilder = Thread.ofVirtual()
        .name(THREAD_NAME_PREFIX + "-exception-")
        .uncaughtExceptionHandler(handler);
    
    // Create an executor with the custom thread factory
    ExecutorService customExecutor = Executors.newThreadPerTaskExecutor(virtualBuilder.factory());
    
    try {
      // Submit a task that throws an uncaught exception
      customExecutor.submit(() -> {
        throw new RuntimeException("Uncaught exception test");
      });
      
      // Give some time for the exception handler to be invoked
      Thread.sleep(100);
      
      // Verify the exception was caught by our handler
      assertThat(caughtException.get(), notNullValue());
      assertThat(caughtException.get().getMessage(), is("Uncaught exception test"));
    }
    finally {
      customExecutor.shutdown();
      customExecutor.awaitTermination(1, TimeUnit.SECONDS);
    }
  }
}