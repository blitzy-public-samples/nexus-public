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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
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
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class VirtualThreadScheduledExecutorServiceTest
    extends TestSupport
{
  @Mock
  private ScheduledExecutorService delegateExecutor;

  @Mock
  private Subject subject;

  private VirtualThreadScheduledExecutorService underTest;

  @BeforeEach
  void setUp() {
    underTest = new VirtualThreadScheduledExecutorService(delegateExecutor, () -> subject);
  }

  @Test
  void testScheduleRunnable() throws Exception {
    // Setup a latch to wait for task execution
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean executed = new AtomicBoolean(false);
    AtomicReference<Thread> executionThread = new AtomicReference<>();

    // Mock the delegate to execute the task immediately
    doAnswer(invocation -> {
      Runnable command = invocation.getArgument(0);
      command.run(); // Execute the command immediately
      return null;
    }).when(delegateExecutor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

    // Create a task that will record its execution
    Runnable task = () -> {
      executionThread.set(Thread.currentThread());
      executed.set(true);
      latch.countDown();
    };

    // Schedule the task
    underTest.schedule(task, 100, TimeUnit.MILLISECONDS);

    // Wait for the task to complete
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Task should have executed");
    
    // Verify the task was executed
    assertTrue(executed.get(), "Task should have been executed");
    
    // Verify the task was executed in a virtual thread
    assertThat(executionThread.get(), notNullValue());
    assertTrue(executionThread.get().isVirtual(), "Task should have been executed in a virtual thread");
    
    // Verify the delegate was called with the correct parameters
    verify(delegateExecutor).schedule(any(Runnable.class), equalTo(100L), equalTo(TimeUnit.MILLISECONDS));
  }

  @Test
  void testScheduleCallable() throws Exception {
    // Setup a latch to wait for task execution
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Thread> executionThread = new AtomicReference<>();
    AtomicBoolean executed = new AtomicBoolean(false);

    // Mock the delegate to execute the task immediately
    doAnswer(invocation -> {
      Runnable command = invocation.getArgument(0);
      command.run(); // Execute the command immediately
      return null;
    }).when(delegateExecutor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

    // Create a task that will record its execution
    underTest.schedule(() -> {
      executionThread.set(Thread.currentThread());
      executed.set(true);
      latch.countDown();
      return "result";
    }, 100, TimeUnit.MILLISECONDS);

    // Wait for the task to complete
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Task should have executed");
    
    // Verify the task was executed
    assertTrue(executed.get(), "Task should have been executed");
    
    // Verify the task was executed in a virtual thread
    assertThat(executionThread.get(), notNullValue());
    assertTrue(executionThread.get().isVirtual(), "Task should have been executed in a virtual thread");
    
    // Verify the delegate was called with the correct parameters
    verify(delegateExecutor).schedule(any(Runnable.class), equalTo(100L), equalTo(TimeUnit.MILLISECONDS));
  }

  @Test
  void testScheduleAtFixedRate() throws Exception {
    // Setup a counter to track executions
    AtomicInteger executionCount = new AtomicInteger(0);
    AtomicReference<Thread> executionThread = new AtomicReference<>();

    // Mock the delegate to execute the task immediately
    doAnswer(invocation -> {
      Runnable command = invocation.getArgument(0);
      command.run(); // Execute the command immediately
      return null;
    }).when(delegateExecutor).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

    // Create a task that will record its execution
    Runnable task = () -> {
      executionThread.set(Thread.currentThread());
      executionCount.incrementAndGet();
    };

    // Schedule the task
    underTest.scheduleAtFixedRate(task, 100, 200, TimeUnit.MILLISECONDS);

    // Wait for the task to be executed
    await().atMost(1, TimeUnit.SECONDS).until(() -> executionCount.get() > 0);
    
    // Verify the task was executed
    assertThat(executionCount.get(), is(1));
    
    // Verify the task was executed in a virtual thread
    assertThat(executionThread.get(), notNullValue());
    assertTrue(executionThread.get().isVirtual(), "Task should have been executed in a virtual thread");
    
    // Verify the delegate was called with the correct parameters
    verify(delegateExecutor).scheduleAtFixedRate(
        any(Runnable.class), 
        equalTo(100L), 
        equalTo(200L), 
        equalTo(TimeUnit.MILLISECONDS));
  }

  @Test
  void testScheduleWithFixedDelay() throws Exception {
    // Setup a counter to track executions
    AtomicInteger executionCount = new AtomicInteger(0);
    AtomicReference<Thread> executionThread = new AtomicReference<>();

    // Mock the delegate to execute the task immediately
    doAnswer(invocation -> {
      Runnable command = invocation.getArgument(0);
      command.run(); // Execute the command immediately
      return null;
    }).when(delegateExecutor).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

    // Create a task that will record its execution
    Runnable task = () -> {
      executionThread.set(Thread.currentThread());
      executionCount.incrementAndGet();
    };

    // Schedule the task
    underTest.scheduleWithFixedDelay(task, 100, 200, TimeUnit.MILLISECONDS);

    // Wait for the task to be executed
    await().atMost(1, TimeUnit.SECONDS).until(() -> executionCount.get() > 0);
    
    // Verify the task was executed
    assertThat(executionCount.get(), is(1));
    
    // Verify the task was executed in a virtual thread
    assertThat(executionThread.get(), notNullValue());
    assertTrue(executionThread.get().isVirtual(), "Task should have been executed in a virtual thread");
    
    // Verify the delegate was called with the correct parameters
    verify(delegateExecutor).scheduleWithFixedDelay(
        any(Runnable.class), 
        equalTo(100L), 
        equalTo(200L), 
        equalTo(TimeUnit.MILLISECONDS));
  }

  @Test
  void testSubjectPropagation() throws Exception {
    // Setup a latch to wait for task execution
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Subject> taskSubject = new AtomicReference<>();

    // Mock the subject to associate with the runnable and capture itself
    when(subject.associateWith(any(Runnable.class))).thenAnswer(invocation -> {
      Runnable originalRunnable = invocation.getArgument(0);
      return (Runnable) () -> {
        taskSubject.set(subject);
        originalRunnable.run();
      };
    });

    // Mock the delegate to execute the task immediately
    doAnswer(invocation -> {
      Runnable command = invocation.getArgument(0);
      command.run(); // Execute the command immediately
      return null;
    }).when(delegateExecutor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

    // Schedule a task
    underTest.schedule(() -> latch.countDown(), 100, TimeUnit.MILLISECONDS);

    // Wait for the task to complete
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Task should have executed");
    
    // Verify the subject was propagated to the task
    assertThat(taskSubject.get(), is(subject));
    
    // Verify the subject.associateWith was called
    verify(subject, times(1)).associateWith(any(Runnable.class));
  }

  @Test
  void testCancellation() throws Exception {
    // Setup a mock ScheduledFuture
    @SuppressWarnings("unchecked")
    ScheduledFuture<Object> mockFuture = mock(ScheduledFuture.class);
    when(mockFuture.cancel(true)).thenReturn(true);
    
    // Mock the delegate to return the mock future
    when(delegateExecutor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenReturn(mockFuture);

    // Schedule a task
    ScheduledFuture<?> future = underTest.schedule(() -> {}, 100, TimeUnit.MILLISECONDS);

    // Cancel the task
    boolean result = future.cancel(true);

    // Verify the result
    assertTrue(result, "Cancel should return true");
    
    // Verify the delegate future was cancelled
    verify(mockFuture).cancel(true);
  }

  @Test
  void testShutdown() {
    // Call shutdown
    underTest.shutdown();

    // Verify the delegate was shut down
    verify(delegateExecutor).shutdown();
  }

  @Test
  void testShutdownNow() {
    // Call shutdownNow
    underTest.shutdownNow();

    // Verify the delegate was shut down
    verify(delegateExecutor).shutdownNow();
  }

  @Test
  void testIsShutdown() {
    // Mock the delegate
    when(delegateExecutor.isShutdown()).thenReturn(true);

    // Check if the executor is shut down
    boolean result = underTest.isShutdown();

    // Verify the result
    assertTrue(result, "isShutdown should return true");
    
    // Verify the delegate was called
    verify(delegateExecutor).isShutdown();
  }

  @Test
  void testIsTerminated() {
    // Mock the delegate
    when(delegateExecutor.isTerminated()).thenReturn(true);

    // Check if the executor is terminated
    boolean result = underTest.isTerminated();

    // Verify the result
    assertTrue(result, "isTerminated should return true");
    
    // Verify the delegate was called
    verify(delegateExecutor).isTerminated();
  }

  @Test
  void testAwaitTermination() throws Exception {
    // Mock the delegate
    when(delegateExecutor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);

    // Wait for termination
    boolean result = underTest.awaitTermination(100, TimeUnit.MILLISECONDS);

    // Verify the result
    assertTrue(result, "awaitTermination should return true");
    
    // Verify the delegate was called
    verify(delegateExecutor).awaitTermination(100, TimeUnit.MILLISECONDS);
  }

  @Test
  void testFactoryMethodForFixedSubject() {
    // Create an executor with a fixed subject
    VirtualThreadScheduledExecutorService executor = 
        VirtualThreadScheduledExecutorService.forFixedSubject(delegateExecutor, subject);

    // Verify the executor is not null
    assertThat(executor, notNullValue());
  }

  @Test
  void testFactoryMethodForCurrentSubject() {
    // Create an executor with the current subject
    VirtualThreadScheduledExecutorService executor = 
        VirtualThreadScheduledExecutorService.forCurrentSubject(delegateExecutor);

    // Verify the executor is not null
    assertThat(executor, notNullValue());
  }
}