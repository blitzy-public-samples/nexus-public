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
package org.sonatype.nexus.virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.thread.NexusForkJoinPoolFactory;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link NexusForkJoinPoolFactory} with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class NexusForkJoinPoolFactoryVirtualThreadTest
{
  private static final String TEST_PREFIX = "virtual-thread-test-";
  
  /**
   * Verifies that virtual thread executors created by the factory correctly apply thread naming conventions.
   */
  @Test
  public void virtualThreadsHaveCustomPrefix() throws Exception {
    Executor executor = NexusForkJoinPoolFactory.createVirtualThreadExecutor(TEST_PREFIX);
    
    AtomicReference<String> threadName = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    executor.execute(() -> {
      threadName.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    
    assertThat(threadName.get(), containsString(TEST_PREFIX));
    assertThat(Thread.currentThread().isVirtual(), is(false)); // Main test thread is platform thread
  }
  
  /**
   * Verifies that the default virtual thread executor creates threads that are properly identified as virtual.
   */
  @Test
  public void defaultVirtualThreadExecutorCreatesVirtualThreads() throws Exception {
    Executor executor = NexusForkJoinPoolFactory.createVirtualThreadExecutor();
    
    AtomicReference<Boolean> isVirtual = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    executor.execute(() -> {
      isVirtual.set(Thread.currentThread().isVirtual());
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    
    assertThat(isVirtual.get(), is(true));
  }
  
  /**
   * Tests that ForkJoinPool instances can effectively manage both platform and virtual threads.
   * This test verifies that a ForkJoinPool created by the factory can execute tasks while
   * virtual threads are also running in the system.
   */
  @Test
  public void forkJoinPoolWorksWithVirtualThreads() throws Exception {
    // Create a ForkJoinPool using the factory
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool(TEST_PREFIX);
    
    // Create a virtual thread executor
    Executor virtualExecutor = NexusForkJoinPoolFactory.createVirtualThreadExecutor();
    
    // Start some virtual threads
    int virtualThreadCount = 10;
    CountDownLatch virtualThreadsLatch = new CountDownLatch(virtualThreadCount);
    
    for (int i = 0; i < virtualThreadCount; i++) {
      final int taskId = i;
      virtualExecutor.execute(() -> {
        try {
          // Simulate some I/O work
          Thread.sleep(100);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          virtualThreadsLatch.countDown();
        }
      });
    }
    
    // Submit tasks to the ForkJoinPool
    int platformTaskCount = 5;
    List<Future<String>> futures = new ArrayList<>();
    
    for (int i = 0; i < platformTaskCount; i++) {
      final int taskId = i;
      futures.add(forkJoinPool.submit(() -> {
        // Verify thread naming
        String threadName = Thread.currentThread().getName();
        assertThat(threadName, containsString(TEST_PREFIX));
        
        // Simulate some CPU work
        int result = 0;
        for (int j = 0; j < 1000; j++) {
          result += j;
        }
        
        return "Task " + taskId + " completed with result " + result;
      }));
    }
    
    // Verify all virtual threads completed
    assertThat(virtualThreadsLatch.await(5, TimeUnit.SECONDS), is(true));
    
    // Verify all platform thread tasks completed successfully
    for (Future<String> future : futures) {
      String result = future.get(5, TimeUnit.SECONDS);
      assertThat(result, notNullValue());
      assertThat(result, containsString("completed with result"));
    }
    
    // Clean up
    forkJoinPool.shutdown();
    assertThat(forkJoinPool.awaitTermination(5, TimeUnit.SECONDS), is(true));
  }
  
  /**
   * Tests structured concurrency patterns with Virtual Threads using Java 21's
   * StructuredTaskScope. This test verifies that multiple related tasks can be
   * executed concurrently and managed as a single unit of work.
   */
  @Test
  public void structuredConcurrencyWithVirtualThreads() throws Exception {
    // Create a virtual thread executor with custom naming
    Executor executor = NexusForkJoinPoolFactory.createVirtualThreadExecutor(TEST_PREFIX);
    
    // Use try-with-resources to ensure proper cleanup of the scope
    try (var scope = new java.util.concurrent.StructuredTaskScope.ShutdownOnFailure()) {
      // Fork multiple subtasks
      int taskCount = 5;
      List<java.util.concurrent.StructuredTaskScope.Subtask<Integer>> subtasks = new ArrayList<>();
      
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        subtasks.add(scope.fork(() -> {
          // Verify we're running in a virtual thread
          assertThat(Thread.currentThread().isVirtual(), is(true));
          
          // Simulate some work with varying duration
          Thread.sleep(50 * taskId);
          return taskId * 10;
        }));
      }
      
      // Wait for all subtasks to complete
      scope.join();
      
      // Ensure no exceptions occurred
      scope.throwIfFailed();
      
      // Verify results
      for (int i = 0; i < taskCount; i++) {
        assertThat(subtasks.get(i).get(), is(i * 10));
      }
    }
  }
  
  /**
   * Tests that the CPU-bound pool created by the factory is properly configured
   * for computational workloads in a Java 21 environment with Virtual Threads present.
   */
  @Test
  public void cpuBoundPoolConfiguration() throws Exception {
    // Create a CPU-bound pool
    ForkJoinPool cpuPool = NexusForkJoinPoolFactory.createCpuBoundPool(TEST_PREFIX);
    
    // Verify the pool has the expected parallelism (should match available processors)
    assertThat(cpuPool.getParallelism(), is(Runtime.getRuntime().availableProcessors()));
    
    // Create some virtual threads in the background
    Executor virtualExecutor = NexusForkJoinPoolFactory.createVirtualThreadExecutor();
    CountDownLatch virtualThreadsStarted = new CountDownLatch(5);
    
    for (int i = 0; i < 5; i++) {
      virtualExecutor.execute(() -> {
        virtualThreadsStarted.countDown();
        try {
          // Keep virtual threads alive during the test
          Thread.sleep(1000);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }
    
    // Wait for virtual threads to start
    assertThat(virtualThreadsStarted.await(5, TimeUnit.SECONDS), is(true));
    
    // Submit CPU-intensive work to the CPU pool
    Future<Long> result = cpuPool.submit(() -> {
      // Verify thread naming
      String threadName = Thread.currentThread().getName();
      assertThat(threadName, containsString(TEST_PREFIX));
      
      // Perform CPU-intensive calculation
      long sum = 0;
      for (long i = 0; i < 10_000_000; i++) {
        sum += i;
      }
      return sum;
    });
    
    // Verify the result
    long expectedSum = 49999995000000L; // Sum of numbers from 0 to 9,999,999
    assertThat(result.get(10, TimeUnit.SECONDS), is(expectedSum));
    
    // Clean up
    cpuPool.shutdown();
    assertThat(cpuPool.awaitTermination(5, TimeUnit.SECONDS), is(true));
  }
}