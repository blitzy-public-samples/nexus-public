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

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NexusForkJoinPoolFactory}.
 * 
 * @since 3.20
 */
public class NexusForkJoinPoolFactoryTest
{
  @Test
  void threadsHaveCustomPrefix() {
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createForkJoinPool("custom-prefix-test-");
    ForkJoinWorkerThread thread = forkJoinPool.getFactory().newThread(forkJoinPool);
    assertTrue(thread.getName().contains("custom-prefix-test-"), "Thread name should contain custom prefix");
  }
  
  @Test
  void createForkJoinPoolWithCustomParallelism() {
    int customParallelism = 4; // Use a specific parallelism value for testing
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createIoBoundPool("custom-parallelism-test-", customParallelism);
    
    assertEquals(customParallelism, forkJoinPool.getParallelism(), 
        "ForkJoinPool should be created with the specified parallelism");
    
    // Verify the pool is configured for FIFO mode (async mode = true) which is better for I/O operations
    assertTrue(forkJoinPool.getAsyncMode(), "I/O bound pool should use FIFO (async) mode");
  }
  
  @Test
  void cpuBoundPoolUsesLIFOMode() {
    ForkJoinPool forkJoinPool = NexusForkJoinPoolFactory.createCpuBoundPool("cpu-bound-test-");
    
    // Verify the pool is configured for LIFO mode (async mode = false) which is better for CPU-bound operations
    assertFalse(forkJoinPool.getAsyncMode(), "CPU bound pool should use LIFO (non-async) mode");
    
    // Verify parallelism matches available processors
    assertEquals(Runtime.getRuntime().availableProcessors(), forkJoinPool.getParallelism(),
        "CPU bound pool should use available processor count for parallelism");
  }
  
  @Tag("VirtualThreadTestGroup")
  @Test
  void virtualThreadExecutorCreatesVirtualThreads() throws Exception {
    Executor executor = NexusForkJoinPoolFactory.createVirtualThreadExecutor();
    AtomicBoolean executed = new AtomicBoolean(false);
    
    executor.execute(() -> {
      executed.set(true);
      assertTrue(Thread.currentThread().isVirtual(), "Should be running on a virtual thread");
    });
    
    // Give the virtual thread a moment to execute
    Thread.sleep(100);
    assertTrue(executed.get(), "Task should have been executed");
  }
  
  @Tag("VirtualThreadTestGroup")
  @Test
  void virtualThreadExecutorWithCustomNaming() throws Exception {
    String prefix = "custom-virtual-thread-";
    Executor executor = NexusForkJoinPoolFactory.createVirtualThreadExecutor(prefix);
    AtomicBoolean executed = new AtomicBoolean(false);
    
    executor.execute(() -> {
      executed.set(true);
      String threadName = Thread.currentThread().getName();
      assertTrue(threadName.startsWith(prefix), 
          "Thread name should start with custom prefix, but was: " + threadName);
      assertTrue(Thread.currentThread().isVirtual(), "Should be running on a virtual thread");
    });
    
    // Give the virtual thread a moment to execute
    Thread.sleep(100);
    assertTrue(executed.get(), "Task should have been executed");
  }
  
  @Test
  void optimalParallelismReturnsValidValue() {
    int parallelism = NexusForkJoinPoolFactory.getOptimalParallelism();
    assertTrue(parallelism > 0, "Optimal parallelism should be greater than zero");
  }
  
  @Tag("VirtualThreadTestGroup")
  @Test
  void platformThreadFactoryUsedForPinningOperations() throws Exception {
    // Create a thread factory that produces platform threads
    ThreadFactory platformThreadFactory = Thread.ofPlatform().name("platform-thread-", 0).factory();
    
    // Use the platform thread factory for an operation that might pin
    Thread thread = platformThreadFactory.newThread(() -> {
      // Simulate an operation that would pin a virtual thread
      synchronized (this) {
        try {
          wait(10); // This would pin a virtual thread
        }
        catch (InterruptedException e) {
          // Ignore
        }
      }
    });
    
    // Verify it's a platform thread, not a virtual thread
    assertFalse(thread.isVirtual(), "Should be a platform thread for pinning operations");
  }
}