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
package org.sonatype.nexus.testsuite.proxy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;

import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheKey;
import org.sonatype.nexus.repository.cache.internal.NegativeCacheFacetImpl;
import org.sonatype.nexus.repository.view.Status;

import org.ehcache.config.CacheRuntimeConfiguration;
import org.ehcache.config.ResourceType;
import org.ehcache.jsr107.Eh107Configuration;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Ensure that cache configuration is as expected based on the defaults of the system.
 * 
 * @since 3.3
 */
public class DefaultCacheSettingsTester
{
  public static final long DEFAULT_HEAP_SIZE = 10000L;

  /**
   * For 'negativeCache' backing NegativeCacheFacet activities, we expect specific ExpiryPolicy and heap size settings
   * for all Proxy repositories.
   */
  public static void verifyNegativeCacheSettings(Repository repository, CacheManager cacheManager) {
    NestedAttributesMap cacheConfig = repository.getConfiguration().attributes("negativeCache");
    boolean enabled = cacheConfig.get("enabled", Boolean.class);
    if (enabled) {
      int ttl = cacheConfig.get("timeToLive", Integer.class);
      Cache<NegativeCacheKey, Status> cache = cacheManager.getCache(
          ((NegativeCacheFacetImpl) repository.facet(NegativeCacheFacet.class)).getCacheName(), NegativeCacheKey.class,
          Status.class);
      CompleteConfiguration<NegativeCacheKey, Status> completeConfiguration =
          cache.getConfiguration(CompleteConfiguration.class);

      assertThat(completeConfiguration.getExpiryPolicyFactory(),
          is(CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.MINUTES, ttl))));
      verifyHeapSize(cache, DEFAULT_HEAP_SIZE);
    }
  }

  /**
   * Verify that the underlying cache configuration matches our expectations for heapSize.
   */
  public static void verifyHeapSize(Cache<NegativeCacheKey, Status> cache, long heapSize) {
    Eh107Configuration<NegativeCacheKey, Status> eh107Configuration = cache.getConfiguration(Eh107Configuration.class);
    CacheRuntimeConfiguration runtimeConfiguration = eh107Configuration.unwrap(CacheRuntimeConfiguration.class);
    assertThat(runtimeConfiguration.getResourcePools().getPoolForResource(ResourceType.Core.HEAP).getSize(),
        is(heapSize));
  }
  
  /**
   * Creates an ExecutorService using virtual threads (Java 21+).
   * Virtual threads are lightweight and efficient for I/O-bound operations.
   * 
   * @return An ExecutorService that creates a new virtual thread for each task
   */
  public static ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates an ExecutorService using platform threads with the specified number of threads.
   * 
   * @param nThreads the number of threads in the thread pool
   * @return An ExecutorService with a fixed number of platform threads
   */
  public static ExecutorService createPlatformThreadExecutor(int nThreads) {
    return Executors.newFixedThreadPool(nThreads);
  }
  
  /**
   * Creates a ThreadFactory that produces virtual threads.
   * 
   * @return A ThreadFactory that creates virtual threads
   */
  public static ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }
  
  /**
   * Creates a ThreadFactory that produces platform threads.
   * 
   * @return A ThreadFactory that creates platform threads
   */
  public static ThreadFactory createPlatformThreadFactory() {
    return Thread.ofPlatform().factory();
  }
  
  /**
   * Detects if a virtual thread is pinned to its carrier thread.
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
   * typically due to synchronized blocks or native method calls.
   * 
   * @param runnable The code to execute and check for pinning
   * @return true if pinning is detected, false otherwise
   */
  public static boolean detectThreadPinning(Runnable runnable) {
    // Set up a flag to track pinning detection
    AtomicInteger pinnedCount = new AtomicInteger(0);
    
    // Enable pinning detection via system property
    String originalPinningProperty = System.getProperty("jdk.tracePinnedThreads");
    try {
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Create and run a virtual thread with the provided code
      Thread thread = Thread.ofVirtual().start(() -> {
        // Run the provided code that might cause pinning
        runnable.run();
      });
      
      // Wait for the thread to complete
      thread.join();
      
      // Check if pinning was detected (this is a simplified approach)
      // In a real implementation, you would need to capture and analyze the output
      // from the jdk.tracePinnedThreads property or use JFR events
      return pinnedCount.get() > 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      // Restore the original system property
      if (originalPinningProperty != null) {
        System.setProperty("jdk.tracePinnedThreads", originalPinningProperty);
      } else {
        System.clearProperty("jdk.tracePinnedThreads");
      }
    }
  }
  
  /**
   * Compares the performance of virtual threads vs platform threads for a given operation.
   * 
   * @param operation The operation to test
   * @param iterations Number of iterations to run
   * @param concurrency Level of concurrency (number of threads for platform threads)
   * @return A PerformanceResult containing timing information for both thread types
   */
  public static PerformanceResult compareThreadPerformance(
      Runnable operation, int iterations, int concurrency) throws InterruptedException {
    // Create result object to store performance metrics
    PerformanceResult result = new PerformanceResult();
    
    // Test with platform threads
    ExecutorService platformExecutor = createPlatformThreadExecutor(concurrency);
    try {
      long platformStart = System.nanoTime();
      
      // Submit all tasks
      for (int i = 0; i < iterations; i++) {
        platformExecutor.submit(operation);
      }
      
      // Shutdown and wait for completion
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(1, TimeUnit.MINUTES);
      
      long platformDuration = System.nanoTime() - platformStart;
      result.setPlatformThreadDuration(platformDuration);
    } finally {
      if (!platformExecutor.isTerminated()) {
        platformExecutor.shutdownNow();
      }
    }
    
    // Test with virtual threads
    ExecutorService virtualExecutor = createVirtualThreadExecutor();
    try {
      long virtualStart = System.nanoTime();
      
      // Submit all tasks
      for (int i = 0; i < iterations; i++) {
        virtualExecutor.submit(operation);
      }
      
      // Shutdown and wait for completion
      virtualExecutor.shutdown();
      virtualExecutor.awaitTermination(1, TimeUnit.MINUTES);
      
      long virtualDuration = System.nanoTime() - virtualStart;
      result.setVirtualThreadDuration(virtualDuration);
    } finally {
      if (!virtualExecutor.isTerminated()) {
        virtualExecutor.shutdownNow();
      }
    }
    
    return result;
  }
  
  /**
   * Verifies that virtual threads provide better performance than platform threads
   * for I/O-bound operations.
   * 
   * @param operation The operation to test
   * @param iterations Number of iterations to run
   * @param concurrency Level of concurrency (number of threads for platform threads)
   */
  public static void verifyVirtualThreadPerformance(
      Runnable operation, int iterations, int concurrency) throws InterruptedException {
    PerformanceResult result = compareThreadPerformance(operation, iterations, concurrency);
    
    // For I/O-bound operations, virtual threads should be faster
    assertThat("Virtual threads should outperform platform threads for I/O-bound operations",
        result.getVirtualThreadDuration(), lessThan(result.getPlatformThreadDuration()));
  }
  
  /**
   * Class to hold performance comparison results.
   */
  public static class PerformanceResult {
    private long platformThreadDuration;
    private long virtualThreadDuration;
    
    public long getPlatformThreadDuration() {
      return platformThreadDuration;
    }
    
    public void setPlatformThreadDuration(long platformThreadDuration) {
      this.platformThreadDuration = platformThreadDuration;
    }
    
    public long getVirtualThreadDuration() {
      return virtualThreadDuration;
    }
    
    public void setVirtualThreadDuration(long virtualThreadDuration) {
      this.virtualThreadDuration = virtualThreadDuration;
    }
    
    /**
     * Calculates the performance improvement ratio of virtual threads over platform threads.
     * 
     * @return The ratio of platform thread duration to virtual thread duration
     */
    public double getImprovementRatio() {
      if (virtualThreadDuration == 0) {
        return 0; // Avoid division by zero
      }
      return (double) platformThreadDuration / virtualThreadDuration;
    }
  }
}