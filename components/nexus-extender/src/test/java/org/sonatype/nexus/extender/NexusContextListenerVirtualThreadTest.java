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
package org.sonatype.nexus.extender;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.osgi.framework.BundleContext;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link NexusContextListener} with Java 21 Virtual Threads.
 * 
 * Validates that the context listener properly handles concurrent feature flag checks 
 * with virtual threads, maintains thread context, and correctly manages the lifecycle 
 * phases when operating under a virtual thread execution environment.
 */
public class NexusContextListenerVirtualThreadTest
{
  private NexusContextListener underTest;

  @BeforeEach
  void setUp() {
    NexusBundleExtender bundleExtender = mock(NexusBundleExtender.class);
    when(bundleExtender.getBundleContext()).thenReturn(mock(BundleContext.class));
    underTest = new NexusContextListener(bundleExtender);
  }

  /**
   * Provides test data for feature flag tests.
   */
  static Collection<Object[]> featureFlagTestData() {
    return Arrays.asList(new Object[][]{
        // Format: installMode, flag, flagValue, edition, expectedResult
        {"oss,pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "COMMUNITY", true},
        {"oss,pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", true},
        {"oss,pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", true},
        {"oss,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", false},
        {"pro:featureFlag:foo.enabled", "foo.enabled", true, "OSS", false},
        {"pro:featureFlag:foo.enabled", "foo.enabled", true, "PRO", true},
        {"featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "OSS", true},
        {"featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "PRO", true},
        {"featureFlag:foo.enabled", "foo.enabled", false, "OSS", false},
        {"featureFlag:foo.enabled", "foo.enabled", false, "PRO", false}
    });
  }

  @ParameterizedTest(name = "{index}: installMode: {0}, flag: {1}, flagValue: {2}, edition: {3}, expected: {4}")
  @MethodSource("featureFlagTestData")
  @DisplayName("Test feature flag evaluation in virtual threads")
  void featureFlagEvaluationInVirtualThread(
      final String installMode,
      final String flag,
      final Boolean flagValue,
      final String edition,
      final boolean expected) throws Exception 
  {
    // Set up system property if flagValue is provided
    if (flagValue != null) {
      System.setProperty(flag, String.valueOf(flagValue));
    } else {
      System.clearProperty(flag);
    }

    // Create a virtual thread to run the feature flag check
    AtomicBoolean result = new AtomicBoolean();
    Thread virtualThread = Thread.ofVirtual().name("feature-flag-test").start(() -> {
      result.set(underTest.isFeatureFlagEnabled(edition, installMode));
    });

    // Wait for the virtual thread to complete
    virtualThread.join();

    // Verify the result matches the expected value
    assertThat(result.get(), is(expected));

    // Clean up system property
    System.clearProperty(flag);
  }

  @Test
  @DisplayName("Test concurrent feature flag checks with multiple virtual threads")
  void concurrentFeatureFlagChecksWithVirtualThreads() throws Exception {
    // Set up test data
    final String flag = "concurrent.test.flag";
    final String installMode = "featureFlag:enabledByDefault:" + flag;
    final String edition = "OSS";
    
    // Set the flag to true
    System.setProperty(flag, "true");

    // Number of concurrent threads to test with
    final int threadCount = 100;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final AtomicBoolean anyFailures = new AtomicBoolean(false);

    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().name("concurrent-test-" + i).start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Check the feature flag
          boolean enabled = underTest.isFeatureFlagEnabled(edition, installMode);
          
          // The flag should be enabled
          if (!enabled) {
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
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify no failures occurred
    assertFalse(anyFailures.get(), "All virtual threads should successfully check feature flags");
    
    // Clean up
    System.clearProperty(flag);
  }

  @Test
  @DisplayName("Test feature flag changes are visible to virtual threads")
  void featureFlagChangesVisibleToVirtualThreads() throws Exception {
    // Set up test data
    final String flag = "dynamic.test.flag";
    final String installMode = "featureFlag:" + flag;
    final String edition = "OSS";
    
    // Initially the flag is not set
    System.clearProperty(flag);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // First check - flag should be disabled (false)
      Future<Boolean> future1 = executor.submit(() -> underTest.isFeatureFlagEnabled(edition, installMode));
      assertFalse(future1.get(), "Flag should initially be disabled");

      // Set the flag to true
      System.setProperty(flag, "true");

      // Second check - flag should now be enabled (true)
      Future<Boolean> future2 = executor.submit(() -> underTest.isFeatureFlagEnabled(edition, installMode));
      assertTrue(future2.get(), "Flag should now be enabled");

      // Set the flag to false
      System.setProperty(flag, "false");

      // Third check - flag should now be disabled again (false)
      Future<Boolean> future3 = executor.submit(() -> underTest.isFeatureFlagEnabled(edition, installMode));
      assertFalse(future3.get(), "Flag should now be disabled again");
    }
    
    // Clean up
    System.clearProperty(flag);
  }

  @Test
  @DisplayName("Test multiple virtual threads with different edition settings")
  void multipleVirtualThreadsWithDifferentEditions() throws Exception {
    // Set up test data
    final String flag = "edition.test.flag";
    final String installMode = "pro:featureFlag:" + flag;
    
    // Set the flag to true
    System.setProperty(flag, "true");

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks for different editions
      Future<Boolean> ossFuture = executor.submit(() -> underTest.isFeatureFlagEnabled("OSS", installMode));
      Future<Boolean> proFuture = executor.submit(() -> underTest.isFeatureFlagEnabled("PRO", installMode));
      Future<Boolean> communityFuture = executor.submit(() -> underTest.isFeatureFlagEnabled("COMMUNITY", installMode));

      // Verify results
      assertFalse(ossFuture.get(), "OSS edition should not have the pro-only feature enabled");
      assertTrue(proFuture.get(), "PRO edition should have the pro-only feature enabled");
      assertFalse(communityFuture.get(), "COMMUNITY edition should not have the pro-only feature enabled");
    }
    
    // Clean up
    System.clearProperty(flag);
  }

  @Test
  @DisplayName("Test exception handling in virtual threads")
  void exceptionHandlingInVirtualThreads() {
    // Create a virtual thread that will throw an exception
    AtomicReference<Throwable> caughtException = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().name("exception-test").start(() -> {
      try {
        // Call with null parameters to trigger exception
        underTest.isFeatureFlagEnabled(null, null);
      }
      catch (Throwable t) {
        caughtException.set(t);
      }
    });

    // Wait for the virtual thread to complete
    assertDoesNotThrow(() -> virtualThread.join(), "Virtual thread should complete without throwing exceptions");
    
    // Verify an exception was caught in the virtual thread
    assertTrue(caughtException.get() != null, "An exception should have been caught in the virtual thread");
  }

  @Test
  @DisplayName("Test virtual thread with malformed feature flag strings")
  void malformedFeatureFlagStringsInVirtualThread() throws Exception {
    // Test data with malformed feature flag strings
    String[] malformedFlags = {
        "featureFlag:",
        "featureFlag:enabledByDefault:",
        "foo:featureFlag:enabledByDefault:",
        "fooFlag:enabledByDefault:foo.enabled",
        ""
    };

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String malformedFlag : malformedFlags) {
        // Submit task to check the malformed flag
        Future<Boolean> future = executor.submit(() -> underTest.isFeatureFlagEnabled("OSS", malformedFlag));
        
        // All malformed flags should return false
        assertFalse(future.get(), "Malformed flag '" + malformedFlag + "' should return false");
      }
    }
  }
}