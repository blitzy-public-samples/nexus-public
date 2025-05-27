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
package org.sonatype.nexus.extender.modules.virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.extender.modules.FeatureFlaggedIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;

/**
 * Tests the {@link FeatureFlaggedIndex} component's compatibility with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class FeatureFlaggedIndexVirtualThreadTest
    extends TestSupport
{
  private static final String FLAG_1 = "FeatureFlaggedIndexVirtualThreadTest_1";

  private static final String FLAG_2 = "FeatureFlaggedIndexVirtualThreadTest_2";
  
  private static final int VIRTUAL_THREAD_COUNT = 1000;
  
  private static final int PLATFORM_THREAD_COUNT = 100;
  
  @Mock
  Bundle mockBundle;

  @FeatureFlag(name = FLAG_1)
  @FeatureFlag(name = FLAG_2)
  private static final class TestClass {
  }
  
  @FeatureFlag(name = FLAG_1, inverse = true)
  private static final class TestInvertedClass {
  }
  
  @FeatureFlag(name = FLAG_1, inverse = true, enabledByDefault = true)
  private static final class TestInvertedEnabledByDefaultClass {
  }

  @BeforeEach
  public void setup() throws ClassNotFoundException {
    doReturn(TestClass.class).when(mockBundle).loadClass(nullable(String.class));
    System.clearProperty(FLAG_1);
    System.clearProperty(FLAG_2);
    assertThat(System.getProperty(FLAG_1), is((String) null));
    assertThat(System.getProperty(FLAG_2), is((String) null));
  }

  @AfterEach
  public void teardown() {
    System.clearProperty(FLAG_1);
    System.clearProperty(FLAG_2);
  }

  /**
   * Tests that feature flag checking works correctly with a single virtual thread.
   */
  @Test
  public void testBasicFeatureFlagCheckingWithVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Boolean> result = executor.submit(() -> {
        return !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
      });
      
      // Default behavior - no flags enabled means feature is disabled
      assertFalse(result.get(5, TimeUnit.SECONDS));
      
      // Enable all flags
      System.setProperty(FLAG_1, Boolean.toString(true));
      System.setProperty(FLAG_2, Boolean.toString(true));
      
      result = executor.submit(() -> {
        return !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
      });
      
      // With all flags enabled, feature should be enabled
      assertTrue(result.get(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Tests concurrent feature flag checking with many virtual threads to validate thread safety.
   */
  @Test
  public void testConcurrentFeatureFlagChecking() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Start with no flags enabled
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            boolean isDisabled = FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
            if (isDisabled) { // Expected to be disabled initially
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
        }));
      }
      
      // Release all threads at once
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      
      // All threads should have seen the feature as disabled
      assertThat(successCount.get(), is(VIRTUAL_THREAD_COUNT));
    }
  }

  /**
   * Tests that feature flag checking remains thread-safe when flags are changed during concurrent access.
   */
  @Test
  public void testConcurrentFeatureFlagChanges() throws Exception {
    AtomicBoolean keepRunning = new AtomicBoolean(true);
    ConcurrentHashMap<Boolean, AtomicInteger> results = new ConcurrentHashMap<>();
    results.put(Boolean.TRUE, new AtomicInteger(0));
    results.put(Boolean.FALSE, new AtomicInteger(0));
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Start threads that continuously check feature flags
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        futures.add(executor.submit(() -> {
          while (keepRunning.get()) {
            boolean isEnabled = !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
            results.get(isEnabled).incrementAndGet();
            Thread.yield(); // Allow other threads to run
          }
        }));
      }
      
      // Start a thread that toggles feature flags
      Future<?> togglerFuture = executor.submit(() -> {
        try {
          for (int i = 0; i < 10; i++) {
            // Enable all flags
            System.setProperty(FLAG_1, Boolean.toString(true));
            System.setProperty(FLAG_2, Boolean.toString(true));
            Thread.sleep(50);
            
            // Disable all flags
            System.clearProperty(FLAG_1);
            System.clearProperty(FLAG_2);
            Thread.sleep(50);
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          keepRunning.set(false);
        }
      });
      
      // Wait for toggler to finish
      togglerFuture.get(10, TimeUnit.SECONDS);
      
      // Wait for all checker threads to finish
      for (Future<?> future : futures) {
        future.get(5, TimeUnit.SECONDS);
      }
      
      // We should have seen both enabled and disabled states
      assertTrue(results.get(Boolean.TRUE).get() > 0, "Should have seen enabled state");
      assertTrue(results.get(Boolean.FALSE).get() > 0, "Should have seen disabled state");
      
      log.info("Feature flag check results - Enabled: {}, Disabled: {}", 
          results.get(Boolean.TRUE).get(), results.get(Boolean.FALSE).get());
    }
  }

  /**
   * Tests performance comparison between platform threads and virtual threads.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    // Enable all flags for this test
    System.setProperty(FLAG_1, Boolean.toString(true));
    System.setProperty(FLAG_2, Boolean.toString(true));
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(PLATFORM_THREAD_COUNT)) {
        runConcurrentFeatureFlagChecks(executor, PLATFORM_THREAD_COUNT);
      }
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        runConcurrentFeatureFlagChecks(executor, VIRTUAL_THREAD_COUNT);
      }
    });
    
    log.info("Performance comparison - Platform threads ({} threads): {} ms, Virtual threads ({} threads): {} ms", 
        PLATFORM_THREAD_COUNT, platformThreadTime, VIRTUAL_THREAD_COUNT, virtualThreadTime);
    
    // We're not making assertions about performance, just logging the results
    // Virtual threads should handle more concurrent operations with similar or better performance
  }

  /**
   * Tests for thread pinning during feature flag checking operations.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    CountDownLatch allThreadsStarted = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    CountDownLatch allThreadsCompleted = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Create a thread factory that detects carrier thread blocking
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("pinning-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Start many virtual threads that check feature flags
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            allThreadsStarted.countDown();
            
            // Record thread before operation
            Thread threadBefore = Thread.currentThread();
            long startTime = System.nanoTime();
            
            // Perform feature flag check
            FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
            
            // Check if operation took suspiciously long (potential pinning)
            long duration = System.nanoTime() - startTime;
            if (duration > TimeUnit.MILLISECONDS.toNanos(100)) {
              log.warn("Potential thread pinning detected: operation took {} ms", 
                  TimeUnit.NANOSECONDS.toMillis(duration));
              pinningDetected.set(true);
            }
            
            // Record thread after operation
            Thread threadAfter = Thread.currentThread();
            
            // If thread identity changed, that would be unusual and indicate potential issues
            if (threadBefore != threadAfter) {
              log.warn("Thread identity changed during operation");
              pinningDetected.set(true);
            }
          }
          finally {
            allThreadsCompleted.countDown();
          }
        });
      }
      
      // Wait for all threads to start and complete
      assertTrue(allThreadsStarted.await(5, TimeUnit.SECONDS), "Not all threads started");
      assertTrue(allThreadsCompleted.await(5, TimeUnit.SECONDS), "Not all threads completed");
      
      // We don't expect thread pinning with proper virtual thread implementation
      assertFalse(pinningDetected.get(), "Thread pinning detected during feature flag operations");
    }
  }
  
  /**
   * Tests that inverted feature flags work correctly with virtual threads.
   */
  @Test
  public void testInvertedFlagWithVirtualThreads() throws Exception {
    doReturn(TestInvertedClass.class).when(mockBundle).loadClass(nullable(String.class));
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Test with flag not set
      Future<Boolean> result = executor.submit(() -> 
          !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, ""));
      assertFalse(result.get(5, TimeUnit.SECONDS), "Feature should be disabled when flag not set");
      
      // Test with flag set to false
      System.setProperty(FLAG_1, Boolean.toString(false));
      result = executor.submit(() -> 
          !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, ""));
      assertTrue(result.get(5, TimeUnit.SECONDS), "Feature should be enabled when flag is false");
      
      // Test with flag set to true
      System.setProperty(FLAG_1, Boolean.toString(true));
      result = executor.submit(() -> 
          !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, ""));
      assertFalse(result.get(5, TimeUnit.SECONDS), "Feature should be disabled when flag is true");
    }
  }
  
  /**
   * Tests that inverted feature flags with enabledByDefault work correctly with virtual threads.
   */
  @Test
  public void testInvertedFlagEnabledByDefaultWithVirtualThreads() throws Exception {
    doReturn(TestInvertedEnabledByDefaultClass.class).when(mockBundle).loadClass(nullable(String.class));
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Test with flag not set
      Future<Boolean> result = executor.submit(() -> 
          !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, ""));
      assertTrue(result.get(5, TimeUnit.SECONDS), "Feature should be enabled when flag not set");
      
      // Test with flag set to false
      System.setProperty(FLAG_1, Boolean.toString(false));
      result = executor.submit(() -> 
          !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, ""));
      assertTrue(result.get(5, TimeUnit.SECONDS), "Feature should be enabled when flag is false");
      
      // Test with flag set to true
      System.setProperty(FLAG_1, Boolean.toString(true));
      result = executor.submit(() -> 
          !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, ""));
      assertFalse(result.get(5, TimeUnit.SECONDS), "Feature should be disabled when flag is true");
    }
  }
  
  /**
   * Tests that feature flag checking works correctly when virtual threads are under high contention.
   */
  @Test
  public void testFeatureFlagCheckingUnderContention() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a large number of virtual threads to create contention
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Enable all flags
      System.setProperty(FLAG_1, Boolean.toString(true));
      System.setProperty(FLAG_2, Boolean.toString(true));
      
      // Submit many tasks that will all start at the same time
      for (int i = 0; i < VIRTUAL_THREAD_COUNT * 2; i++) {
        futures.add(executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Perform feature flag check multiple times to increase contention
            for (int j = 0; j < 10; j++) {
              boolean isEnabled = !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
              if (isEnabled) { // Expected to be enabled
                successCount.incrementAndGet();
              }
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread under contention", e);
          }
        }));
      }
      
      // Release all threads at once to create maximum contention
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      
      // All feature flag checks should have succeeded
      assertThat(successCount.get(), is(VIRTUAL_THREAD_COUNT * 2 * 10));
    }
  }
  
  /**
   * Helper method to run concurrent feature flag checks using the provided executor.
   */
  private void runConcurrentFeatureFlagChecks(ExecutorService executor, int threadCount) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Submit tasks
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Perform feature flag check multiple times
          for (int j = 0; j < 100; j++) {
            FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
          }
        }
        catch (Exception e) {
          log.error("Error in concurrent feature flag check", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads at once
    startLatch.countDown();
    
    // Wait for all threads to complete
    await().atMost(Duration.ofSeconds(10)).until(() -> completionLatch.getCount() == 0);
  }
  
  /**
   * Helper method to measure execution time of a runnable in milliseconds.
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
}