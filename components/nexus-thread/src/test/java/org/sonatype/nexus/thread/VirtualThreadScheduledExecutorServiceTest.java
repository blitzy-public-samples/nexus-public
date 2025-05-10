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
package org.sonatype.nexus.thread;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link VirtualThreadScheduledExecutorService}.
 * 
 * This test class verifies that the VirtualThreadScheduledExecutorService correctly schedules and executes
 * tasks using virtual threads, with proper timing, cancellation behavior, and security context propagation.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("Java21")
@org.junit.jupiter.api.Tag("VirtualThread")
@org.junit.experimental.categories.Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class VirtualThreadScheduledExecutorServiceTest
    extends TestSupport
{
  private static final int SHORT_DELAY_MS = 50;
  private static final int MEDIUM_DELAY_MS = 200;
  private static final int LONG_DELAY_MS = 500;
  
  @Mock
  private ScheduledExecutorService delegate;
  
  @Mock
  private Subject subject;
  
  @Mock
  private Supplier<Subject> subjectSupplier;
  
  private VirtualThreadScheduledExecutorService underTest;
  
  @BeforeEach
  void setUp() {
    when(subjectSupplier.get()).thenReturn(subject);
    when(subject.associateWith(any(Runnable.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(subject.associateWith(any(Callable.class))).thenAnswer(invocation -> invocation.getArgument(0));
    
    underTest = new VirtualThreadScheduledExecutorService(delegate, subjectSupplier);
  }
  
  @AfterEach
  void tearDown() {
    if (underTest != null) {
      underTest.shutdown();
    }
  }
  
  /**
   * Tests that a one-time scheduled Runnable task is executed correctly after the specified delay.
   */
  @Test
  void scheduleRunnableExecutesAfterDelay() throws Exception {
    // Setup a mock ScheduledFuture that completes after the delay
    ScheduledFuture<?> mockFuture = createCompletingScheduledFuture();
    
    // Setup the delegate to execute the task when scheduled
    AtomicBoolean taskExecuted = new AtomicBoolean(false);
    doAnswer(invocation -> {
      Runnable task = (Runnable) invocation.getArgument(0);
      // Execute the task in a separate thread to simulate scheduler behavior
      new Thread(() -> {
        try {
          Thread.sleep(SHORT_DELAY_MS);
          task.run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }).start();
      return mockFuture;
    }).when(delegate).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    
    // Schedule a task
    ScheduledFuture<?> future = underTest.schedule(() -> taskExecuted.set(true), SHORT_DELAY_MS, MILLISECONDS);
    
    // Verify the task was scheduled with the delegate
    verify(delegate).schedule(any(Runnable.class), equalTo((long) SHORT_DELAY_MS), equalTo(MILLISECONDS));
    
    // Wait for the task to complete
    await().atMost(1, SECONDS).until(taskExecuted::get);
    
    // Verify the task was executed
    assertTrue(taskExecuted.get(), "Task should have been executed");
    assertFalse(future.isCancelled(), "Task should not be cancelled");
    assertTrue(future.isDone(), "Task should be done");
  }
  
  /**
   * Tests that a one-time scheduled Callable task is executed correctly after the specified delay.
   */
  @Test
  void scheduleCallableExecutesAfterDelay() throws Exception {
    // Setup a mock ScheduledFuture that completes after the delay
    ScheduledFuture<String> mockFuture = createCompletingScheduledFuture("result");
    
    // Setup the delegate to execute the task when scheduled
    AtomicReference<String> result = new AtomicReference<>();
    doAnswer(invocation -> {
      Callable<String> task = (Callable<String>) invocation.getArgument(0);
      // Execute the task in a separate thread to simulate scheduler behavior
      new Thread(() -> {
        try {
          Thread.sleep(SHORT_DELAY_MS);
          result.set(task.call());
        }
        catch (Exception e) {
          // Ignore for test
        }
      }).start();
      return mockFuture;
    }).when(delegate).schedule(any(Callable.class), anyLong(), any(TimeUnit.class));
    
    // Schedule a task
    ScheduledFuture<String> future = underTest.schedule(() -> "result", SHORT_DELAY_MS, MILLISECONDS);
    
    // Verify the task was scheduled with the delegate
    verify(delegate).schedule(any(Callable.class), equalTo((long) SHORT_DELAY_MS), equalTo(MILLISECONDS));
    
    // Wait for the task to complete
    await().atMost(1, SECONDS).until(() -> result.get() != null);
    
    // Verify the task was executed and returned the expected result
    assertThat(result.get(), is("result"));
    assertFalse(future.isCancelled(), "Task should not be cancelled");
    assertTrue(future.isDone(), "Task should be done");
  }
  
  /**
   * Tests that tasks scheduled at a fixed rate execute repeatedly at the specified rate.
   */
  @Test
  void scheduleAtFixedRateExecutesRepeatedlyAtRate() throws Exception {
    // Setup a mock ScheduledFuture
    ScheduledFuture<?> mockFuture = createNonCompletingScheduledFuture();
    
    // Setup the delegate to execute the task when scheduled
    AtomicInteger executionCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(3); // Wait for 3 executions
    
    doAnswer(invocation -> {
      Runnable task = (Runnable) invocation.getArgument(0);
      long initialDelay = invocation.getArgument(1);
      long period = invocation.getArgument(2);
      
      // Execute the task periodically in a separate thread to simulate scheduler behavior
      new Thread(() -> {
        try {
          Thread.sleep(initialDelay);
          while (!Thread.currentThread().isInterrupted() && executionCount.get() < 3) {
            task.run();
            executionCount.incrementAndGet();
            latch.countDown();
            Thread.sleep(period);
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }).start();
      
      return mockFuture;
    }).when(delegate).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    
    // Schedule a task at a fixed rate
    ScheduledFuture<?> future = underTest.scheduleAtFixedRate(
        () -> log.info("Executing fixed rate task"), 
        SHORT_DELAY_MS, 
        SHORT_DELAY_MS, 
        MILLISECONDS);
    
    // Verify the task was scheduled with the delegate
    verify(delegate).scheduleAtFixedRate(
        any(Runnable.class), 
        equalTo((long) SHORT_DELAY_MS), 
        equalTo((long) SHORT_DELAY_MS), 
        equalTo(MILLISECONDS));
    
    // Wait for multiple executions
    boolean completed = latch.await(1, SECONDS);
    
    // Verify the task was executed multiple times
    assertTrue(completed, "Task should have executed multiple times");
    assertThat(executionCount.get(), is(3));
    assertFalse(future.isCancelled(), "Task should not be cancelled");
    assertFalse(future.isDone(), "Task should not be done as it's periodic");
  }
  
  /**
   * Tests that tasks scheduled with fixed delay execute repeatedly with the specified delay between executions.
   */
  @Test
  void scheduleWithFixedDelayExecutesRepeatedlyWithDelay() throws Exception {
    // Setup a mock ScheduledFuture
    ScheduledFuture<?> mockFuture = createNonCompletingScheduledFuture();
    
    // Setup the delegate to execute the task when scheduled
    AtomicInteger executionCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(3); // Wait for 3 executions
    
    doAnswer(invocation -> {
      Runnable task = (Runnable) invocation.getArgument(0);
      long initialDelay = invocation.getArgument(1);
      long delay = invocation.getArgument(2);
      
      // Execute the task periodically in a separate thread to simulate scheduler behavior
      new Thread(() -> {
        try {
          Thread.sleep(initialDelay);
          while (!Thread.currentThread().isInterrupted() && executionCount.get() < 3) {
            task.run();
            executionCount.incrementAndGet();
            latch.countDown();
            Thread.sleep(delay);
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }).start();
      
      return mockFuture;
    }).when(delegate).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    
    // Schedule a task with fixed delay
    ScheduledFuture<?> future = underTest.scheduleWithFixedDelay(
        () -> log.info("Executing fixed delay task"), 
        SHORT_DELAY_MS, 
        SHORT_DELAY_MS, 
        MILLISECONDS);
    
    // Verify the task was scheduled with the delegate
    verify(delegate).scheduleWithFixedDelay(
        any(Runnable.class), 
        equalTo((long) SHORT_DELAY_MS), 
        equalTo((long) SHORT_DELAY_MS), 
        equalTo(MILLISECONDS));
    
    // Wait for multiple executions
    boolean completed = latch.await(1, SECONDS);
    
    // Verify the task was executed multiple times
    assertTrue(completed, "Task should have executed multiple times");
    assertThat(executionCount.get(), is(3));
    assertFalse(future.isCancelled(), "Task should not be cancelled");
    assertFalse(future.isDone(), "Task should not be done as it's periodic");
  }
  
  /**
   * Tests that scheduled tasks can be cancelled before they execute.
   */
  @Test
  void scheduledTaskCanBeCancelled() throws Exception {
    // Setup a mock ScheduledFuture that can be cancelled
    AtomicBoolean cancelled = new AtomicBoolean(false);
    ScheduledFuture<?> mockFuture = createCancellableScheduledFuture(cancelled);
    
    // Setup the delegate to return the mock future
    when(delegate.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenReturn(mockFuture);
    
    // Schedule a task with a long delay
    ScheduledFuture<?> future = underTest.schedule(() -> {}, LONG_DELAY_MS, MILLISECONDS);
    
    // Cancel the task before it executes
    boolean cancelResult = future.cancel(true);
    
    // Verify cancellation was successful
    assertTrue(cancelResult, "Cancellation should return true");
    assertTrue(cancelled.get(), "Task should be marked as cancelled");
    assertTrue(future.isCancelled(), "Future should report as cancelled");
  }
  
  /**
   * Tests that the executor service can be shut down and all tasks are terminated.
   */
  @Test
  void executorServiceCanBeShutDown() {
    // Shutdown the executor service
    underTest.shutdown();
    
    // Verify the delegate was shut down
    verify(delegate).shutdown();
  }
  
  /**
   * Tests that the executor service can be shut down immediately and returns the list of pending tasks.
   */
  @Test
  void executorServiceCanBeShutDownNow() {
    // Setup the delegate to return a list of pending tasks
    List<Runnable> pendingTasks = List.of(() -> {}, () -> {});
    when(delegate.shutdownNow()).thenReturn(pendingTasks);
    
    // Shutdown the executor service immediately
    List<Runnable> result = underTest.shutdownNow();
    
    // Verify the delegate was shut down and the correct list was returned
    verify(delegate).shutdownNow();
    assertThat(result, is(pendingTasks));
  }
  
  /**
   * Tests that the executor service correctly reports its shutdown and termination status.
   */
  @Test
  void executorServiceReportsCorrectShutdownAndTerminationStatus() {
    // Setup the delegate to report shutdown and termination status
    when(delegate.isShutdown()).thenReturn(true);
    when(delegate.isTerminated()).thenReturn(true);
    
    // Verify the executor service reports the correct status
    assertTrue(underTest.isShutdown(), "Executor should report as shutdown");
    assertTrue(underTest.isTerminated(), "Executor should report as terminated");
  }
  
  /**
   * Tests that the executor service correctly awaits termination.
   */
  @Test
  void executorServiceAwaitsTermination() throws Exception {
    // Setup the delegate to report successful termination
    when(delegate.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    
    // Await termination
    boolean terminated = underTest.awaitTermination(LONG_DELAY_MS, MILLISECONDS);
    
    // Verify the delegate was called and the correct result was returned
    verify(delegate).awaitTermination(LONG_DELAY_MS, MILLISECONDS);
    assertTrue(terminated, "Executor should report successful termination");
  }
  
  /**
   * Tests that the security subject is correctly associated with the executed task.
   */
  @Test
  void securitySubjectIsAssociatedWithTask() throws Exception {
    // Setup a mock ScheduledFuture that completes after the delay
    ScheduledFuture<?> mockFuture = createCompletingScheduledFuture();
    
    // Setup the delegate to execute the task when scheduled
    AtomicBoolean taskExecuted = new AtomicBoolean(false);
    doAnswer(invocation -> {
      Runnable task = (Runnable) invocation.getArgument(0);
      // Execute the task in a separate thread to simulate scheduler behavior
      new Thread(() -> {
        try {
          Thread.sleep(SHORT_DELAY_MS);
          task.run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }).start();
      return mockFuture;
    }).when(delegate).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    
    // Schedule a task
    underTest.schedule(() -> taskExecuted.set(true), SHORT_DELAY_MS, MILLISECONDS);
    
    // Wait for the task to complete
    await().atMost(1, SECONDS).until(taskExecuted::get);
    
    // Verify the subject was retrieved and associated with the task
    verify(subjectSupplier).get();
    verify(subject).associateWith(any(Runnable.class));
  }
  
  /**
   * Tests that the factory method for current subject creates a properly configured executor service.
   */
  @Test
  void factoryMethodForCurrentSubjectCreatesProperExecutor() {
    // Create an executor service using the factory method
    VirtualThreadScheduledExecutorService executor = 
        VirtualThreadScheduledExecutorService.forCurrentSubject(delegate);
    
    // Verify the executor was created with the correct parameters
    assertThat(executor, is(notNullValue()));
  }
  
  /**
   * Tests that the factory method for fixed subject creates a properly configured executor service.
   */
  @Test
  void factoryMethodForFixedSubjectCreatesProperExecutor() {
    // Create an executor service using the factory method
    VirtualThreadScheduledExecutorService executor = 
        VirtualThreadScheduledExecutorService.forFixedSubject(delegate, subject);
    
    // Verify the executor was created with the correct parameters
    assertThat(executor, is(notNullValue()));
  }
  
  /**
   * Tests that the factory method with scheduled pool creates a properly configured executor service.
   */
  @Test
  void factoryMethodWithScheduledPoolCreatesProperExecutor() {
    // Create an executor service using the factory method
    VirtualThreadScheduledExecutorService executor = 
        VirtualThreadScheduledExecutorService.withScheduledPool(2);
    
    // Verify the executor was created
    assertThat(executor, is(notNullValue()));
    
    // Clean up
    executor.shutdown();
  }
  
  /**
   * Tests that the factory method with single thread scheduler creates a properly configured executor service.
   */
  @Test
  void factoryMethodWithSingleThreadSchedulerCreatesProperExecutor() {
    // Create an executor service using the factory method
    VirtualThreadScheduledExecutorService executor = 
        VirtualThreadScheduledExecutorService.withSingleThreadScheduler();
    
    // Verify the executor was created
    assertThat(executor, is(notNullValue()));
    
    // Clean up
    executor.shutdown();
  }
  
  /**
   * Tests that submit(Runnable) correctly wraps and delegates the task.
   */
  @Test
  void submitRunnableWrapsAndDelegatesTask() {
    // Setup the delegate to return a mock future
    when(delegate.submit(any(Runnable.class))).thenReturn(null);
    
    // Submit a task
    underTest.submit(() -> {});
    
    // Verify the task was wrapped and delegated
    verify(delegate).submit(any(Runnable.class));
    verify(subject).associateWith(any(Runnable.class));
  }
  
  /**
   * Tests that submit(Runnable, T) correctly wraps and delegates the task.
   */
  @Test
  void submitRunnableWithResultWrapsAndDelegatesTask() {
    // Setup the delegate to return a mock future
    when(delegate.submit(any(Runnable.class), any())).thenReturn(null);
    
    // Submit a task with a result
    underTest.submit(() -> {}, "result");
    
    // Verify the task was wrapped and delegated
    verify(delegate).submit(any(Runnable.class), equalTo("result"));
    verify(subject).associateWith(any(Runnable.class));
  }
  
  /**
   * Tests that submit(Callable) correctly wraps and delegates the task.
   */
  @Test
  void submitCallableWrapsAndDelegatesTask() {
    // Setup the delegate to return a mock future
    when(delegate.submit(any(Callable.class))).thenReturn(null);
    
    // Submit a task
    underTest.submit(() -> "result");
    
    // Verify the task was wrapped and delegated
    verify(delegate).submit(any(Callable.class));
    verify(subject).associateWith(any(Callable.class));
  }
  
  /**
   * Tests that execute(Runnable) correctly wraps and delegates the task.
   */
  @Test
  void executeWrapsAndDelegatesTask() {
    // Execute a task
    underTest.execute(() -> {});
    
    // Verify the task was wrapped and delegated
    verify(delegate).execute(any(Runnable.class));
    verify(subject).associateWith(any(Runnable.class));
  }
  
  /**
   * Tests that invokeAll correctly wraps and delegates the tasks.
   */
  @Test
  void invokeAllWrapsAndDelegatesTasks() throws Exception {
    // Setup the delegate to return an empty list
    when(delegate.invokeAll(any())).thenReturn(List.of());
    
    // Invoke a collection of tasks
    List<Callable<String>> tasks = List.of(() -> "result1", () -> "result2");
    underTest.invokeAll(tasks);
    
    // Verify the tasks were wrapped and delegated
    verify(delegate).invokeAll(any());
    verify(subject, times(2)).associateWith(any(Callable.class));
  }
  
  /**
   * Tests that invokeAll with timeout correctly wraps and delegates the tasks.
   */
  @Test
  void invokeAllWithTimeoutWrapsAndDelegatesTasks() throws Exception {
    // Setup the delegate to return an empty list
    when(delegate.invokeAll(any(), anyLong(), any())).thenReturn(List.of());
    
    // Invoke a collection of tasks with a timeout
    List<Callable<String>> tasks = List.of(() -> "result1", () -> "result2");
    underTest.invokeAll(tasks, LONG_DELAY_MS, MILLISECONDS);
    
    // Verify the tasks were wrapped and delegated
    verify(delegate).invokeAll(any(), equalTo((long) LONG_DELAY_MS), equalTo(MILLISECONDS));
    verify(subject, times(2)).associateWith(any(Callable.class));
  }
  
  /**
   * Tests that invokeAny correctly wraps and delegates the tasks.
   */
  @Test
  void invokeAnyWrapsAndDelegatesTasks() throws Exception {
    // Setup the delegate to return a result
    when(delegate.invokeAny(any())).thenReturn("result");
    
    // Invoke a collection of tasks
    List<Callable<String>> tasks = List.of(() -> "result1", () -> "result2");
    String result = underTest.invokeAny(tasks);
    
    // Verify the tasks were wrapped and delegated and the result was returned
    verify(delegate).invokeAny(any());
    verify(subject, times(2)).associateWith(any(Callable.class));
    assertThat(result, is("result"));
  }
  
  /**
   * Tests that invokeAny with timeout correctly wraps and delegates the tasks.
   */
  @Test
  void invokeAnyWithTimeoutWrapsAndDelegatesTasks() throws Exception {
    // Setup the delegate to return a result
    when(delegate.invokeAny(any(), anyLong(), any())).thenReturn("result");
    
    // Invoke a collection of tasks with a timeout
    List<Callable<String>> tasks = List.of(() -> "result1", () -> "result2");
    String result = underTest.invokeAny(tasks, LONG_DELAY_MS, MILLISECONDS);
    
    // Verify the tasks were wrapped and delegated and the result was returned
    verify(delegate).invokeAny(any(), equalTo((long) LONG_DELAY_MS), equalTo(MILLISECONDS));
    verify(subject, times(2)).associateWith(any(Callable.class));
    assertThat(result, is("result"));
  }
  
  /**
   * Creates a mock ScheduledFuture that completes after a delay.
   */
  @SuppressWarnings("unchecked")
  private <T> ScheduledFuture<T> createCompletingScheduledFuture(T result) {
    ScheduledFuture<T> future = mock(ScheduledFuture.class);
    when(future.isDone()).thenReturn(true);
    when(future.isCancelled()).thenReturn(false);
    try {
      when(future.get()).thenReturn(result);
    }
    catch (Exception e) {
      // Ignore for test
    }
    return future;
  }
  
  /**
   * Creates a mock ScheduledFuture that completes after a delay (for Runnable tasks).
   */
  private ScheduledFuture<?> createCompletingScheduledFuture() {
    return createCompletingScheduledFuture(null);
  }
  
  /**
   * Creates a mock ScheduledFuture that does not complete (for periodic tasks).
   */
  @SuppressWarnings("unchecked")
  private <T> ScheduledFuture<T> createNonCompletingScheduledFuture() {
    ScheduledFuture<T> future = mock(ScheduledFuture.class);
    when(future.isDone()).thenReturn(false);
    when(future.isCancelled()).thenReturn(false);
    return future;
  }
  
  /**
   * Creates a mock ScheduledFuture that can be cancelled.
   */
  @SuppressWarnings("unchecked")
  private <T> ScheduledFuture<T> createCancellableScheduledFuture(AtomicBoolean cancelled) {
    ScheduledFuture<T> future = mock(ScheduledFuture.class);
    when(future.cancel(any(Boolean.class))).thenAnswer(invocation -> {
      cancelled.set(true);
      return true;
    });
    when(future.isCancelled()).thenAnswer(invocation -> cancelled.get());
    when(future.isDone()).thenAnswer(invocation -> cancelled.get());
    return future;
  }
}