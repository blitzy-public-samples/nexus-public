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
package org.sonatype.nexus.repository.maven.internal.filter;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Virtual Thread-specific test for {@link BloomFilterDuplicateDetectionStrategy} that validates
 * its behavior when executed with Java 21's Virtual Threads.
 * 
 * <p>This test ensures that the Bloom filter-based duplicate detection maintains correctness
 * and performance under high concurrency with lightweight threads, and identifies any thread
 * pinning issues that might affect scalability.</p>
 *
 * @since 3.60
 */
public class BloomFilterDuplicateDetectionStrategyTest
    extends DuplicateDetectionStrategyTestSupport
{
  private static final Logger log = LoggerFactory.getLogger(BloomFilterDuplicateDetectionStrategyTest.class);
  
  // Number of threads to use for high-volume testing
  private static final int HIGH_VOLUME_THREADS = 1000;
  
  // Number of operations for performance testing
  private static final int PERFORMANCE_TEST_OPERATIONS = 10000;
  
  /**
   * Basic test for duplicate detection functionality.
   * This test verifies that the strategy correctly identifies duplicates
   * using the standard test method from the parent class.
   */
  @Test
  public void shouldIdentifyDuplicates() throws Exception {
    verifyDuplicateDetection(new BloomFilterDuplicateDetectionStrategy());
  }
  
  /**
   * Tests duplicate detection with concurrent Virtual Threads.
   * This test verifies that the Bloom filter strategy correctly identifies
   * duplicates when accessed concurrently by multiple Virtual Threads.
   */
  @Test
  public void shouldIdentifyDuplicatesWithVirtualThreads() throws Exception {
    verifyDuplicateDetectionWithVirtualThreads(new BloomFilterDuplicateDetectionStrategy());
  }
  
  /**
   * Tests duplicate detection with a high volume of concurrent Virtual Threads.
   * This test verifies that the Bloom filter strategy maintains correctness
   * under high concurrency with thousands of Virtual Threads.
   */
  @Test
  public void shouldHandleHighVolumeConcurrentAccess() throws Exception {
    log.info(STR."Starting high volume test with \{HIGH_VOLUME_THREADS} virtual threads");
    verifyDuplicateDetectionWithVirtualThreads(
        new BloomFilterDuplicateDetectionStrategy(),
        HIGH_VOLUME_THREADS);
  }
  
  /**
   * Tests for thread pinning issues in the Bloom filter implementation.
   * Thread pinning occurs when a Virtual Thread cannot unmount from its carrier thread,
   * typically due to synchronized blocks or native methods, reducing the benefits of Virtual Threads.
   */
  @Test
  public void shouldNotCauseThreadPinning() {
    BloomFilterDuplicateDetectionStrategy strategy = new BloomFilterDuplicateDetectionStrategy();
    boolean pinningDetected = detectThreadPinning(strategy);
    
    assertFalse("Bloom filter implementation should not cause thread pinning", pinningDetected);
    
    try {
      strategy.close();
    }
    catch (Exception e) {
      log.error("Error closing strategy", e);
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads.
   * This test helps evaluate the performance benefits of using Virtual Threads
   * with the Bloom filter duplicate detection strategy.
   */
  @Test
  public void shouldPerformBetterWithVirtualThreads() {
    BloomFilterDuplicateDetectionStrategy strategy = new BloomFilterDuplicateDetectionStrategy();
    
    log.info(STR."Starting performance comparison with \{PERFORMANCE_TEST_OPERATIONS} operations");
    Duration timeSaved = compareThreadPerformance(strategy, PERFORMANCE_TEST_OPERATIONS);
    
    log.info(STR."Performance difference: \{timeSaved.toMillis()} ms");
    
    // Virtual threads should perform at least as well as platform threads for I/O-bound operations
    // A negative value would indicate platform threads performed better, which would be unexpected
    assertTrue("Virtual threads should perform at least as well as platform threads", 
        timeSaved.toMillis() >= 0);
    
    try {
      strategy.close();
    }
    catch (Exception e) {
      log.error("Error closing strategy", e);
    }
  }
  
  /**
   * Tests the behavior of the Bloom filter when accessed by a mix of platform and virtual threads.
   * This test verifies that the strategy works correctly in a mixed-thread environment.
   */
  @Test
  public void shouldWorkWithMixedThreadTypes() throws Exception {
    BloomFilterDuplicateDetectionStrategy strategy = new BloomFilterDuplicateDetectionStrategy();
    
    try {
      // Use both platform threads and virtual threads to access the strategy
      try (ExecutorService platformExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
           ExecutorService virtualExecutor = createVirtualThreadExecutor()) {
        
        // Add some records with platform threads
        platformExecutor.submit(() -> {
          try {
            for (int i = 0; i < 100; i++) {
              strategy.apply(buildRecord("platform", "artifact" + i, "1.0", "sources", "jar"));
            }
          }
          catch (Exception e) {
            log.error("Error in platform thread", e);
          }
        }).get(30, TimeUnit.SECONDS);
        
        // Verify duplicates with virtual threads
        virtualExecutor.submit(() -> {
          try {
            // These should be detected as duplicates
            for (int i = 0; i < 50; i++) {
              assertFalse("Should detect duplicate from platform thread",
                  strategy.apply(buildRecord("platform", "artifact" + i, "1.0", "sources", "jar")));
            }
            
            // These should be new records
            for (int i = 0; i < 100; i++) {
              assertTrue("Should accept new record in virtual thread",
                  strategy.apply(buildRecord("virtual", "artifact" + i, "1.0", "sources", "jar")));
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
        }).get(30, TimeUnit.SECONDS);
      }
    }
    finally {
      strategy.close();
    }
  }
}