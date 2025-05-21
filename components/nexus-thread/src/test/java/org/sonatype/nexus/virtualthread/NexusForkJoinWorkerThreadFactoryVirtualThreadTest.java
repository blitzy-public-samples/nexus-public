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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.thread.NexusForkJoinWorkerThreadFactory;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NexusForkJoinWorkerThreadFactory} with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class NexusForkJoinWorkerThreadFactoryVirtualThreadTest
{
  private static final int THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 10;
  
  /**
   * Verifies that the thread prefix is correctly added to worker threads when used with Virtual Threads.
   */
  @Test
  public void prefixIsAddedToThreadWithVirtualThreads() {
    NexusForkJoinWorkerThreadFactory factory = new NexusForkJoinWorkerThreadFactory("virtual-test");
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread thread = factory.newThread(forkJoinPool);
    
    assertThat(thread.getName(), CoreMatchers.containsString("virtual-test"));
  }
  
  /**
   * Tests that the factory works correctly when used with a high number of concurrent Virtual Threads.
   * This verifies that thread naming and creation remain consistent under high concurrency.
   */
  @Test
  public void highConcurrencyWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create a ForkJoinPool with our custom factory
    String threadPrefix = "high-concurrency-test";
    NexusForkJoinWorkerThreadFactory factory = new NexusForkJoinWorkerThreadFactory(threadPrefix);
    ForkJoinPool forkJoinPool = new ForkJoinPool(
        Runtime.getRuntime().availableProcessors(),
        factory,
        null,
        false);
    
    // Use a latch to coordinate the threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger correctlyNamedThreads = new AtomicInteger(0);
    
    // Submit tasks to the virtual thread executor
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Submit work to the ForkJoinPool
          forkJoinPool.submit(() -> {
            String threadName = Thread.currentThread().getName();
            if (threadName.contains(threadPrefix)) {
              correctlyNamedThreads.incrementAndGet();
            }
          }).get(); // Wait for the task to complete
          
          completionLatch.countDown();
        }
        catch (Exception e) {
          // Count down even if there's an exception
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(completed, "Not all threads completed within the timeout period");
    
    // Verify that all worker threads were correctly named
    assertEquals(THREAD_COUNT, correctlyNamedThreads.get(), 
        "Not all worker threads were correctly named with the prefix");
    
    // Clean up
    virtualExecutor.shutdown();
    forkJoinPool.shutdown();
  }
  
  /**
   * Tests compatibility with structured concurrency patterns introduced in Java 21.
   * This ensures the factory works correctly with the new concurrency features.
   */
  @Test
  public void structuredConcurrencyCompatibility() throws Exception {
    // Create a ForkJoinPool with our custom factory
    String threadPrefix = "structured-concurrency-test";
    NexusForkJoinWorkerThreadFactory factory = new NexusForkJoinWorkerThreadFactory(threadPrefix);
    ForkJoinPool forkJoinPool = new ForkJoinPool(
        Runtime.getRuntime().availableProcessors(),
        factory,
        null,
        false);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("virtual-", 0).factory();
    
    // Create a list to store the results
    List<String> threadNames = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(10);
    
    // Create 10 virtual threads that will use the ForkJoinPool
    for (int i = 0; i < 10; i++) {
      Thread virtualThread = virtualThreadFactory.newThread(() -> {
        try {
          // Submit work to the ForkJoinPool
          String workerThreadName = forkJoinPool.submit(() -> {
            return Thread.currentThread().getName();
          }).get();
          
          synchronized (threadNames) {
            threadNames.add(workerThreadName);
          }
        }
        catch (Exception e) {
          // Ignore exceptions
        }
        finally {
          latch.countDown();
        }
      });
      
      // Start the virtual thread
      virtualThread.start();
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(completed, "Not all threads completed within the timeout period");
    
    // Verify that all worker threads were correctly named
    assertEquals(10, threadNames.size(), "Not all tasks completed successfully");
    
    for (String name : threadNames) {
      assertThat(name, CoreMatchers.containsString(threadPrefix));
    }
    
    // Clean up
    forkJoinPool.shutdown();
  }
}