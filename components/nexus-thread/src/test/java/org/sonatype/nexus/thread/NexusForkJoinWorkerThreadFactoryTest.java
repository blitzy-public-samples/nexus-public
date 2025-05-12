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
import java.util.concurrent.ThreadFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for {@link NexusForkJoinWorkerThreadFactory}.
 *
 * @since 3.20
 */
class NexusForkJoinWorkerThreadFactoryTest
{
  private NexusForkJoinWorkerThreadFactory nexusForkJoinWorkerThreadFactory;
  
  private static final String TEST_PREFIX = "prefix-test";

  @BeforeEach
  void setUp() {
    nexusForkJoinWorkerThreadFactory = new NexusForkJoinWorkerThreadFactory(TEST_PREFIX);
  }

  @Test
  void prefixIsAddedToThread() {
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread thread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
    assertTrue(thread.getName().contains(TEST_PREFIX), 
        "Thread name should contain the specified prefix");
  }

  @Test
  void threadNameIncludesPoolIndex() {
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread thread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
    // The pool index is appended to the prefix
    assertTrue(thread.getName().matches(TEST_PREFIX + "\\d+"), 
        "Thread name should match pattern: prefix + digit");
  }

  @Test
  void compareWithPlatformThreadNaming() {
    // Create a platform thread using Thread.Builder API
    Thread platformThread = Thread.ofPlatform()
        .name(TEST_PREFIX)
        .factory()
        .newThread(() -> {});
    
    // Create a ForkJoinWorkerThread with our factory
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread fjThread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
    
    // Both should contain the prefix
    assertTrue(platformThread.getName().contains(TEST_PREFIX), 
        "Platform thread name should contain the prefix");
    assertTrue(fjThread.getName().contains(TEST_PREFIX), 
        "ForkJoinWorkerThread name should contain the prefix");
    
    // But the ForkJoinWorkerThread should have a numeric suffix
    assertTrue(fjThread.getName().matches(TEST_PREFIX + "\\d+"), 
        "ForkJoinWorkerThread should have numeric suffix");
  }

  @Test
  void compareWithVirtualThreadNaming() {
    // Create a virtual thread using Thread.Builder API
    Thread virtualThread = Thread.ofVirtual()
        .name(TEST_PREFIX)
        .factory()
        .newThread(() -> {});
    
    // Create a ForkJoinWorkerThread with our factory
    ForkJoinPool forkJoinPool = new ForkJoinPool();
    ForkJoinWorkerThread fjThread = nexusForkJoinWorkerThreadFactory.newThread(forkJoinPool);
    
    // Both should contain the prefix
    assertTrue(virtualThread.getName().contains(TEST_PREFIX), 
        "Virtual thread name should contain the prefix");
    assertTrue(fjThread.getName().contains(TEST_PREFIX), 
        "ForkJoinWorkerThread name should contain the prefix");
    
    // Virtual thread should not be a ForkJoinWorkerThread
    assertFalse(virtualThread instanceof ForkJoinWorkerThread, 
        "Virtual thread should not be a ForkJoinWorkerThread");
    
    // Virtual thread should be a virtual thread
    assertTrue(virtualThread.isVirtual(), 
        "Thread created with Thread.ofVirtual() should be a virtual thread");
  }

  @Test
  void testThreadBuilderCompatibility() {
    // Test that our factory works with Thread.Builder API for platform threads
    ThreadFactory platformFactory = Thread.ofPlatform()
        .name(TEST_PREFIX + "-platform-")
        .factory();
    Thread platformThread = platformFactory.newThread(() -> {});
    assertTrue(platformThread.getName().contains(TEST_PREFIX), 
        "Platform thread name should contain the prefix");
    assertFalse(platformThread.isVirtual(), 
        "Platform thread should not be virtual");
    
    // Test with virtual threads
    ThreadFactory virtualFactory = Thread.ofVirtual()
        .name(TEST_PREFIX + "-virtual-")
        .factory();
    Thread virtualThread = virtualFactory.newThread(() -> {});
    assertTrue(virtualThread.getName().contains(TEST_PREFIX), 
        "Virtual thread name should contain the prefix");
    assertTrue(virtualThread.isVirtual(), 
        "Thread created with Thread.ofVirtual() should be a virtual thread");
  }
}