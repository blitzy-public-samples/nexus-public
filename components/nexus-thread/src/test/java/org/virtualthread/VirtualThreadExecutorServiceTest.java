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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.thread.NexusExecutorService;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link NexusExecutorService} with Virtual Threads.
 * 
 * This test class verifies that the NexusExecutorService correctly propagates security contexts,
 * MDC logging context, and thread names when submitting tasks to Java 21 Virtual Threads.
 *
 * @since 3.60
 */
public class VirtualThreadExecutorServiceTest
    extends TestSupport
{
  private static final String TEST_MDC_KEY = "test-mdc-key";
  private static final String TEST_MDC_VALUE = "test-mdc-value";
  private static final String TEST_SUBJECT_ID = "test-subject-id";

  @Mock
  private Subject subject;

  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  public void setUp() {
    // Set up the mock Subject
    when(subject.getPrincipal()).thenReturn(TEST_SUBJECT_ID);
    
    // Set up MDC context
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    
    // Create the executor service with virtual threads
    virtualThreadExecutor = NexusExecutorService.forFixedSubjectVirtual(subject);
  }

  @AfterEach
  public void tearDown() {
    // Clean up MDC context
    MDC.clear();
    
    // Clean up Shiro ThreadContext
    ThreadContext.unbindSubject();
    ThreadContext.remove();
    
    // Shutdown the executor service
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        virtualThreadExecutor.shutdownNow();
      }
    }
  }

  /**
   * Tests that a Callable task executes successfully on a virtual thread and returns the expected result.
   */
  @Test
  public void testCallableExecution() throws Exception {
    // Create a Callable that returns a string
    Callable<String> callable = () -> "success";
    
    // Submit the Callable to the executor service
    Future<String> future = virtualThreadExecutor.submit(callable);
    
    // Verify the result
    assertEquals("success", future.get(), "Callable should return the expected result");
  }

  /**
   * Tests that a Runnable task executes successfully on a virtual thread.
   */
  @Test
  public void testRunnableExecution() throws Exception {
    // Create an AtomicBoolean to track execution
    AtomicBoolean executed = new AtomicBoolean(false);
    
    // Create a Runnable that sets the AtomicBoolean to true
    Runnable runnable = () -> executed.set(true);
    
    // Submit the Runnable to the executor service
    Future<?> future = virtualThreadExecutor.submit(runnable);
    
    // Wait for completion
    future.get();
    
    // Verify execution
    assertTrue(executed.get(), "Runnable should have executed");
  }

  /**
   * Tests that the Subject is properly propagated to the virtual thread.
   */
  @Test
  public void testSubjectPropagation() throws Exception {
    // Create an AtomicReference to capture the Subject in the virtual thread
    AtomicReference<Object> threadSubjectPrincipal = new AtomicReference<>();
    
    // Create a Callable that captures the Subject principal
    Callable<Boolean> callable = () -> {
      Subject threadSubject = ThreadContext.getSubject();
      if (threadSubject != null) {
        threadSubjectPrincipal.set(threadSubject.getPrincipal());
        return true;
      }
      return false;
    };
    
    // Submit the Callable to the executor service
    Future<Boolean> future = virtualThreadExecutor.submit(callable);
    
    // Verify that the Subject was available in the virtual thread
    assertTrue(future.get(), "Subject should be available in the virtual thread");
    
    // Verify that the Subject principal matches the expected value
    assertEquals(TEST_SUBJECT_ID, threadSubjectPrincipal.get(), 
        "Subject principal in virtual thread should match the original Subject");
    
    // Verify that the Subject's getPrincipal method was called
    verify(subject).getPrincipal();
  }

  /**
   * Tests that MDC context is properly propagated to the virtual thread.
   */
  @Test
  public void testMdcPropagation() throws Exception {
    // Create an AtomicReference to capture the MDC value in the virtual thread
    AtomicReference<String> threadMdcValue = new AtomicReference<>();
    
    // Create a Callable that captures the MDC value
    Callable<Boolean> callable = () -> {
      String mdcValue = MDC.get(TEST_MDC_KEY);
      threadMdcValue.set(mdcValue);
      return mdcValue != null;
    };
    
    // Submit the Callable to the executor service
    Future<Boolean> future = virtualThreadExecutor.submit(callable);
    
    // Verify that the MDC value was available in the virtual thread
    assertTrue(future.get(), "MDC value should be available in the virtual thread");
    
    // Verify that the MDC value matches the expected value
    assertEquals(TEST_MDC_VALUE, threadMdcValue.get(), 
        "MDC value in virtual thread should match the original MDC value");
  }

  /**
   * Tests that tasks are actually running on virtual threads.
   */
  @Test
  public void testVirtualThreadExecution() throws Exception {
    // Create an AtomicBoolean to track if the thread is virtual
    AtomicBoolean isVirtual = new AtomicBoolean(false);
    
    // Create a Callable that checks if the current thread is virtual
    Callable<Boolean> callable = () -> {
      boolean virtual = Thread.currentThread().isVirtual();
      isVirtual.set(virtual);
      return virtual;
    };
    
    // Submit the Callable to the executor service
    Future<Boolean> future = virtualThreadExecutor.submit(callable);
    
    // Verify that the thread is virtual
    assertTrue(future.get(), "Task should execute on a virtual thread");
    assertTrue(isVirtual.get(), "Thread should be virtual");
  }

  /**
   * Tests that thread names follow the expected pattern for virtual threads.
   */
  @Test
  public void testVirtualThreadNaming() throws Exception {
    // Create an AtomicReference to capture the thread name
    AtomicReference<String> threadName = new AtomicReference<>();
    
    // Create a Callable that captures the thread name
    Callable<String> callable = () -> {
      String name = Thread.currentThread().getName();
      threadName.set(name);
      return name;
    };
    
    // Submit the Callable to the executor service
    Future<String> future = virtualThreadExecutor.submit(callable);
    
    // Get the thread name
    String name = future.get();
    
    // Verify that the thread name is not null
    assertNotNull(name, "Thread name should not be null");
    assertThat(threadName.get(), is(notNullValue()));
    
    // Virtual thread names typically contain "VirtualThread" or follow a specific pattern
    // This is implementation-dependent, so we just check that it's not null or empty
    assertTrue(!name.isEmpty(), "Thread name should not be empty");
  }

  /**
   * Tests concurrent execution of multiple tasks on virtual threads.
   */
  @Test
  public void testConcurrentExecution() throws Exception {
    // Number of tasks to execute concurrently
    int taskCount = 1000;
    
    // Create a list to hold the futures
    List<Future<Integer>> futures = new ArrayList<>(taskCount);
    
    // Submit multiple tasks
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        // Simulate some work
        Thread.sleep(10);
        return taskId;
      }));
    }
    
    // Wait for all tasks to complete and verify results
    for (int i = 0; i < taskCount; i++) {
      assertEquals(i, futures.get(i).get(), "Task result should match task ID");
    }
  }

  /**
   * Tests that exceptions in tasks are properly propagated.
   */
  @Test
  public void testExceptionPropagation() {
    // Create a Callable that throws an exception
    Callable<Object> callable = () -> {
      throw new RuntimeException("Test exception");
    };
    
    // Submit the Callable to the executor service
    Future<Object> future = virtualThreadExecutor.submit(callable);
    
    // Verify that the exception is propagated
    try {
      future.get();
      // If we get here, the test has failed
      throw new AssertionError("Expected ExecutionException was not thrown");
    }
    catch (ExecutionException e) {
      // Expected exception
      assertTrue(e.getCause() instanceof RuntimeException, "Cause should be RuntimeException");
      assertEquals("Test exception", e.getCause().getMessage(), "Exception message should match");
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Unexpected InterruptedException", e);
    }
  }

  /**
   * Tests that MDC context is properly cleaned up after task execution.
   */
  @Test
  public void testMdcCleanup() throws Exception {
    // Create a custom MDC key that should only exist during task execution
    final String customMdcKey = "custom-mdc-key";
    final String customMdcValue = "custom-mdc-value";
    
    // Create a Callable that sets a custom MDC value
    Callable<String> callable = () -> {
      MDC.put(customMdcKey, customMdcValue);
      return MDC.get(customMdcKey);
    };
    
    // Submit the Callable to the executor service
    Future<String> future = virtualThreadExecutor.submit(callable);
    
    // Verify that the custom MDC value was set during execution
    assertEquals(customMdcValue, future.get(), "Custom MDC value should be set during task execution");
    
    // Submit another task to check if the custom MDC value was cleaned up
    Future<String> cleanupCheck = virtualThreadExecutor.submit(() -> MDC.get(customMdcKey));
    
    // Verify that the custom MDC value is not present in the new task
    assertEquals(null, cleanupCheck.get(), "Custom MDC value should not be present in subsequent tasks");
  }
}