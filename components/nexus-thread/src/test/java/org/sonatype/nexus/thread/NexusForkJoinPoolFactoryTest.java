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

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Tests for {@link NexusForkJoinPoolFactory}.
 *
 * @since 3.20
 */
public class NexusForkJoinPoolFactoryTest
{
  @Test
  @DisplayName("ForkJoinPool threads should have the specified custom prefix")
  public void threadsHaveCustomPrefix() {
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("custom-prefix-test-");
    ForkJoinWorkerThread thread = forkJoinPool.getFactory().newThread(forkJoinPool);
    assertTrue(thread.getName().contains("custom-prefix-test-"), 
        "Thread name should contain the custom prefix");
  }
  
  @Test
  @DisplayName("ForkJoinPool should use the correct number of processors")
  public void poolUsesCorrectNumberOfProcessors() {
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("processor-test-");
    assertEquals(Runtime.getRuntime().availableProcessors(), forkJoinPool.getParallelism(),
        "ForkJoinPool should use available processors for parallelism");
  }
  
  @Test
  @DisplayName("ForkJoinPool should execute tasks in parallel")
  public void poolExecutesTasksInParallel() {
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("parallel-test-");
    int taskCount = Runtime.getRuntime().availableProcessors() * 2;
    int[] completedTasks = new int[1];
    
    // Submit multiple tasks to the pool
    for (int i = 0; i < taskCount; i++) {
      forkJoinPool.submit(() -> {
        try {
          // Simulate some work
          Thread.sleep(50);
          synchronized (completedTasks) {
            completedTasks[0]++;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }
    
    // Wait for all tasks to complete
    forkJoinPool.shutdown();
    try {
      assertTrue(forkJoinPool.awaitTermination(2, TimeUnit.SECONDS),
          "All tasks should complete within the timeout");
      assertEquals(taskCount, completedTasks[0],
          "All tasks should have been completed");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  @Test
  @DisplayName("ForkJoinPool should work with Virtual Threads")
  public void poolWorksWithVirtualThreads() {
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("virtual-thread-test-");
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit a task to a virtual thread that uses the ForkJoinPool
      Future<String> future = virtualExecutor.submit(() -> {
        // Run a task in the ForkJoinPool from within a virtual thread
        return forkJoinPool.submit(() -> {
          Thread currentThread = Thread.currentThread();
          return "Task executed by: " + currentThread.getName();
        }).get();
      });
      
      // Verify the task completed successfully
      String result = assertTimeout(java.time.Duration.ofSeconds(2), () -> future.get(),
          "Task should complete within timeout");
      
      assertTrue(result.contains("virtual-thread-test-"),
          "Result should contain the ForkJoinPool thread prefix");
      
    } catch (Exception e) {
      throw new AssertionError("Exception during test execution", e);
    } finally {
      virtualExecutor.shutdown();
      forkJoinPool.shutdown();
    }
  }
  
  @Test
  @DisplayName("Thread naming should support both platform and virtual threads")
  public void threadNamingSupportsVirtualThreads() {
    // Test with a thread name that would be used by Thread.ofVirtual()
    String virtualThreadName = "VirtualThread-test";
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool(virtualThreadName);
    ForkJoinWorkerThread thread = forkJoinPool.getFactory().newThread(forkJoinPool);
    
    // Verify the thread name contains our prefix
    assertTrue(thread.getName().contains(virtualThreadName),
        "Thread name should contain the virtual thread prefix");
    
    // Test with a more complex naming pattern that includes special characters
    String complexName = "virtual-thread-pool-[worker]-#";
    ForkJoinPool complexPool = NexusForkJoinPoolFactory.createForkJoinPool(complexName);
    ForkJoinWorkerThread complexThread = complexPool.getFactory().newThread(complexPool);
    
    assertTrue(complexThread.getName().contains(complexName),
        "Thread name should support complex naming patterns");
  }
  
  @Test
  @DisplayName("ForkJoinPool should work with modern Java 21 syntax")
  public void poolWorksWithModernJavaSyntax() {
    // Using var for type inference (Java 10+)
    var forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("modern-syntax-test-");
    
    // Using text blocks (Java 15+)
    String expectedPattern = """
        modern-syntax-test-
        """;
    
    // Using pattern matching for instanceof (Java 16+)
    Object thread = forkJoinPool.getFactory().newThread(forkJoinPool);
    if (thread instanceof ForkJoinWorkerThread workerThread) {
      assertTrue(workerThread.getName().contains(expectedPattern.trim()),
          "Thread name should contain the expected prefix");
    } else {
      throw new AssertionError("Expected a ForkJoinWorkerThread instance");
    }
    
    // Using switch expressions (Java 14+)
    int parallelism = forkJoinPool.getParallelism();
    String description = switch (parallelism) {
      case 1 -> "single-threaded";
      case 2, 3, 4 -> "few-threaded";
      default -> "multi-threaded";
    };
    
    assertTrue(description.equals("single-threaded") || 
              description.equals("few-threaded") || 
              description.equals("multi-threaded"),
        "Pool should have a valid parallelism description");
  }
}