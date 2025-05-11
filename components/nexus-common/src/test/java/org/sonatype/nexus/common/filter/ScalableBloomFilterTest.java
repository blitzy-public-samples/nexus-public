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
package org.sonatype.nexus.common.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;

import static com.google.common.hash.Funnels.stringFunnel;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ScalableBloomFilter} to verify its functionality for identifying duplicates
 * and maintaining expected false positive probability.
 * 
 * Updated for Java 21 compatibility with JUnit Jupiter and virtual thread testing.
 */
class ScalableBloomFilterTest
    extends TestSupport
{
  private static final double FALSE_POSITIVE_PROBABILITY = 10e-19;

  /**
   * Verifies that the filter correctly identifies duplicates.
   */
  @Test
  void shouldIdentifyDuplicates() {
    List<String> added = new ArrayList<>();

    ScalableBloomFilter<String> uniqueFilter = buildFilter();
    for (int i = 1; i <= 10; i++) {
      String value = randomUUID().toString();
      added.add(value);
      assertFalse(uniqueFilter.mightContain(value), "Filter should not contain newly generated value");
      assertTrue(uniqueFilter.put(value), "First insertion of a value should return true");
    }

    for (String value : added) {
      assertTrue(uniqueFilter.mightContain(value), "Filter should contain previously added value");
      assertFalse(uniqueFilter.put(value), "Second insertion of a value should return false");
    }
  }

  /**
   * Verifies that the filter maintains its expected false positive probability
   * even with a large number of entries.
   */
  @Test
  void shouldNotReturnFalsePositiveInFirstMillion() {
    ScalableBloomFilter<String> uniqueFilter = buildFilter();

    for (int i = 1; i <= 1000000; i++) {
      assertFalse(uniqueFilter.mightContain(randomUUID().toString()), 
          "Filter should not contain newly generated value");
      assertTrue(uniqueFilter.put(randomUUID().toString()), 
          "Insertion of a unique value should return true");
    }

    // 1,000,000 records for this configuration leads to a probability of ~1.6047675107709276E-19 for a false positive.
    assertThat("Expected false positive probability should be below threshold", 
        uniqueFilter.expectedFpp(), is(lessThan(10e-18)));
  }
  
  /**
   * Tests the filter's behavior under concurrent operations using Java 21 virtual threads.
   * This verifies that the filter maintains correctness when accessed by multiple threads simultaneously.
   */
  @Test
  void concurrentOperationsWithVirtualThreads() throws Exception {
    // Create a filter to be accessed concurrently
    ScalableBloomFilter<String> uniqueFilter = buildFilter();
    
    // Use virtual threads for concurrent operations
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Each thread adds its own unique value
            String value = "value-" + taskId + "-" + randomUUID().toString();
            
            // First check should return false (not present)
            if (!uniqueFilter.mightContain(value)) {
              // Then add it to the filter
              if (uniqueFilter.put(value)) {
                // Finally verify it was added
                if (uniqueFilter.mightContain(value)) {
                  successCount.incrementAndGet();
                }
              }
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with timeout for safety)
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
      
      // Verify results - all operations should have succeeded
      assertThat("All concurrent operations should succeed", 
          successCount.get(), is(equalTo(taskCount)));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Creates a new ScalableBloomFilter with standard test parameters.
   */
  private ScalableBloomFilter<String> buildFilter() {
    return new ScalableBloomFilter<>(stringFunnel(UTF_8), 1000000, FALSE_POSITIVE_PROBABILITY);
  }
}
