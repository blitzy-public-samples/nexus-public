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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NexusForkJoinWorkerThreadFactory} with both platform and virtual thread compatibility.
 * 
 * @since 3.20
 */
public class NexusForkJoinWorkerThreadFactoryTest
{
  private NexusForkJoinWorkerThreadFactory nexusForkJoinWorkerThreadFactory;
  
  @BeforeEach
  void setUp() {
    nexusForkJoinWorkerThreadFactory = new NexusForkJoinWorkerThreadFactory("prefix-test");
  }

  @Test
  @DisplayName("Verify prefix is added to thread name with platform threads")
  void prefixIsAddedToThread() {
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread thread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
    
    assertNotNull(thread);
    assertTrue(thread.getName().contains("prefix-test"), 
        "Thread name should contain the specified prefix, but was: " + thread.getName());
  }
  
  @Test
  @DisplayName("Verify thread factory works with multiple threads")
  void multipleThreadsCreatedCorrectly() {
    ForkJoinPool forkJoinPool = new ForkJoinPool(4, nexusForkJoinWorkerThreadFactory, null, false);
    AtomicInteger counter = new AtomicInteger(0);
    
    // Submit multiple tasks to ensure multiple threads are created
    IntStream.range(0, 10).forEach(i -> {
      forkJoinPool.submit(() -> {
        counter.incrementAndGet();
        return null;
      });
    });
    
    // Wait for tasks to complete
    forkJoinPool.shutdown();
    while (!forkJoinPool.isTerminated()) {
      Thread.onSpinWait();
    }
    
    assertEquals(10, counter.get(), "All tasks should have been executed");
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("Verify compatibility with Java 21 thread execution models")
  void compatibilityWithJava21ThreadModels() {
    // Create a ForkJoinPool with our factory
    ForkJoinPool forkJoinPool = new ForkJoinPool(4, nexusForkJoinWorkerThreadFactory, null, false);
    AtomicInteger counter = new AtomicInteger(0);
    
    // Submit tasks that would typically benefit from virtual threads
    IntStream.range(0, 100).forEach(i -> {
      forkJoinPool.submit(() -> {
        // Simulate I/O operation that would benefit from virtual threads
        try {
          Thread.sleep(10); // Short sleep to simulate I/O
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        counter.incrementAndGet();
        return null;
      });
    });
    
    // Wait for tasks to complete
    forkJoinPool.shutdown();
    while (!forkJoinPool.isTerminated()) {
      Thread.onSpinWait();
    }
    
    assertEquals(100, counter.get(), "All tasks should have been executed with Java 21 thread model");
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("Verify thread naming with virtual thread context")
  void threadNamingWithVirtualThreadContext() {
    // Create a ForkJoinPool with our factory
    ForkJoinPool forkJoinPool = new ForkJoinPool(4, nexusForkJoinWorkerThreadFactory, null, false);
    
    // Submit a task and verify the thread name
    String[] threadName = new String[1];
    forkJoinPool.submit(() -> {
      threadName[0] = Thread.currentThread().getName();
      return null;
    }).join();
    
    assertNotNull(threadName[0], "Thread name should not be null");
    assertTrue(threadName[0].contains("prefix-test"), 
        "Thread name should contain the specified prefix, but was: " + threadName[0]);
  }
}
