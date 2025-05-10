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
package org.sonatype.nexus.logging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests to validate MDC (Mapped Diagnostic Context) context propagation with Java 21 Virtual Threads.
 * 
 * These tests ensure that thread-local diagnostic data is correctly maintained when using
 * the new lightweight threading model introduced in Java 21.
 */
public class VirtualThreadMDCTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadMDCTest.class);
  
  private static final String TEST_KEY = "testKey";
  private static final String TEST_VALUE = "testValue";
  private static final int THREAD_COUNT = 100;
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  
  @Before
  public void setUp() {
    // Clear MDC before each test
    MDC.clear();
  }
  
  @After
  public void tearDown() {
    // Clear MDC after each test
    MDC.clear();
  }
  
  /**
   * Tests basic MDC context propagation to a single Virtual Thread.
   */
  @Test
  public void testBasicMdcPropagationToVirtualThread() throws Exception {
    // Set MDC in the main thread
    MDC.put(TEST_KEY, TEST_VALUE);
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean mdcValueCorrect = new AtomicBoolean(false);
    
    // Start a virtual thread that checks MDC value
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Check if MDC value is correctly propagated
        String mdcValue = MDC.get(TEST_KEY);
        log.info("MDC value in virtual thread: {}", mdcValue);
        mdcValueCorrect.set(TEST_VALUE.equals(mdcValue));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertThat("Virtual thread did not complete in time", 
        latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    // Verify MDC value was correctly propagated
    assertThat("MDC value was not correctly propagated to virtual thread", 
        mdcValueCorrect.get(), is(true));
  }
  
  /**
   * Tests MDC context integrity across multiple Virtual Thread operations with yielding.
   * This simulates Virtual Threads being unmounted and remounted on carrier threads.
   */
  @Test
  public void testMdcContextIntegrityWithYielding() throws Exception {
    // Set MDC in the main thread
    MDC.put(TEST_KEY, TEST_VALUE);
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean allChecksSucceeded = new AtomicBoolean(true);
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Check MDC before yielding
        String beforeYield = MDC.get(TEST_KEY);
        log.info("MDC value before yield: {}", beforeYield);
        if (!TEST_VALUE.equals(beforeYield)) {
          allChecksSucceeded.set(false);
          log.error("MDC value incorrect before yield: {}", beforeYield);
        }
        
        // Yield to potentially switch carrier threads
        for (int i = 0; i < 5; i++) {
          Thread.yield();
          // Perform some blocking operation to force unmounting
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          
          // Check MDC after each yield
          String afterYield = MDC.get(TEST_KEY);
          log.info("MDC value after yield {}: {}", i, afterYield);
          if (!TEST_VALUE.equals(afterYield)) {
            allChecksSucceeded.set(false);
            log.error("MDC value incorrect after yield {}: {}", i, afterYield);
          }
        }
      } finally {
        latch.countDown();
      }
    });
    
    assertThat("Virtual thread did not complete in time", 
        latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    assertThat("MDC context integrity was not maintained across yields", 
        allChecksSucceeded.get(), is(true));
  }
  
  /**
   * Tests MDC context propagation in high concurrency scenarios with many Virtual Threads.
   */
  @Test
  public void testMdcPropagationWithHighConcurrency() throws Exception {
    // Create a latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Track success for each thread
    ConcurrentHashMap<Integer, Boolean> threadResults = new ConcurrentHashMap<>();
    
    // Create and start multiple virtual threads
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      final String threadValue = "thread-" + threadId;
      
      // Set unique MDC value for each thread
      MDC.put(TEST_KEY, threadValue);
      
      Thread virtualThread = Thread.ofVirtual().name("vt-" + threadId).start(() -> {
        try {
          // Check if MDC value is correctly propagated
          String mdcValue = MDC.get(TEST_KEY);
          log.info("Thread {}: MDC value = {}", threadId, mdcValue);
          threadResults.put(threadId, threadValue.equals(mdcValue));
        } finally {
          latch.countDown();
          // Clear MDC to avoid leaking to other threads
          MDC.clear();
        }
      });
      
      threads.add(virtualThread);
      
      // Clear MDC for the next thread
      MDC.clear();
    }
    
    // Wait for all threads to complete
    assertThat("Not all virtual threads completed in time", 
        latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    // Verify all threads had correct MDC values
    for (int i = 0; i < THREAD_COUNT; i++) {
      assertThat("Thread " + i + " did not have correct MDC value", 
          threadResults.get(i), is(true));
    }
  }
  
  /**
   * Tests that MDC context is properly cleared after Virtual Thread completion.
   */
  @Test
  public void testMdcContextClearedAfterCompletion() throws Exception {
    // Set MDC in the main thread
    MDC.put(TEST_KEY, TEST_VALUE);
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean mdcClearedProperly = new AtomicBoolean(false);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          // Verify MDC is propagated
          assertThat(MDC.get(TEST_KEY), is(TEST_VALUE));
          
          // Clear MDC in this thread
          MDC.clear();
          
          // Verify MDC is cleared
          assertThat(MDC.get(TEST_KEY), is(nullValue()));
          mdcClearedProperly.set(true);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for the virtual thread to complete
    assertThat("Virtual thread did not complete in time", 
        latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    // Verify MDC was properly cleared in the virtual thread
    assertThat("MDC was not properly cleared in virtual thread", 
        mdcClearedProperly.get(), is(true));
    
    // Verify MDC in main thread is still intact
    assertThat("MDC in main thread was unexpectedly modified", 
        MDC.get(TEST_KEY), is(TEST_VALUE));
  }
  
  /**
   * Tests interaction between platform threads and Virtual Threads for MDC propagation.
   */
  @Test
  public void testMdcPropagationBetweenPlatformAndVirtualThreads() throws Exception {
    // Set MDC in the main thread
    MDC.put(TEST_KEY, TEST_VALUE);
    
    CountDownLatch platformThreadLatch = new CountDownLatch(1);
    CountDownLatch virtualThreadLatch = new CountDownLatch(1);
    AtomicBoolean platformThreadMdcCorrect = new AtomicBoolean(false);
    AtomicBoolean virtualThreadMdcCorrect = new AtomicBoolean(false);
    
    // Start a platform thread
    Thread platformThread = Thread.ofPlatform().start(() -> {
      try {
        // Check MDC in platform thread
        String mdcValue = MDC.get(TEST_KEY);
        log.info("MDC value in platform thread: {}", mdcValue);
        platformThreadMdcCorrect.set(TEST_VALUE.equals(mdcValue));
        
        // Modify MDC in platform thread
        String platformValue = "platform-value";
        MDC.put(TEST_KEY, platformValue);
        
        // Start a virtual thread from the platform thread
        Thread virtualThread = Thread.ofVirtual().start(() -> {
          try {
            // Check MDC in virtual thread
            String vtMdcValue = MDC.get(TEST_KEY);
            log.info("MDC value in virtual thread (started from platform thread): {}", vtMdcValue);
            virtualThreadMdcCorrect.set(platformValue.equals(vtMdcValue));
          } finally {
            virtualThreadLatch.countDown();
          }
        });
        
        // Wait for virtual thread to complete
        try {
          virtualThread.join(TIMEOUT.toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      } finally {
        platformThreadLatch.countDown();
      }
    });
    
    // Wait for both threads to complete
    assertThat("Platform thread did not complete in time", 
        platformThreadLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    assertThat("Virtual thread did not complete in time", 
        virtualThreadLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    // Verify MDC propagation was correct
    assertThat("MDC value was not correctly propagated to platform thread", 
        platformThreadMdcCorrect.get(), is(true));
    assertThat("MDC value was not correctly propagated from platform thread to virtual thread", 
        virtualThreadMdcCorrect.get(), is(true));
  }
  
  /**
   * Tests that MDC context is properly maintained when virtual threads are pinned to carrier threads.
   * This simulates scenarios where virtual threads might be pinned during blocking operations.
   */
  @Test
  public void testMdcContextWithPinnedVirtualThreads() throws Exception {
    // Set MDC in the main thread
    MDC.put(TEST_KEY, TEST_VALUE);
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean mdcMaintained = new AtomicBoolean(true);
    
    // Create a synchronized object to force pinning
    final Object lock = new Object();
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Check initial MDC value
        String initialValue = MDC.get(TEST_KEY);
        if (!TEST_VALUE.equals(initialValue)) {
          mdcMaintained.set(false);
          log.error("Initial MDC value incorrect: {}", initialValue);
        }
        
        // Enter synchronized block to pin the virtual thread
        synchronized (lock) {
          // Check MDC while pinned
          String pinnedValue = MDC.get(TEST_KEY);
          log.info("MDC value while pinned: {}", pinnedValue);
          if (!TEST_VALUE.equals(pinnedValue)) {
            mdcMaintained.set(false);
            log.error("MDC value incorrect while pinned: {}", pinnedValue);
          }
          
          // Sleep while holding the lock to ensure pinning
          try {
            Thread.sleep(100);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        
        // Check MDC after unpinning
        String afterValue = MDC.get(TEST_KEY);
        log.info("MDC value after unpinning: {}", afterValue);
        if (!TEST_VALUE.equals(afterValue)) {
          mdcMaintained.set(false);
          log.error("MDC value incorrect after unpinning: {}", afterValue);
        }
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertThat("Virtual thread did not complete in time", 
        latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    // Verify MDC context was maintained throughout pinning
    assertThat("MDC context was not maintained during virtual thread pinning", 
        mdcMaintained.get(), is(true));
  }
}