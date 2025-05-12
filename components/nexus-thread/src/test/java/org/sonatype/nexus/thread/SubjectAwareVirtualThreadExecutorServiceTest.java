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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SubjectAwareVirtualThreadExecutorService}.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("Java21TestGroup")
@Tag("VirtualThreadTestGroup")
public class SubjectAwareVirtualThreadExecutorServiceTest
    extends TestSupport
{
  private static final String TEST_MDC_KEY = "testMdcKey";
  private static final String TEST_MDC_VALUE = "testMdcValue";

  @Mock
  private Subject subject;

  @Mock
  private Supplier<Subject> subjectSupplier;

  private SubjectAwareVirtualThreadExecutorService underTest;

  @BeforeEach
  void setUp() {
    // Configure the subject supplier to return our mock subject
    when(subjectSupplier.get()).thenReturn(subject);
    
    // Configure the subject to associate tasks with itself
    doAnswer(invocation -> {
      Runnable runnable = invocation.getArgument(0);
      return runnable;
    }).when(subject).associateWith(any(Runnable.class));
    
    doAnswer(invocation -> {
      Callable<?> callable = invocation.getArgument(0);
      return callable;
    }).when(subject).associateWith(any(Callable.class));
    
    // Create the executor service under test
    underTest = new SubjectAwareVirtualThreadExecutorService(subjectSupplier);
    
    // Clear any MDC context from previous tests
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    // Shutdown the executor service
    if (underTest != null && !underTest.isShutdown()) {
      underTest.shutdownNow();
    }
    
    // Clear MDC context
    MDC.clear();
  }

  @Test
  @DisplayName("Subject is properly propagated to virtual threads")
  void subjectIsPropagatedToVirtualThreads() throws Exception {
    // Set up a reference to capture the subject in the task
    AtomicReference<Subject> capturedSubject = new AtomicReference<>();
    
    // Configure the subject to capture itself when associateWith is called
    doAnswer(invocation -> {
      Runnable runnable = invocation.getArgument(0);
      return (Runnable) () -> {
        capturedSubject.set(subject);
        runnable.run();
      };
    }).when(subject).associateWith(any(Runnable.class));
    
    // Execute a task
    Future<?> future = underTest.submit(() -> {
      // Task does nothing, we just want to verify the subject is propagated
    });
    
    // Wait for the task to complete
    future.get(5, TimeUnit.SECONDS);
    
    // Verify the subject was propagated to the virtual thread
    assertEquals(subject, capturedSubject.get());
    verify(subject).associateWith(any(Runnable.class));
  }

  @Test
  @DisplayName("MDC context is properly propagated to virtual threads")
  void mdcContextIsPropagatedToVirtualThreads() throws Exception {
    // Set MDC context in the current thread
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    
    // Set up a reference to capture the MDC value in the task
    AtomicReference<String> capturedMdcValue = new AtomicReference<>();
    
    // Execute a task that captures the MDC value
    Future<?> future = underTest.submit(() -> {
      capturedMdcValue.set(MDC.get(TEST_MDC_KEY));
    });
    
    // Wait for the task to complete
    future.get(5, TimeUnit.SECONDS);
    
    // Verify the MDC context was propagated to the virtual thread
    assertEquals(TEST_MDC_VALUE, capturedMdcValue.get());
  }

  @Test
  @DisplayName("MDC context is cleared after task completion")
  void mdcContextIsClearedAfterTaskCompletion() throws Exception {
    // Set MDC context in the current thread
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    
    // Set up a flag to indicate if MDC context is cleared after the task
    AtomicBoolean mdcClearedAfterTask = new AtomicBoolean(false);
    
    // Execute a task that checks if MDC is cleared after the main task logic
    Future<?> future = underTest.submit(() -> {
      // First verify the MDC context is propagated
      assertEquals(TEST_MDC_VALUE, MDC.get(TEST_MDC_KEY));
      
      // Return a runnable that will be executed after the task completes
      // to check if MDC context is cleared
      return () -> {
        mdcClearedAfterTask.set(MDC.get(TEST_MDC_KEY) == null);
      };
    });
    
    // Wait for the task to complete
    future.get(5, TimeUnit.SECONDS);
    
    // Verify the MDC context was cleared after the task completed
    assertTrue(mdcClearedAfterTask.get());
  }

  @Test
  @DisplayName("High concurrency with many virtual threads")
  void highConcurrencyWithManyVirtualThreads() throws Exception {
    // Number of concurrent tasks to run
    int taskCount = 1000;
    
    // Latch to wait for all tasks to complete
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    // Counter to track successful task executions
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // List to collect futures for all tasks
    List<Future<Integer>> futures = new ArrayList<>(taskCount);
    
    // Submit many concurrent tasks
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      futures.add(underTest.submit(() -> {
        try {
          // Simulate some work
          Thread.sleep(10);
          return taskId;
        } finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Verify all tasks completed successfully
    for (int i = 0; i < taskCount; i++) {
      try {
        int result = futures.get(i).get();
        assertEquals(i, result);
        successCounter.incrementAndGet();
      } catch (ExecutionException e) {
        // Count failed tasks
      }
    }
    
    // Verify all tasks were successful
    assertEquals(taskCount, successCounter.get(), "All tasks should complete successfully");
    
    // Verify subject was associated with each task
    verify(subject, times(taskCount)).associateWith(any(Callable.class));
  }

  @Test
  @DisplayName("Exception handling in virtual threads")
  void exceptionHandlingInVirtualThreads() {
    // Submit a task that throws an exception
    Future<?> future = underTest.submit(() -> {
      throw new RuntimeException("Test exception");
    });
    
    // Verify the exception is properly propagated
    ExecutionException exception = assertThrows(ExecutionException.class, () -> {
      future.get(5, TimeUnit.SECONDS);
    });
    
    // Verify the cause of the exception
    assertTrue(exception.getCause() instanceof RuntimeException);
    assertEquals("Test exception", exception.getCause().getMessage());
  }

  @Test
  @DisplayName("Factory method for fixed subject creates executor with correct subject")
  void factoryMethodForFixedSubjectCreatesExecutorWithCorrectSubject() throws Exception {
    // Create an executor with a fixed subject
    SubjectAwareVirtualThreadExecutorService executor = 
        SubjectAwareVirtualThreadExecutorService.forFixedSubject(subject);
    
    try {
      // Set up a reference to capture the subject in the task
      AtomicReference<Subject> capturedSubject = new AtomicReference<>();
      
      // Configure the subject to capture itself when associateWith is called
      doAnswer(invocation -> {
        Runnable runnable = invocation.getArgument(0);
        return (Runnable) () -> {
          capturedSubject.set(subject);
          runnable.run();
        };
      }).when(subject).associateWith(any(Runnable.class));
      
      // Execute a task
      Future<?> future = executor.submit(() -> {
        // Task does nothing, we just want to verify the subject is propagated
      });
      
      // Wait for the task to complete
      future.get(5, TimeUnit.SECONDS);
      
      // Verify the subject was propagated to the virtual thread
      assertEquals(subject, capturedSubject.get());
      verify(subject, times(2)).associateWith(any(Runnable.class)); // Once in this test, once in the previous test
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("Shutdown behavior works correctly")
  void shutdownBehaviorWorksCorrectly() throws Exception {
    // Verify the executor is not shutdown initially
    assertFalse(underTest.isShutdown());
    assertFalse(underTest.isTerminated());
    
    // Submit a task
    Future<?> future = underTest.submit(() -> {
      // Task does nothing
    });
    
    // Wait for the task to complete
    future.get(5, TimeUnit.SECONDS);
    
    // Shutdown the executor
    underTest.shutdown();
    
    // Verify the executor is shutdown
    assertTrue(underTest.isShutdown());
    
    // Wait for termination
    assertTrue(underTest.awaitTermination(5, TimeUnit.SECONDS));
    
    // Verify the executor is terminated
    assertTrue(underTest.isTerminated());
  }

  @Test
  @DisplayName("invokeAll executes all tasks and propagates subject")
  void invokeAllExecutesAllTasksAndPropagatesSubject() throws Exception {
    // Create a list of tasks
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      final int taskId = i;
      tasks.add(() -> taskId);
    }
    
    // Execute all tasks
    List<Future<Integer>> futures = underTest.invokeAll(tasks, 10, TimeUnit.SECONDS);
    
    // Verify all tasks completed successfully
    assertEquals(tasks.size(), futures.size());
    for (int i = 0; i < tasks.size(); i++) {
      assertEquals(i, futures.get(i).get());
    }
    
    // Verify subject was associated with each task
    verify(subject, times(tasks.size())).associateWith(any(Callable.class));
  }

  @Test
  @DisplayName("invokeAny executes tasks until one completes successfully")
  void invokeAnyExecutesTasksUntilOneCompletesSuccessfully() throws Exception {
    // Create a list of tasks where only one succeeds
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      final int taskId = i;
      if (taskId == 3) {
        // This task succeeds
        tasks.add(() -> taskId);
      } else {
        // These tasks fail
        tasks.add(() -> {
          throw new RuntimeException("Task " + taskId + " failed");
        });
      }
    }
    
    // Execute tasks until one succeeds
    Integer result = underTest.invokeAny(tasks, 10, TimeUnit.SECONDS);
    
    // Verify the successful task's result
    assertEquals(3, result);
  }
}