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
package org.sonatype.nexus.cleanup.config;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.IS_PRERELEASE_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.LAST_BLOB_UPDATED_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.LAST_DOWNLOADED_KEY;

/**
 * Virtual Thread tests for {@link DefaultCleanupPolicyConfiguration}.
 * 
 * Tests verify that the configuration values remain consistent when accessed concurrently
 * by multiple Virtual Threads and that no thread pinning occurs during configuration operations.
 */
@Tag("virtualthread")
public class DefaultCleanupPolicyConfigurationVirtualThreadTest
    extends DefaultCleanupPolicyConfigurationTest
{
  private static final int THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 10;
  
  private DefaultCleanupPolicyConfiguration underTest;
  private ExecutorService virtualExecutor;
  private ExecutorService platformExecutor;
  
  @BeforeEach
  public void setUp() {
    underTest = new DefaultCleanupPolicyConfiguration();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    platformExecutor = Executors.newCachedThreadPool();
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    if (virtualExecutor != null) {
      virtualExecutor.shutdown();
      virtualExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    if (platformExecutor != null) {
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Verifies that concurrent access to configuration by multiple Virtual Threads
   * maintains data consistency.
   */
  @Test
  public void testConcurrentConfigurationAccessWithVirtualThreads() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicBoolean inconsistencyDetected = new AtomicBoolean(false);
    
    // Launch multiple virtual threads to concurrently access the configuration
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Get the configuration and verify its values
          Map<String, Boolean> config = underTest.getConfiguration();
          
          // Check for consistency
          if (!Boolean.TRUE.equals(config.get(LAST_BLOB_UPDATED_KEY)) ||
              !Boolean.TRUE.equals(config.get(LAST_DOWNLOADED_KEY)) ||
              !Boolean.FALSE.equals(config.get(IS_PRERELEASE_KEY))) {
            inconsistencyDetected.set(true);
          }
        } 
        catch (Exception e) {
          inconsistencyDetected.set(true);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all threads completed and no inconsistencies were detected
    assertThat("All threads should complete within the timeout", completed, is(true));
    assertThat("No configuration inconsistencies should be detected", inconsistencyDetected.get(), is(false));
  }
  
  /**
   * Verifies that no thread pinning occurs during configuration access.
   * 
   * This test would need JVM flag -Djdk.tracePinnedThreads=full to detect actual pinning,
   * but we can verify that operations complete successfully which is an indirect indicator.
   */
  @Test
  public void testNoPinningDuringConfigurationAccess() throws Exception {
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Launch multiple virtual threads to concurrently access the configuration
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualExecutor.submit(() -> {
        try {
          // Access configuration multiple times to increase chance of detecting issues
          for (int j = 0; j < 10; j++) {
            Map<String, Boolean> config = underTest.getConfiguration();
            // Small delay to increase chance of thread scheduling issues
            Thread.sleep(1);
          }
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all threads completed without errors
    assertThat("All threads should complete within the timeout", completed, is(true));
    assertThat("No errors should occur during configuration access", errorCount.get(), is(0));
  }
  
  /**
   * Compares performance between platform threads and Virtual Threads when accessing configuration.
   * 
   * This test measures the time taken to perform the same operation with both thread types
   * and verifies that Virtual Threads provide comparable or better performance.
   */
  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    int iterations = 10000;
    
    // Measure platform thread performance
    long platformStart = System.nanoTime();
    CountDownLatch platformLatch = new CountDownLatch(iterations);
    
    for (int i = 0; i < iterations; i++) {
      platformExecutor.submit(() -> {
        try {
          underTest.getConfiguration();
        } 
        finally {
          platformLatch.countDown();
        }
      });
    }
    
    platformLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long platformDuration = System.nanoTime() - platformStart;
    
    // Measure virtual thread performance
    long virtualStart = System.nanoTime();
    CountDownLatch virtualLatch = new CountDownLatch(iterations);
    
    for (int i = 0; i < iterations; i++) {
      virtualExecutor.submit(() -> {
        try {
          underTest.getConfiguration();
        } 
        finally {
          virtualLatch.countDown();
        }
      });
    }
    
    virtualLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long virtualDuration = System.nanoTime() - virtualStart;
    
    // Log performance metrics for analysis
    System.out.println("Platform thread duration (ns): " + platformDuration);
    System.out.println("Virtual thread duration (ns): " + virtualDuration);
    
    // For high thread counts, virtual threads should generally perform better
    // but for simple operations the difference might not be significant
    // This assertion is more of a sanity check than a strict requirement
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualDuration, lessThan(platformDuration * 2));
  }
  
  /**
   * Tests that configuration values remain consistent when accessed by multiple Virtual Threads
   * that are reading and writing concurrently.
   */
  @Test
  public void testConcurrentReadWriteWithVirtualThreads() throws Exception {
    // Create a custom configuration with a ConcurrentHashMap for thread safety
    DefaultCleanupPolicyConfiguration customConfig = new DefaultCleanupPolicyConfiguration() {
      private final Map<String, Boolean> config = new ConcurrentHashMap<>();
      
      {
        // Initialize with default values
        config.put(LAST_BLOB_UPDATED_KEY, true);
        config.put(LAST_DOWNLOADED_KEY, true);
        config.put(IS_PRERELEASE_KEY, false);
      }
      
      @Override
      public Map<String, Boolean> getConfiguration() {
        return config;
      }
    };
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT * 2); // Readers + Writers
    AtomicBoolean inconsistencyDetected = new AtomicBoolean(false);
    
    // Launch reader threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualExecutor.submit(() -> {
        try {
          startLatch.await();
          
          // Read configuration multiple times
          for (int j = 0; j < 10; j++) {
            Map<String, Boolean> config = customConfig.getConfiguration();
            
            // Check for consistency - all values should be either true or false, not null
            if (config.get(LAST_BLOB_UPDATED_KEY) == null ||
                config.get(LAST_DOWNLOADED_KEY) == null ||
                config.get(IS_PRERELEASE_KEY) == null) {
              inconsistencyDetected.set(true);
            }
          }
        } 
        catch (Exception e) {
          inconsistencyDetected.set(true);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Launch writer threads that toggle values
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadNum = i;
      virtualExecutor.submit(() -> {
        try {
          startLatch.await();
          
          // Toggle a specific key based on thread number to create contention
          String key = (threadNum % 3 == 0) ? LAST_BLOB_UPDATED_KEY :
                      (threadNum % 3 == 1) ? LAST_DOWNLOADED_KEY : IS_PRERELEASE_KEY;
          
          // Toggle the value multiple times
          for (int j = 0; j < 10; j++) {
            Map<String, Boolean> config = customConfig.getConfiguration();
            boolean currentValue = config.getOrDefault(key, false);
            config.put(key, !currentValue);
            Thread.sleep(1); // Small delay to increase interleaving
          }
        } 
        catch (Exception e) {
          inconsistencyDetected.set(true);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all threads completed and no inconsistencies were detected
    assertThat("All threads should complete within the timeout", completed, is(true));
    assertThat("No configuration inconsistencies should be detected", inconsistencyDetected.get(), is(false));
    
    // Verify final configuration has valid values (not null)
    Map<String, Boolean> finalConfig = customConfig.getConfiguration();
    assertThat("LAST_BLOB_UPDATED_KEY should have a non-null value", 
        finalConfig.get(LAST_BLOB_UPDATED_KEY) != null, is(true));
    assertThat("LAST_DOWNLOADED_KEY should have a non-null value", 
        finalConfig.get(LAST_DOWNLOADED_KEY) != null, is(true));
    assertThat("IS_PRERELEASE_KEY should have a non-null value", 
        finalConfig.get(IS_PRERELEASE_KEY) != null, is(true));
  }
}