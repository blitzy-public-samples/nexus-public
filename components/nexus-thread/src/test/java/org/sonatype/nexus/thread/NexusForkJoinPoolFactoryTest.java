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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NexusForkJoinPoolFactory}.
 * 
 * @since 3.20
 */
class NexusForkJoinPoolFactoryTest
{
  @Test
  void threadsHaveCustomPrefix() {
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("custom-prefix-test-");
    ForkJoinWorkerThread thread = forkJoinPool.getFactory().newThread(forkJoinPool);
    assertTrue(thread.getName().contains("custom-prefix-test-"), 
        "Thread name should contain the custom prefix");
  }
  
  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void forkJoinPoolExecutesTasksSuccessfully() {
    // Create a ForkJoinPool with a custom prefix
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("task-execution-test-");
    
    // Create a task that returns a result
    ForkJoinTask<String> task = forkJoinPool.submit(() -> "Task completed successfully");
    
    // Verify the task completes and returns the expected result
    try {
      String result = task.get();
      assertEquals("Task completed successfully", result, "Task should complete with expected result");
    }
    catch (InterruptedException | ExecutionException e) {
      throw new AssertionError("Task execution failed", e);
    }
    finally {
      forkJoinPool.shutdown();
    }
  }
  
  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void virtualThreadsCanSubmitTasksToForkJoinPool() throws Exception {
    // Create a ForkJoinPool with a custom prefix
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("virtual-thread-test-");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("virtual-test-", 0).factory();
    
    // Use AtomicReference to capture the result from the virtual thread
    AtomicReference<String> resultRef = new AtomicReference<>();
    AtomicBoolean completed = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create and start a virtual thread that submits a task to the ForkJoinPool
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        // Submit a task to the ForkJoinPool from within a virtual thread
        String result = forkJoinPool.submit(() -> "Task submitted from virtual thread").get();
        resultRef.set(result);
        completed.set(true);
      }
      catch (Exception e) {
        resultRef.set("Error: " + e.getMessage());
      }
      finally {
        latch.countDown();
      }
    });
    
    // Start the virtual thread
    virtualThread.start();
    
    // Wait for the task to complete
    assertTrue(latch.await(3, TimeUnit.SECONDS), "Task should complete within timeout");
    
    // Verify the task completed successfully
    assertTrue(completed.get(), "Task should have completed");
    assertEquals("Task submitted from virtual thread", resultRef.get(), 
        "Task submitted from virtual thread should complete successfully");
    
    // Shutdown the pool
    forkJoinPool.shutdown();
  }
  
  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void forkJoinPoolWorksWithCompletableFuture() throws Exception {
    // Create a ForkJoinPool with a custom prefix
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("completable-future-test-");
    
    // Use CompletableFuture with the ForkJoinPool
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
      // Verify we're running in the expected thread
      String threadName = Thread.currentThread().getName();
      assertTrue(threadName.contains("completable-future-test-"), 
          "Thread name should contain the custom prefix, but was: " + threadName);
      return "CompletableFuture task completed";
    }, forkJoinPool);
    
    // Chain operations using modern Java syntax
    CompletableFuture<String> processedFuture = future
        .thenApply(result -> {
          // Pattern matching for instanceof (Java 21 feature)
          if (result instanceof String s && s.contains("completed")) {
            return "Processed: " + s;
          }
          return "Unexpected result";
        });
    
    // Get the final result
    String result = processedFuture.get(3, TimeUnit.SECONDS);
    assertEquals("Processed: CompletableFuture task completed", result, 
        "CompletableFuture chain should complete with expected result");
    
    // Shutdown the pool
    forkJoinPool.shutdown();
  }
  
  @Test
  void threadNamingSupportsVirtualThreads() {
    // Create a virtual thread with a name pattern similar to what ForkJoinPool would use
    Thread virtualThread = Thread.ofVirtual()
        .name("virtual-fjp-test-", 1)
        .unstarted(() -> {
          // Just a dummy runnable
        });
    
    // Verify the thread name contains the expected prefix
    String threadName = virtualThread.getName();
    assertNotNull(threadName, "Thread name should not be null");
    assertTrue(threadName.contains("virtual-fjp-test-"), 
        "Virtual thread name should contain the prefix, but was: " + threadName);
    
    // Verify the thread is actually a virtual thread
    assertTrue(virtualThread.isVirtual(), "Thread should be a virtual thread");
  }
}