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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.thread.NexusForkJoinPoolFactory;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link NexusForkJoinPoolFactory} with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class NexusForkJoinPoolFactoryVirtualThreadTest
{
  private static final String TEST_PREFIX = "nexus-vt-test-";
  
  /**
   * Tests that virtual thread executor properly names threads.
   */
  @Test
  public void virtualThreadsHaveCustomPrefix() throws Exception {
    Executor executor = NexusForkJoinPoolFactory.createVirtualThreadExecutor(TEST_PREFIX);
    
    // Use a latch to wait for the thread to execute
    CountDownLatch latch = new CountDownLatch(1);
    
    // Use an array to capture the thread name from inside the virtual thread
    String[] threadName = new String[1];
    
    executor.execute(() -> {
      // Capture the current thread name
      threadName[0] = Thread.currentThread().getName();
      latch.countDown();
    });
    
    // Wait for the virtual thread to complete
    assertTrue("Virtual thread did not complete in time", latch.await(5, TimeUnit.SECONDS));
    
    // Verify the thread name contains our prefix
    assertThat(threadName[0], containsString(TEST_PREFIX));
  }
  
  /**
   * Tests that virtual threads are properly created and can execute tasks.
   */
  @Test
  public void virtualThreadsExecuteTasks() throws Exception {
    Executor executor = NexusForkJoinPoolFactory.createVirtualThreadExecutor();
    
    // Create a counter to track completed tasks
    AtomicInteger counter = new AtomicInteger(0);
    
    // Number of tasks to execute
    int taskCount = 100;
    
    // Use a latch to wait for all tasks to complete
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    // Submit tasks
    for (int i = 0; i < taskCount; i++) {
      executor.execute(() -> {
        counter.incrementAndGet();
        latch.countDown();
      });
    }
    
    // Wait for all tasks to complete
    assertTrue("Not all virtual threads completed in time", latch.await(5, TimeUnit.SECONDS));
    
    // Verify all tasks were executed
    assertThat(counter.get(), is(taskCount));
  }
  
  /**
   * Tests that ForkJoinPool can work with virtual threads for I/O-bound operations.
   */
  @Test
  public void forkJoinPoolWorksWithVirtualThreads() throws Exception {
    // Create an I/O-bound pool with higher parallelism
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createIoBoundPool(TEST_PREFIX, 16);
    
    // Create a virtual thread executor
    Executor virtualExecutor = NexusForkJoinPoolFactory.createVirtualThreadExecutor();
    
    // Number of tasks to execute
    int taskCount = 50;
    
    // Use a latch to wait for all tasks to complete
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    // Submit tasks that simulate I/O operations using both executors
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      
      // Alternate between ForkJoinPool and VirtualThreadExecutor
      if (i % 2 == 0) {
        // Use ForkJoinPool
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          simulateIoOperation(taskId);
          latch.countDown();
        }, forkJoinPool);
        futures.add(future);
      } else {
        // Use VirtualThreadExecutor
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          simulateIoOperation(taskId);
          latch.countDown();
        }, virtualExecutor);
        futures.add(future);
      }
    }
    
    // Wait for all tasks to complete
    assertTrue("Not all tasks completed in time", latch.await(10, TimeUnit.SECONDS));
    
    // Verify all futures completed without exceptions
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    allFutures.join(); // This will throw an exception if any future completed exceptionally
    
    // Shutdown the ForkJoinPool
    forkJoinPool.shutdown();
    assertTrue("ForkJoinPool did not terminate in time", forkJoinPool.awaitTermination(5, TimeUnit.SECONDS));
  }
  
  /**
   * Tests that the optimal parallelism is correctly determined.
   */
  @Test
  public void optimalParallelismIsCorrect() {
    int parallelism = NexusForkJoinPoolFactory.getOptimalParallelism();
    
    // Verify parallelism is positive and reasonable
    assertTrue("Parallelism should be positive", parallelism > 0);
    
    // It should be related to the available processors
    int availableProcessors = Runtime.getRuntime().availableProcessors();
    assertTrue("Parallelism should be related to available processors", 
        parallelism <= availableProcessors * 2); // Allow for some flexibility
  }
  
  /**
   * Tests structured concurrency patterns with virtual threads.
   */
  @Test
  public void structuredConcurrencyWithVirtualThreads() throws Exception {
    // Create a thread-per-task executor using virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Number of tasks to execute
      int taskCount = 100;
      
      // Submit tasks and collect futures
      List<CompletableFuture<Integer>> futures = new ArrayList<>();
      
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
          // Simulate some work
          try {
            Thread.sleep(10);
          } 
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return taskId * 2; // Return a result
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all futures to complete and collect results
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
      
      // This demonstrates structured concurrency - we wait for all child tasks before proceeding
      allFutures.join();
      
      // Verify all results
      for (int i = 0; i < taskCount; i++) {
        assertThat(futures.get(i).join(), is(i * 2));
      }
    } // ExecutorService is automatically closed here due to try-with-resources
  }
  
  /**
   * Simulates an I/O operation by sleeping for a short time.
   */
  private void simulateIoOperation(int taskId) {
    try {
      // Simulate I/O with different durations
      Thread.sleep(50 + (taskId % 5) * 10);
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}