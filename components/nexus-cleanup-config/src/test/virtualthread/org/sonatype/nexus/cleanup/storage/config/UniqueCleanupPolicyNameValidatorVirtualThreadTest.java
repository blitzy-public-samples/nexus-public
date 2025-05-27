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
package org.sonatype.nexus.cleanup.storage.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cleanup.storage.CleanupPolicyStorage;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

/**
 * Virtual thread test for {@link UniqueCleanupPolicyNameValidator} that verifies thread-safety,
 * performance, and correctness under Java 21's virtual thread execution model.
 * <p>
 * This test focuses on validating that database interactions through {@link CleanupPolicyStorage}
 * remain safe under high concurrency with virtual threads, particularly ensuring that no thread
 * pinning occurs during cleanup policy name validation operations.
 */
public class UniqueCleanupPolicyNameValidatorVirtualThreadTest
    extends TestSupport
{
  private UniqueCleanupPolicyNameValidator underTest;

  private static final String TEST_NAME = "test";
  private static final String TEST_NAME_PREFIX = "test-";
  private static final int CONCURRENT_THREADS = 1000;
  private static final int TIMEOUT_SECONDS = 10;

  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  @Before
  public void setUp() {
    underTest = new UniqueCleanupPolicyNameValidator(cleanupPolicyStorage);
  }

  /**
   * Tests that the validator correctly handles concurrent validation requests for a name
   * that doesn't exist in storage (should return true for all threads).
   */
  @Test
  public void concurrentValidationOfNonExistentName() throws Exception {
    when(cleanupPolicyStorage.exists(TEST_NAME)).thenReturn(false);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicInteger validResults = new AtomicInteger(0);
      
      // Submit concurrent validation tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            if (underTest.isValid(TEST_NAME, null)) {
              validResults.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertThat("All validation tasks should complete within timeout",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      
      // All validations should return true
      assertThat("All validations should return true for non-existent name",
          validResults.get(), is(CONCURRENT_THREADS));
    }
  }

  /**
   * Tests that the validator correctly handles concurrent validation requests for a name
   * that exists in storage (should return false for all threads).
   */
  @Test
  public void concurrentValidationOfExistingName() throws Exception {
    when(cleanupPolicyStorage.exists(TEST_NAME)).thenReturn(true);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicInteger invalidResults = new AtomicInteger(0);
      
      // Submit concurrent validation tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            if (!underTest.isValid(TEST_NAME, null)) {
              invalidResults.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertThat("All validation tasks should complete within timeout",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      
      // All validations should return false
      assertThat("All validations should return false for existing name",
          invalidResults.get(), is(CONCURRENT_THREADS));
    }
  }

  /**
   * Tests that the validator correctly handles concurrent validation requests for multiple
   * different policy names, ensuring consistent results across all threads.
   */
  @Test
  public void concurrentValidationOfMultipleNames() throws Exception {
    // Configure mock to return true for even-indexed names and false for odd-indexed names
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      String name = TEST_NAME_PREFIX + i;
      when(cleanupPolicyStorage.exists(name)).thenReturn(i % 2 != 0);
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      List<Boolean> results = new ArrayList<>(CONCURRENT_THREADS);
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        results.add(null); // Initialize with nulls
      }
      
      // Submit concurrent validation tasks with different names
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int index = i;
        final String name = TEST_NAME_PREFIX + index;
        
        executor.submit(() -> {
          try {
            boolean result = underTest.isValid(name, null);
            synchronized (results) {
              results.set(index, result);
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertThat("All validation tasks should complete within timeout",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      
      // Verify results - even indices should be valid (true), odd indices should be invalid (false)
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        boolean expected = i % 2 == 0; // Even indices should be valid
        assertThat("Validation result for " + TEST_NAME_PREFIX + i + " should be " + expected,
            results.get(i), is(expected));
      }
    }
  }

  /**
   * Tests that the validator can handle a high number of concurrent validation requests
   * without thread pinning or performance degradation.
   */
  @Test
  public void highConcurrencyValidation() throws Exception {
    final int HIGH_CONCURRENCY = 5000; // Test with 5000 concurrent threads
    when(cleanupPolicyStorage.exists(TEST_NAME)).thenReturn(false);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(HIGH_CONCURRENCY);
      AtomicInteger completedTasks = new AtomicInteger(0);
      
      // Submit a high number of concurrent validation tasks
      for (int i = 0; i < HIGH_CONCURRENCY; i++) {
        executor.submit(() -> {
          try {
            underTest.isValid(TEST_NAME, null);
            completedTasks.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a longer timeout
      assertThat("All high-concurrency validation tasks should complete within timeout",
          latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS), is(true));
      
      // All tasks should complete successfully
      assertThat("All validation tasks should complete successfully",
          completedTasks.get(), is(HIGH_CONCURRENCY));
    }
  }
}