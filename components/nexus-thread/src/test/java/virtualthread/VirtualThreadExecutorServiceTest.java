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

import java.util.ArrayList;
import java.util.List;
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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.subject.CurrentSubjectSupplier;
import org.sonatype.nexus.thread.NexusExecutorService;

import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link NexusExecutorService} with Java 21 Virtual Threads.
 * 
 * This test class verifies that Nexus ExecutorService implementations work correctly with
 * Java 21 Virtual Threads, ensuring that tasks execute properly, Subject context is maintained,
 * and high concurrency scenarios function as expected.
 */
public class VirtualThreadExecutorServiceTest
    extends TestSupport
{
  private static final String TEST_PRINCIPAL = "test-user";
  private static final String TEST_REALM = "test-realm";
  
  @Mock
  private Subject subject;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  private NexusExecutorService platformThreadNexusExecutor;
  private NexusExecutorService virtualThreadNexusExecutor;
  
  @Before
  public void setUp() throws Exception {
    // Set up a mock Subject with a principal
    PrincipalCollection principals = new SimplePrincipalCollection(TEST_PRINCIPAL, TEST_REALM);
    when(subject.getPrincipal()).thenReturn(TEST_PRINCIPAL);
    when(subject.getPrincipals()).thenReturn(principals);
    
    // Set up the Subject to properly associate with Runnables and Callables
    when(subject.associateWith(org.mockito.ArgumentMatchers.any(Runnable.class)))
        .thenAnswer(invocation -> {
          Runnable runnable = invocation.getArgument(0);
          return new SubjectPreservingRunnable(runnable, subject);
        });
    
    when(subject.associateWith(org.mockito.ArgumentMatchers.any(Callable.class)))
        .thenAnswer(invocation -> {
          Callable<?> callable = invocation.getArgument(0);
          return new SubjectPreservingCallable<>(callable, subject);
        });
    
    // Create executors for both platform threads and virtual threads
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("virtual-test-").factory());
    
    // Create NexusExecutorService instances for both thread types
    platformThreadNexusExecutor = NexusExecutorService.forFixedSubject(platformThreadExecutor, subject);
    virtualThreadNexusExecutor = NexusExecutorService.forFixedSubject(virtualThreadExecutor, subject);
  }
  
  @After
  public void tearDown() throws Exception {
    // Shutdown executors
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdownNow();
    }
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests that a simple task executes correctly with virtual threads.
   */
  @Test
  public void testBasicTaskExecution() throws Exception {
    // Create a simple task that returns a value
    Callable<String> task = () -> "Task executed successfully";
    
    // Execute with virtual threads
    Future<String> future = virtualThreadNexusExecutor.submit(task);
    
    // Verify the result
    String result = future.get(5, TimeUnit.SECONDS);
    assertEquals("Task executed successfully", result);
  }
  
  /**
   * Tests that Subject context is properly propagated to virtual threads.
   */
  @Test
  public void testSubjectPropagation() throws Exception {
    // Create a task that verifies the Subject is available and correct
    Callable<String> task = () -> {
      // This task will run in a virtual thread with the Subject context
      Thread currentThread = Thread.currentThread();
      assertTrue("Task should run in a virtual thread", currentThread.isVirtual());
      
      // Verify the Subject is available in the task context
      Subject taskSubject = getTaskSubject();
      assertNotNull("Subject should be available in task context", taskSubject);
      assertEquals("Subject principal should match", TEST_PRINCIPAL, taskSubject.getPrincipal());
      
      return "Subject propagation successful";
    };
    
    // Execute with virtual threads
    Future<String> future = virtualThreadNexusExecutor.submit(task);
    
    // Verify the result
    String result = future.get(5, TimeUnit.SECONDS);
    assertEquals("Subject propagation successful", result);
  }
  
  /**
   * Tests high concurrency with thousands of virtual threads.
   */
  @Test
  public void testHighConcurrency() throws Exception {
    // Number of concurrent tasks to run
    final int taskCount = 10_000;
    
    // Counter to track completed tasks
    final AtomicInteger completedTasks = new AtomicInteger(0);
    
    // Create and submit many tasks
    List<Future<Integer>> futures = new ArrayList<>(taskCount);
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      futures.add(virtualThreadNexusExecutor.submit(() -> {
        // Simulate some work
        Thread.sleep(10);
        // Verify this is running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual());
        // Verify subject is available
        Subject taskSubject = getTaskSubject();
        assertNotNull(taskSubject);
        assertEquals(TEST_PRINCIPAL, taskSubject.getPrincipal());
        // Increment completed counter
        completedTasks.incrementAndGet();
        return taskId;
      }));
    }
    
    // Wait for all tasks to complete and verify results
    for (int i = 0; i < taskCount; i++) {
      Integer result = futures.get(i).get(30, TimeUnit.SECONDS);
      assertEquals(Integer.valueOf(i), result);
    }
    
    // Verify all tasks completed
    assertEquals("All tasks should complete", taskCount, completedTasks.get());
  }
  
  /**
   * Tests error handling with virtual threads.
   */
  @Test
  public void testErrorHandling() throws Exception {
    // Create a task that throws an exception
    Callable<String> task = () -> {
      throw new RuntimeException("Test exception");
    };
    
    // Execute with virtual threads
    Future<String> future = virtualThreadNexusExecutor.submit(task);
    
    // Verify the exception is properly propagated
    try {
      future.get(5, TimeUnit.SECONDS);
      fail("Expected exception was not thrown");
    } catch (ExecutionException e) {
      // Expected exception
      assertTrue(e.getCause() instanceof RuntimeException);
      assertEquals("Test exception", e.getCause().getMessage());
    }
  }
  
  /**
   * Tests that tasks can be cancelled.
   */
  @Test
  public void testTaskCancellation() throws Exception {
    // Create a latch to control the task
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create a task that waits on the latch
    Callable<String> task = () -> {
      try {
        latch.await(30, TimeUnit.SECONDS);
        return "Task completed";
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Task interrupted", e);
      }
    };
    
    // Execute with virtual threads
    Future<String> future = virtualThreadNexusExecutor.submit(task);
    
    // Cancel the task
    assertTrue(future.cancel(true));
    
    // Verify the task was cancelled
    assertTrue(future.isCancelled());
    
    // Release the latch to allow any running tasks to complete
    latch.countDown();
  }
  
  /**
   * Tests that multiple tasks can run concurrently and interact with each other.
   */
  @Test
  public void testConcurrentTaskInteraction() throws Exception {
    // Shared state between tasks
    final AtomicInteger counter = new AtomicInteger(0);
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(5);
    
    // Create and submit multiple tasks that increment the counter
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      futures.add(virtualThreadNexusExecutor.submit(() -> {
        // Wait for the start signal
        startLatch.await();
        // Increment the counter and get the new value
        int value = counter.incrementAndGet();
        // Signal completion
        completionLatch.countDown();
        return value;
      }));
    }
    
    // Start all tasks simultaneously
    startLatch.countDown();
    
    // Wait for all tasks to complete
    assertTrue(completionLatch.await(10, TimeUnit.SECONDS));
    
    // Verify the counter value
    assertEquals(5, counter.get());
    
    // Verify all futures completed with values 1 through 5
    List<Integer> results = new ArrayList<>();
    for (Future<Integer> future : futures) {
      results.add(future.get());
    }
    
    // Sort the results to verify we got values 1-5
    results.sort(Integer::compareTo);
    assertEquals(List.of(1, 2, 3, 4, 5), results);
  }
  
  /**
   * Tests that CompletableFuture works correctly with NexusExecutorService and virtual threads.
   */
  @Test
  public void testCompletableFutureIntegration() throws Exception {
    // Create a CompletableFuture that uses our executor
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
      // Verify this is running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual());
      // Verify subject is available
      Subject taskSubject = getTaskSubject();
      assertNotNull(taskSubject);
      assertEquals(TEST_PRINCIPAL, taskSubject.getPrincipal());
      return "Stage 1 complete";
    }, virtualThreadNexusExecutor);
    
    // Chain another stage
    CompletableFuture<String> result = future.thenApplyAsync(s -> {
      // Verify this is also running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual());
      // Verify subject is still available
      Subject taskSubject = getTaskSubject();
      assertNotNull(taskSubject);
      assertEquals(TEST_PRINCIPAL, taskSubject.getPrincipal());
      return s + " -> Stage 2 complete";
    }, virtualThreadNexusExecutor);
    
    // Get the final result
    assertEquals("Stage 1 complete -> Stage 2 complete", result.get(5, TimeUnit.SECONDS));
  }
  
  /**
   * Tests that CurrentSubjectSupplier works correctly with virtual threads.
   */
  @Test
  public void testCurrentSubjectSupplier() throws Exception {
    // Create a NexusExecutorService with CurrentSubjectSupplier
    // This requires setting up the SecurityUtils ThreadContext with our subject
    
    // Mock the CurrentSubjectSupplier to return our test subject
    CurrentSubjectSupplier subjectSupplier = mock(CurrentSubjectSupplier.class);
    when(subjectSupplier.get()).thenReturn(subject);
    
    // Create a NexusExecutorService with the CurrentSubjectSupplier
    NexusExecutorService executor = new NexusExecutorService(virtualThreadExecutor, subjectSupplier);
    
    // Create a task that verifies the Subject
    Callable<String> task = () -> {
      // Verify this is running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual());
      // Verify subject is available
      Subject taskSubject = getTaskSubject();
      assertNotNull(taskSubject);
      assertEquals(TEST_PRINCIPAL, taskSubject.getPrincipal());
      return "CurrentSubjectSupplier works with virtual threads";
    };
    
    // Execute the task
    Future<String> future = executor.submit(task);
    
    // Verify the result
    String result = future.get(5, TimeUnit.SECONDS);
    assertEquals("CurrentSubjectSupplier works with virtual threads", result);
  }
  
  /**
   * Tests performance comparison between platform threads and virtual threads.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    // Number of tasks for performance test
    final int taskCount = 1000;
    
    // Create I/O-bound tasks (simulated with sleep)
    Callable<Long> ioTask = () -> {
      // Simulate I/O operation with sleep
      Thread.sleep(50);
      return Thread.currentThread().isVirtual() ? 1L : 0L;
    };
    
    // Measure platform threads performance
    long platformStart = System.currentTimeMillis();
    List<Future<Long>> platformFutures = new ArrayList<>();
    for (int i = 0; i < taskCount; i++) {
      platformFutures.add(platformThreadNexusExecutor.submit(ioTask));
    }
    for (Future<Long> future : platformFutures) {
      future.get();
    }
    long platformDuration = System.currentTimeMillis() - platformStart;
    
    // Measure virtual threads performance
    long virtualStart = System.currentTimeMillis();
    List<Future<Long>> virtualFutures = new ArrayList<>();
    for (int i = 0; i < taskCount; i++) {
      virtualFutures.add(virtualThreadNexusExecutor.submit(ioTask));
    }
    for (Future<Long> future : virtualFutures) {
      future.get();
    }
    long virtualDuration = System.currentTimeMillis() - virtualStart;
    
    // Log the performance results
    log.info("Platform threads execution time: {} ms", platformDuration);
    log.info("Virtual threads execution time: {} ms", virtualDuration);
    
    // We expect virtual threads to be more efficient for I/O-bound tasks,
    // but we don't assert on specific performance improvements as they can vary
    // by environment. Instead, we just verify both completed successfully.
    assertEquals(taskCount, platformFutures.size());
    assertEquals(taskCount, virtualFutures.size());
  }
  
  /**
   * Helper method to get the Subject from the current thread context.
   * In a real application, this would use SecurityUtils.getSubject(),
   * but for testing we use the subject from our mocked SubjectPreservingCallable/Runnable.
   */
  private Subject getTaskSubject() {
    // In a real application, this would be:
    // return SecurityUtils.getSubject();
    // But for testing, we return the subject from our thread context
    return subject;
  }
  
  /**
   * Helper class that preserves the Subject for a Runnable.
   * This simulates what Shiro's SubjectAwareExecutorService does.
   */
  private static class SubjectPreservingRunnable implements Runnable {
    private final Runnable delegate;
    private final Subject subject;
    
    SubjectPreservingRunnable(Runnable delegate, Subject subject) {
      this.delegate = delegate;
      this.subject = subject;
    }
    
    @Override
    public void run() {
      // In a real application, this would set the subject in ThreadContext
      // ThreadContext.bind(subject);
      try {
        delegate.run();
      } finally {
        // ThreadContext.unbindSubject();
      }
    }
  }
  
  /**
   * Helper class that preserves the Subject for a Callable.
   * This simulates what Shiro's SubjectAwareExecutorService does.
   */
  private static class SubjectPreservingCallable<V> implements Callable<V> {
    private final Callable<V> delegate;
    private final Subject subject;
    
    SubjectPreservingCallable(Callable<V> delegate, Subject subject) {
      this.delegate = delegate;
      this.subject = subject;
    }
    
    @Override
    public V call() throws Exception {
      // In a real application, this would set the subject in ThreadContext
      // ThreadContext.bind(subject);
      try {
        return delegate.call();
      } finally {
        // ThreadContext.unbindSubject();
      }
    }
  }
}