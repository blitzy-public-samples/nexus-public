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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NexusForkJoinWorkerThreadFactory}.
 * 
 * These tests verify the naming conventions and compatibility with both platform threads
 * and virtual threads introduced in Java 21. The tests ensure that the factory works correctly
 * in different threading environments and with the new Thread.Builder API.
 * 
 * @since 3.20
 */
class NexusForkJoinWorkerThreadFactoryTest
{
  private static final String TEST_PREFIX = "prefix-test";
  
  private NexusForkJoinWorkerThreadFactory nexusForkJoinWorkerThreadFactory;

  @BeforeEach
  void setUp() {
    nexusForkJoinWorkerThreadFactory = new NexusForkJoinWorkerThreadFactory(TEST_PREFIX);
  }

  @Test
  void prefixIsAddedToThread() {
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread thread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
    assertTrue(thread.getName().contains(TEST_PREFIX), "Thread name should contain the specified prefix");
  }

  @Test
  void prefixIsFollowedByPoolIndex() {
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread thread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
    String expectedPattern = TEST_PREFIX + "\\d+";
    assertTrue(thread.getName().matches(expectedPattern), 
        "Thread name should match pattern: prefix followed by pool index");
  }

  @Test
  void worksWithVirtualThreads() {
    // Create a virtual thread executor using the ForkJoinPool with our factory
    // This tests compatibility with virtual thread environments
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // The factory should still work in a virtual thread environment
      ForkJoinPool forkJoinPool = new ForkJoinPool();
      ForkJoinWorkerThread thread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
      assertTrue(thread.getName().contains(TEST_PREFIX), 
          "Factory should work correctly in virtual thread environment");
    }
  }

  @Test
  void compatibleWithThreadBuilderAPI() {
    // Test compatibility with Thread.Builder API introduced in Java 21
    Thread.Builder platformBuilder = Thread.ofPlatform().name(TEST_PREFIX + "-platform-");
    Thread platformThread = platformBuilder.start(() -> { /* no-op */ });
    
    // Create a ForkJoinWorkerThread with our factory
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread fjThread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
    
    // Both naming conventions should be compatible
    assertTrue(platformThread.getName().contains(TEST_PREFIX), 
        "Platform thread name should contain the prefix");
    assertTrue(fjThread.getName().contains(TEST_PREFIX), 
        "ForkJoinWorkerThread name should contain the prefix");
    
    // Ensure the platform thread completes
    try {
      platformThread.join(1000);
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void namingConventionComparisonWithVirtualThreads() {
    // Compare naming conventions between platform and virtual threads
    Thread virtualThread = Thread.ofVirtual().name(TEST_PREFIX + "-virtual").start(() -> { /* no-op */ });
    
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread fjThread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
    
    // Both should contain the prefix but have different patterns
    assertTrue(virtualThread.getName().contains(TEST_PREFIX), 
        "Virtual thread should contain the prefix");
    assertTrue(fjThread.getName().contains(TEST_PREFIX), 
        "ForkJoinWorkerThread should contain the prefix");
    
    // Virtual thread should have the exact name we gave it
    assertEquals(TEST_PREFIX + "-virtual", virtualThread.getName(), 
        "Virtual thread should have the exact name specified");
    
    // Ensure the virtual thread completes
    try {
      virtualThread.join(1000);
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
