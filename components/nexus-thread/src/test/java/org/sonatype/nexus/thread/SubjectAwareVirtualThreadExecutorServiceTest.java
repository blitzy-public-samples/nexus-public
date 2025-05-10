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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.concurrent.ConcurrentRunner;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SubjectAwareVirtualThreadExecutorService}.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.condition.EnabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_21)
@org.junit.jupiter.api.Tag("Java21TestGroup")
@org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
public class SubjectAwareVirtualThreadExecutorServiceTest
    extends TestSupport
{
  @Mock
  private Subject subject;

  @Mock
  private Supplier<Subject> subjectSupplier;

  private ExecutorService underTest;

  @BeforeEach
  void setUp() {
    // Clear any ThreadContext from previous tests
    ThreadContext.remove();
    MDC.clear();
    
    // Setup the subject supplier mock
    when(subjectSupplier.get()).thenReturn(subject);
    
    // Create the executor service under test
    underTest = new SubjectAwareVirtualThreadExecutorService(subjectSupplier);
  }

  @AfterEach
  void tearDown() {
    // Ensure executor is shutdown after each test
    if (underTest != null && !underTest.isShutdown()) {
      underTest.shutdownNow();
    }
    
    // Clear ThreadContext and MDC
    ThreadContext.remove();
    MDC.clear();
  }

  @Test
  void testExecuteRunnable() throws Exception {
    // Setup a latch to wait for task completion
    CountDownLatch latch = new CountDownLatch(1);
    
    // Setup a reference to capture the subject from the virtual thread
    AtomicReference<Subject> executedThreadSubject = new AtomicReference<>();
    
    // Execute a task that captures the subject from the thread context
    underTest.execute(() -> {
      executedThreadSubject.set(ThreadContext.getSubject());
      latch.countDown();
    });
    
    // Wait for task to complete
    latch.await(1, TimeUnit.SECONDS);
    
    // Verify the subject was properly associated with the virtual thread
    assertThat(executedThreadSubject.get(), is(subject));
  }

  @Test
  void testSubmitRunnable() throws Exception {
    // Setup a reference to capture the subject from the virtual thread
    AtomicReference<Subject> executedThreadSubject = new AtomicReference<>();
    
    // Submit a task that captures the subject from the thread context
    Future<?> future = underTest.submit(() -> {
      executedThreadSubject.set(ThreadContext.getSubject());
    });
    
    // Wait for task to complete
    future.get(1, TimeUnit.SECONDS);
    
    // Verify the subject was properly associated with the virtual thread
    assertThat(executedThreadSubject.get(), is(subject));
  }

  @Test
  void testSubmitRunnableWithResult() throws Exception {
    // Setup a reference to capture the subject from the virtual thread
    AtomicReference<Subject> executedThreadSubject = new AtomicReference<>();
    String expectedResult = "test-result";
    
    // Submit a task that captures the subject from the thread context and returns a result
    Future<String> future = underTest.submit(() -> {
      executedThreadSubject.set(ThreadContext.getSubject());
    }, expectedResult);
    
    // Wait for task to complete and get the result
    String result = future.get(1, TimeUnit.SECONDS);
    
    // Verify the subject was properly associated with the virtual thread
    assertThat(executedThreadSubject.get(), is(subject));
    // Verify the result was correctly returned
    assertThat(result, is(expectedResult));
  }

  @Test
  void testSubmitCallable() throws Exception {
    // Setup a reference to capture the subject from the virtual thread
    AtomicReference<Subject> executedThreadSubject = new AtomicReference<>();
    String expectedResult = "callable-result";
    
    // Submit a callable that captures the subject from the thread context and returns a result
    Future<String> future = underTest.submit(() -> {
      executedThreadSubject.set(ThreadContext.getSubject());
      return expectedResult;
    });
    
    // Wait for task to complete and get the result
    String result = future.get(1, TimeUnit.SECONDS);
    
    // Verify the subject was properly associated with the virtual thread
    assertThat(executedThreadSubject.get(), is(subject));
    // Verify the result was correctly returned
    assertThat(result, is(expectedResult));
  }

  @Test
  void testInvokeAll() throws Exception {
    // Create a list of callables
    int taskCount = 5;
    List<Callable<Integer>> tasks = new ArrayList<>();
    List<AtomicReference<Subject>> subjects = new ArrayList<>();
    
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      AtomicReference<Subject> taskSubject = new AtomicReference<>();
      subjects.add(taskSubject);
      
      tasks.add(() -> {
        taskSubject.set(ThreadContext.getSubject());
        return taskId;
      });
    }
    
    // Invoke all tasks
    List<Future<Integer>> futures = underTest.invokeAll(tasks);
    
    // Verify all tasks completed and had the correct subject
    for (int i = 0; i < taskCount; i++) {
      assertThat(futures.get(i).get(), is(i));
      assertThat(subjects.get(i).get(), is(subject));
    }
  }

  @Test
  void testInvokeAllWithTimeout() throws Exception {
    // Create a list of callables
    int taskCount = 5;
    List<Callable<Integer>> tasks = new ArrayList<>();
    List<AtomicReference<Subject>> subjects = new ArrayList<>();
    
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      AtomicReference<Subject> taskSubject = new AtomicReference<>();
      subjects.add(taskSubject);
      
      tasks.add(() -> {
        taskSubject.set(ThreadContext.getSubject());
        return taskId;
      });
    }
    
    // Invoke all tasks with a timeout
    List<Future<Integer>> futures = underTest.invokeAll(tasks, 1, TimeUnit.SECONDS);
    
    // Verify all tasks completed and had the correct subject
    for (int i = 0; i < taskCount; i++) {
      assertThat(futures.get(i).get(), is(i));
      assertThat(subjects.get(i).get(), is(subject));
    }
  }

  @Test
  void testInvokeAny() throws Exception {
    // Create a list of callables
    int taskCount = 5;
    List<Callable<String>> tasks = new ArrayList<>();
    String expectedResult = "invoke-any-result";
    
    for (int i = 0; i < taskCount; i++) {
      tasks.add(() -> {
        // Verify the subject is correctly associated with the thread
        assertThat(ThreadContext.getSubject(), is(subject));
        return expectedResult;
      });
    }
    
    // Invoke any task
    String result = underTest.invokeAny(tasks);
    
    // Verify the result
    assertThat(result, is(expectedResult));
  }

  @Test
  void testInvokeAnyWithTimeout() throws Exception {
    // Create a list of callables
    int taskCount = 5;
    List<Callable<String>> tasks = new ArrayList<>();
    String expectedResult = "invoke-any-timeout-result";
    
    for (int i = 0; i < taskCount; i++) {
      tasks.add(() -> {
        // Verify the subject is correctly associated with the thread
        assertThat(ThreadContext.getSubject(), is(subject));
        return expectedResult;
      });
    }
    
    // Invoke any task with a timeout
    String result = underTest.invokeAny(tasks, 1, TimeUnit.SECONDS);
    
    // Verify the result
    assertThat(result, is(expectedResult));
  }

  @Test
  void testMDCContextPropagation() throws Exception {
    // Setup MDC context
    String mdcKey = "test-key";
    String mdcValue = "test-value";
    MDC.put(mdcKey, mdcValue);
    
    // Setup a reference to capture the MDC value from the virtual thread
    AtomicReference<String> executedThreadMdcValue = new AtomicReference<>();
    
    // Submit a task that captures the MDC value
    Future<?> future = underTest.submit(() -> {
      executedThreadMdcValue.set(MDC.get(mdcKey));
    });
    
    // Wait for task to complete
    future.get(1, TimeUnit.SECONDS);
    
    // Verify the MDC context was properly propagated to the virtual thread
    assertThat(executedThreadMdcValue.get(), is(mdcValue));
  }

  @Test
  void testExceptionHandling() {
    // Submit a task that throws an exception
    Future<String> future = underTest.submit(() -> {
      throw new RuntimeException("Test exception");
    });
    
    // Verify the exception is properly propagated
    ExecutionException exception = assertThrows(ExecutionException.class, () -> future.get());
    assertThat(exception.getCause(), is(notNullValue()));
    assertThat(exception.getCause().getMessage(), is("Test exception"));
  }

  @Test
  void testHighConcurrency() throws Exception {
    // Test with a high number of concurrent tasks
    int taskCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(taskCount);
    List<AtomicReference<Subject>> subjects = new ArrayList<>();
    
    // Submit many tasks that will all start at the same time
    for (int i = 0; i < taskCount; i++) {
      AtomicReference<Subject> taskSubject = new AtomicReference<>();
      subjects.add(taskSubject);
      
      underTest.submit(() -> {
        try {
          startLatch.await(); // Wait for the signal to start
          taskSubject.set(ThreadContext.getSubject());
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Signal all tasks to start
    startLatch.countDown();
    
    // Wait for all tasks to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify all tasks had the correct subject
    for (AtomicReference<Subject> taskSubject : subjects) {
      assertThat(taskSubject.get(), is(subject));
    }
  }

  @Test
  void testShutdown() throws Exception {
    // Submit a task
    Future<?> future = underTest.submit(() -> {
      // Do nothing
    });
    
    // Wait for task to complete
    future.get(1, TimeUnit.SECONDS);
    
    // Shutdown the executor
    underTest.shutdown();
    
    // Verify the executor is shutdown
    assertThat(underTest.isShutdown(), is(true));
  }

  @Test
  void testShutdownNow() {
    // Submit a task that will block
    underTest.submit(() -> {
      try {
        Thread.sleep(10000); // This should be interrupted
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    // Shutdown the executor immediately
    List<Runnable> pendingTasks = underTest.shutdownNow();
    
    // Verify the executor is shutdown
    assertThat(underTest.isShutdown(), is(true));
  }

  @Test
  void testForFixedSubject() throws Exception {
    // Create a fixed subject
    Subject fixedSubject = new FakeAlmightySubject();
    
    // Create an executor with the fixed subject
    ExecutorService fixedExecutor = SubjectAwareVirtualThreadExecutorService.forFixedSubject(fixedSubject);
    
    try {
      // Setup a reference to capture the subject from the virtual thread
      AtomicReference<Subject> executedThreadSubject = new AtomicReference<>();
      
      // Submit a task that captures the subject from the thread context
      Future<?> future = fixedExecutor.submit(() -> {
        executedThreadSubject.set(ThreadContext.getSubject());
      });
      
      // Wait for task to complete
      future.get(1, TimeUnit.SECONDS);
      
      // Verify the fixed subject was properly associated with the virtual thread
      assertThat(executedThreadSubject.get(), is(fixedSubject));
    }
    finally {
      fixedExecutor.shutdownNow();
    }
  }

  @Test
  void testForCurrentSubject() throws Exception {
    // Set a current subject
    Subject currentSubject = new FakeAlmightySubject();
    ThreadContext.bind(currentSubject);
    
    // Create an executor that uses the current subject
    ExecutorService currentExecutor = SubjectAwareVirtualThreadExecutorService.forCurrentSubject();
    
    try {
      // Setup a reference to capture the subject from the virtual thread
      AtomicReference<Subject> executedThreadSubject = new AtomicReference<>();
      
      // Submit a task that captures the subject from the thread context
      Future<?> future = currentExecutor.submit(() -> {
        executedThreadSubject.set(ThreadContext.getSubject());
      });
      
      // Wait for task to complete
      future.get(1, TimeUnit.SECONDS);
      
      // Verify the current subject was properly associated with the virtual thread
      assertThat(executedThreadSubject.get(), is(currentSubject));
    }
    finally {
      currentExecutor.shutdownNow();
    }
  }

  @Test
  void testVirtualThreadCleanup() throws Exception {
    // Setup MDC context
    String mdcKey = "cleanup-test-key";
    String mdcValue = "cleanup-test-value";
    MDC.put(mdcKey, mdcValue);
    
    // Setup a reference to capture the MDC value after task completion
    AtomicReference<String> afterTaskMdcValue = new AtomicReference<>();
    
    // Submit a task that modifies the MDC context
    Future<?> future = underTest.submit(() -> {
      // Verify MDC context is propagated to the virtual thread
      assertThat(MDC.get(mdcKey), is(mdcValue));
      
      // Modify the MDC context in the virtual thread
      MDC.put(mdcKey, "modified-value");
      MDC.put("new-key", "new-value");
    });
    
    // Wait for task to complete
    future.get(1, TimeUnit.SECONDS);
    
    // Capture the MDC value after task completion
    afterTaskMdcValue.set(MDC.get(mdcKey));
    
    // Verify the MDC context in the main thread was not affected by changes in the virtual thread
    assertThat(afterTaskMdcValue.get(), is(mdcValue));
    assertThat(MDC.get("new-key"), is(nullValue()));
  }
}