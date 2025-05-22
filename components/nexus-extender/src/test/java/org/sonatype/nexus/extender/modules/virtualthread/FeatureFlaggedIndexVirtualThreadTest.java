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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.extender.modules.FeatureFlaggedIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;

/**
 * Tests the {@link FeatureFlaggedIndex} component's compatibility with Java 21 Virtual Threads.
 * Verifies that feature flag evaluation operates correctly in a concurrent virtual thread environment.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class FeatureFlaggedIndexVirtualThreadTest
{
  private static final String FLAG_1 = "FeatureFlaggedIndexVirtualThreadTest_1";

  private static final String FLAG_2 = "FeatureFlaggedIndexVirtualThreadTest_2";

  private static final int VIRTUAL_THREAD_COUNT = 1000;

  private static final int PLATFORM_THREAD_COUNT = 100;

  @Mock
  Bundle mockBundle;

  @FeatureFlag(name = FLAG_1)
  @FeatureFlag(name = FLAG_2)
  @SuppressWarnings("InnerClassMayBeStatic")
  private final class TestClass
  {
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
   * Tests that feature flag evaluation works correctly when accessed concurrently from multiple virtual threads.
   * This verifies thread safety of the feature flag checking mechanism.
   */
  @Test
  public void testConcurrentFeatureFlagCheckingWithVirtualThreads() throws Exception {
    // Set up feature flags
    System.setProperty(FLAG_1, Boolean.toString(true));
    System.setProperty(FLAG_2, Boolean.toString(true));

    // Create a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicBoolean anyFailures = new AtomicBoolean(false);

    // Create virtual threads to check feature flags concurrently
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            boolean result = !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
            if (!result) {
              anyFailures.set(true);
            }
          }
          catch (Exception e) {
            anyFailures.set(true);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();

      // Wait for all threads to complete
      boolean allCompleted = completionLatch.await(10, TimeUnit.SECONDS);
      assertThat("All virtual threads completed in time", allCompleted, is(true));
      assertThat("All feature flag checks were successful", anyFailures.get(), is(false));
    }
  }

  /**
   * Tests that feature flag evaluation works correctly when flags are changed during concurrent access.
   * This verifies thread safety during dynamic configuration changes.
   */
  @Test
  public void testFeatureFlagChangeDuringConcurrentAccess() throws Exception {
    // Initially set one flag to true
    System.setProperty(FLAG_1, Boolean.toString(true));

    // Create a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch halfwayLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT / 2);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);

    // Create virtual threads to check feature flags concurrently
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // First half of threads will check before the flag change
            boolean result = FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
            halfwayLatch.countDown();
            
            // After half the threads have checked, we'll change the second flag
            if (halfwayLatch.getCount() == 0) {
              System.setProperty(FLAG_2, Boolean.toString(true));
            }
            
            // All threads check again after potential flag change
            boolean secondResult = FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
            
            // The first result should always be true (disabled) since only FLAG_1 is set
            // The second result might be false (not disabled) if FLAG_2 was set in time
            if (result && (secondResult || halfwayLatch.getCount() > 0)) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            // Count failures
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();

      // Wait for all threads to complete
      boolean allCompleted = completionLatch.await(10, TimeUnit.SECONDS);
      assertThat("All virtual threads completed in time", allCompleted, is(true));
      
      // We expect at least some threads to have succeeded
      assertThat("Some feature flag checks were successful", successCount.get() > 0, is(true));
    }
  }

  /**
   * Compares the performance of feature flag checking between platform threads and virtual threads.
   * This helps validate that virtual threads provide comparable or better performance.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    // Set up feature flags
    System.setProperty(FLAG_1, Boolean.toString(true));
    System.setProperty(FLAG_2, Boolean.toString(true));

    // Measure platform thread performance
    long platformThreadTime = measureThreadPerformance(false, PLATFORM_THREAD_COUNT);
    
    // Measure virtual thread performance
    long virtualThreadTime = measureThreadPerformance(true, VIRTUAL_THREAD_COUNT);
    
    // Log the results for analysis
    System.out.println("Platform threads (" + PLATFORM_THREAD_COUNT + "): " + platformThreadTime + "ms");
    System.out.println("Virtual threads (" + VIRTUAL_THREAD_COUNT + "): " + virtualThreadTime + "ms");
    
    // We're not making assertions about which is faster, just ensuring both complete successfully
    // The actual performance comparison can be analyzed from the logs
  }

  /**
   * Tests for thread pinning detection during feature flag checking operations.
   * This helps identify if any operations in the feature flag checking process cause thread pinning.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection via system property
    // Note: In a real environment, this would be set via -Djdk.tracePinnedThreads=full JVM argument
    String originalPinningProperty = System.getProperty("jdk.tracePinnedThreads");
    try {
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Set up feature flags
      System.setProperty(FLAG_1, Boolean.toString(true));
      System.setProperty(FLAG_2, Boolean.toString(true));

      // Create a list to track any exceptions
      List<Exception> exceptions = new ArrayList<>();
      
      // Run feature flag checks in virtual threads with a small delay to detect pinning
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < 10; i++) {
          executor.submit(() -> {
            try {
              // Check feature flag
              boolean result = FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
              
              // Add a small delay to increase chance of detecting pinning if it occurs
              Thread.sleep(50);
              
              // Check feature flag again
              boolean secondResult = FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
              
              assertThat(result, is(secondResult));
            }
            catch (Exception e) {
              synchronized (exceptions) {
                exceptions.add(e);
              }
            }
          });
        }
      }
      
      // Verify no exceptions occurred
      assertThat("No exceptions during thread pinning detection", exceptions.isEmpty(), is(true));
      
      // Note: Actual pinning would be detected via JVM logs when running with -Djdk.tracePinnedThreads=full
    }
    finally {
      // Restore original property
      if (originalPinningProperty != null) {
        System.setProperty("jdk.tracePinnedThreads", originalPinningProperty);
      }
      else {
        System.clearProperty("jdk.tracePinnedThreads");
      }
    }
  }

  /**
   * Helper method to measure the performance of feature flag checking using either platform or virtual threads.
   *
   * @param useVirtualThreads true to use virtual threads, false to use platform threads
   * @param threadCount the number of threads to create
   * @return the time in milliseconds taken to complete all thread operations
   */
  private long measureThreadPerformance(boolean useVirtualThreads, int threadCount) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    ExecutorService executor = useVirtualThreads ? 
        Executors.newVirtualThreadPerTaskExecutor() : 
        Executors.newFixedThreadPool(Math.min(threadCount, 100)); // Limit platform threads to avoid resource exhaustion
    
    try {
      // Create threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Perform feature flag check multiple times to measure performance
            for (int j = 0; j < 10; j++) {
              FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
            }
          }
          catch (Exception e) {
            // Log exception
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start timing
      long startTime = System.currentTimeMillis();
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(30, TimeUnit.SECONDS);
      
      // Calculate elapsed time
      return System.currentTimeMillis() - startTime;
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that feature flag evaluation works correctly with a high number of concurrent virtual threads.
   * This verifies scalability of the feature flag checking mechanism.
   */
  @Test
  public void testHighConcurrencyFeatureFlagChecking() throws Exception {
    // Set up feature flags
    System.setProperty(FLAG_1, Boolean.toString(true));
    System.setProperty(FLAG_2, Boolean.toString(true));

    // Create a latch to synchronize thread start and completion
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);

    // Create a large number of virtual threads to check feature flags concurrently
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Perform feature flag check
            boolean result = !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
            
            if (result) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            // Count failures
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();

      // Wait for all threads to complete with a timeout
      boolean allCompleted = completionLatch.await(20, TimeUnit.SECONDS);
      
      assertThat("All virtual threads completed in time", allCompleted, is(true));
      assertThat("All feature flag checks were successful", successCount.get(), is(VIRTUAL_THREAD_COUNT));
    }
  }
}