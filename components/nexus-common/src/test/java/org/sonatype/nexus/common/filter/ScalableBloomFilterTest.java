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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import static com.google.common.hash.Funnels.stringFunnel;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ScalableBloomFilter} with Java 21 compatibility.
 */
@Category(Java21TestGroup.class)
public class ScalableBloomFilterTest
    extends TestSupport
{
  private static final double FALSE_POSITIVE_PROBABILITY = 10e-19;

  @Test
  public void identifyDuplicates() {
    List<String> added = new ArrayList<>();

    ScalableBloomFilter<String> uniqueFilter = buildFilter();
    for (int i = 1; i <= 10; i++) {
      String value = randomUUID().toString();
      added.add(value);
      assertFalse(uniqueFilter.mightContain(value));
      assertTrue(uniqueFilter.put(value));
    }

    for (String value : added) {
      assertTrue(uniqueFilter.mightContain(value));
      assertFalse(uniqueFilter.put(value));
    }
  }

  @Test
  public void notReturnFalsePositiveInFirstMillion() {
    ScalableBloomFilter<String> uniqueFilter = buildFilter();

    for (int i = 1; i <= 1000000; i++) {
      assertFalse(uniqueFilter.mightContain(randomUUID().toString()));
      assertTrue(uniqueFilter.put(randomUUID().toString()));
    }

    // 1,000,000 records for this configuration leads to a probability of ~1.6047675107709276E-19 for a false positive.
    assertThat(uniqueFilter.expectedFpp(), is(lessThan(10e-18)));
  }
  
  @Test
  public void concurrentOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create a shared bloom filter
    ScalableBloomFilter<String> uniqueFilter = buildFilter();
    
    // Setup concurrent task execution
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger duplicateCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Generate a unique value based on task ID
            String value = "task-" + taskId + "-" + randomUUID();
            
            // First check should return false (not present)
            if (uniqueFilter.mightContain(value)) {
              duplicateCount.incrementAndGet();
            }
            
            // Add the value to the filter
            uniqueFilter.put(value);
            
            // Second check should return true (now present)
            if (!uniqueFilter.mightContain(value)) {
              duplicateCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue("All tasks should complete in time", latch.await(30, TimeUnit.SECONDS));
      
      // Verify no duplicates or missing values were detected
      assertThat("No errors should occur during concurrent operations", duplicateCount.get(), is(0));
      
      // Verify the expected false positive rate is still within bounds
      assertThat(uniqueFilter.expectedFpp(), is(lessThan(10e-18)));
    } finally {
      executor.shutdown();
    }
  }

  private ScalableBloomFilter<String> buildFilter() {
    return new ScalableBloomFilter<>(stringFunnel(UTF_8), 1000000, FALSE_POSITIVE_PROBABILITY);
  }
}