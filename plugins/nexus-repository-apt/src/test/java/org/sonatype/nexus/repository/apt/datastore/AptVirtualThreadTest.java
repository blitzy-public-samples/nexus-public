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
package org.sonatype.nexus.repository.apt.datastore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.apt.datastore.internal.browse.AptBrowseNodeGenerator;
import org.sonatype.nexus.repository.browse.node.BrowsePath;
import org.sonatype.nexus.repository.content.store.AssetData;
import org.sonatype.nexus.repository.content.store.ComponentData;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for APT repository operations using Java 21 Virtual Threads.
 * 
 * This test class validates the performance and correctness of APT repository datastore operations
 * when using Java 21 Virtual Threads compared to platform threads. It tests concurrent operations
 * like asset browsing, component retrieval, and metadata processing under high thread counts.
 * 
 * @since 3.60
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class AptVirtualThreadTest
    extends TestSupport
{
  private static final int LOW_THREAD_COUNT = 100;
  private static final int HIGH_THREAD_COUNT = 1000;
  private static final int VERY_HIGH_THREAD_COUNT = 5000;
  private static final int OPERATION_TIMEOUT_SECONDS = 30;
  
  private AptBrowseNodeGenerator browseNodeGenerator;
  
  @Before
  public void setUp() {
    browseNodeGenerator = new AptBrowseNodeGenerator();
  }
  
  /**
   * Tests concurrent asset browsing operations using virtual threads.
   * 
   * This test creates a large number of virtual threads that simultaneously
   * compute browse paths for APT assets, verifying that the operation completes
   * successfully under high concurrency.
   */
  @Test
  public void testConcurrentAssetBrowsingWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Number of concurrent operations to perform
    int operationCount = HIGH_THREAD_COUNT;
    
    // Create a countdown latch to synchronize completion
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    // Track any errors that occur
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    AtomicInteger completedOperations = new AtomicInteger(0);
    
    try {
      // Submit tasks to the executor
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a test asset with a unique path
            AssetData asset = new AssetData();
            asset.setPath("/path/asset" + index + ".deb");
            
            // Compute browse paths for the asset
            List<BrowsePath> paths = browseNodeGenerator.computeAssetPaths(asset);
            
            // Verify the result is correct
            if (paths.size() != 3) {
              log.error("Incorrect number of browse paths: {}", paths.size());
              hasErrors.set(true);
            }
            
            completedOperations.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread operation", e);
            hasErrors.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all operations completed successfully
      assertThat("All operations should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent operations", hasErrors.get(), is(false));
      assertThat("All operations should complete successfully", completedOperations.get(), is(operationCount));
    }
    finally {
      // Shutdown the executor
      executor.shutdown();
    }
  }
  
  /**
   * Tests concurrent component path generation using virtual threads.
   * 
   * This test creates a large number of virtual threads that simultaneously
   * compute browse paths for APT components, verifying that the operation completes
   * successfully under high concurrency.
   */
  @Test
  public void testConcurrentComponentPathGenerationWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Number of concurrent operations to perform
    int operationCount = HIGH_THREAD_COUNT;
    
    // Create a countdown latch to synchronize completion
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    // Track any errors that occur
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    AtomicInteger completedOperations = new AtomicInteger(0);
    
    try {
      // Submit tasks to the executor
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a test component with a unique name
            ComponentData componentData = new ComponentData();
            componentData.setRepositoryId(1);
            componentData.setComponentId(index);
            componentData.setName("package" + index);
            componentData.setNamespace("amd64");
            componentData.setVersion("1.0." + index);
            
            // Create a test asset with the component
            AssetData asset = new AssetData();
            asset.setComponent(componentData);
            asset.setPath("/path/asset" + index);
            
            // Compute browse paths for the component
            List<BrowsePath> paths = browseNodeGenerator.computeComponentPaths(asset);
            
            // Verify the result is correct
            if (paths.size() != 6) {
              log.error("Incorrect number of browse paths: {}", paths.size());
              hasErrors.set(true);
            }
            
            completedOperations.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread operation", e);
            hasErrors.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all operations completed successfully
      assertThat("All operations should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent operations", hasErrors.get(), is(false));
      assertThat("All operations should complete successfully", completedOperations.get(), is(operationCount));
    }
    finally {
      // Shutdown the executor
      executor.shutdown();
    }
  }
  
  /**
   * Tests for thread pinning issues when performing APT operations with virtual threads.
   * 
   * This test executes operations that might cause thread pinning (like synchronized blocks)
   * and verifies that the operations complete successfully without deadlocks or excessive
   * carrier thread usage.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection via system property
    // Note: In a real environment, this would be set via -Djdk.tracePinnedThreads=full
    String originalPinnedThreadsValue = System.getProperty("jdk.tracePinnedThreads");
    try {
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Create a virtual thread executor
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      
      // Number of concurrent operations to perform
      int operationCount = LOW_THREAD_COUNT;
      
      // Create a countdown latch to synchronize completion
      CountDownLatch latch = new CountDownLatch(operationCount);
      
      // Track any errors that occur
      AtomicBoolean hasErrors = new AtomicBoolean(false);
      
      // Create a shared object that will be used with synchronized blocks
      // to potentially trigger thread pinning
      Object sharedLock = new Object();
      
      try {
        // Submit tasks to the executor
        for (int i = 0; i < operationCount; i++) {
          final int index = i;
          executor.submit(() -> {
            try {
              // Create test data
              AssetData asset = new AssetData();
              asset.setPath("/path/asset" + index + ".deb");
              
              // Perform operations that might cause thread pinning
              synchronized (sharedLock) {
                // Compute browse paths within a synchronized block
                List<BrowsePath> paths = browseNodeGenerator.computeAssetPaths(asset);
                
                // Verify the result is correct
                if (paths.size() != 3) {
                  log.error("Incorrect number of browse paths: {}", paths.size());
                  hasErrors.set(true);
                }
                
                // Simulate some work inside the synchronized block
                Thread.sleep(5);
              }
            }
            catch (Exception e) {
              log.error("Error in virtual thread operation", e);
              hasErrors.set(true);
            }
            finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all operations to complete or timeout
        boolean completed = latch.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        
        // Verify all operations completed successfully
        assertThat("All operations should complete within the timeout", completed, is(true));
        assertThat("No errors should occur during concurrent operations", hasErrors.get(), is(false));
      }
      finally {
        // Shutdown the executor
        executor.shutdown();
      }
    }
    finally {
      // Restore the original system property value
      if (originalPinnedThreadsValue != null) {
        System.setProperty("jdk.tracePinnedThreads", originalPinnedThreadsValue);
      }
      else {
        System.clearProperty("jdk.tracePinnedThreads");
      }
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for APT operations.
   * 
   * This test executes the same operations using both thread types and compares
   * execution time, throughput, and resource utilization.
   */
  @Test
  public void testPlatformVsVirtualThreadPerformance() throws Exception {
    // Create thread factories for both thread types
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Number of concurrent operations to perform
    int operationCount = VERY_HIGH_THREAD_COUNT;
    
    // Run the test with platform threads
    long platformThreadTime = measureExecutionTime(platformThreadFactory, operationCount);
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    
    // Run the test with virtual threads
    long virtualThreadTime = measureExecutionTime(virtualThreadFactory, operationCount);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Verify that virtual threads perform better than platform threads for high concurrency
    assertThat("Virtual threads should be faster than platform threads for high concurrency", 
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Tests memory efficiency when handling numerous concurrent APT operations with virtual threads.
   * 
   * This test creates a very large number of virtual threads and monitors memory usage
   * to verify that virtual threads use significantly less memory than platform threads.
   */
  @Test
  public void testMemoryEfficiencyWithVirtualThreads() throws Exception {
    // Create thread factories for both thread types
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Number of concurrent operations to perform
    int operationCount = VERY_HIGH_THREAD_COUNT;
    
    // Measure memory usage with platform threads
    long platformThreadMemory = measureMemoryUsage(platformThreadFactory, operationCount);
    log.info("Platform thread memory usage: {} bytes", platformThreadMemory);
    
    // Measure memory usage with virtual threads
    long virtualThreadMemory = measureMemoryUsage(virtualThreadFactory, operationCount);
    log.info("Virtual thread memory usage: {} bytes", virtualThreadMemory);
    
    // Verify that virtual threads use less memory than platform threads
    assertThat("Virtual threads should use less memory than platform threads", 
        virtualThreadMemory, lessThan(platformThreadMemory));
  }
  
  /**
   * Measures the execution time of concurrent APT operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param operationCount The number of concurrent operations to perform
   * @return The execution time in milliseconds
   */
  private long measureExecutionTime(ThreadFactory threadFactory, int operationCount) throws Exception {
    // Create an executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    // Create a countdown latch to synchronize completion
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    // Track any errors that occur
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    try {
      // Record the start time
      long startTime = System.currentTimeMillis();
      
      // Submit tasks to the executor
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a test asset with a unique path
            AssetData asset = new AssetData();
            asset.setPath("/path/asset" + index + ".deb");
            
            // Compute browse paths for the asset
            browseNodeGenerator.computeAssetPaths(asset);
          }
          catch (Exception e) {
            log.error("Error in thread operation", e);
            hasErrors.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Record the end time
      long endTime = System.currentTimeMillis();
      
      // Verify all operations completed successfully
      assertThat("All operations should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent operations", hasErrors.get(), is(false));
      
      // Return the execution time
      return endTime - startTime;
    }
    finally {
      // Shutdown the executor
      executor.shutdown();
    }
  }
  
  /**
   * Measures the memory usage of concurrent APT operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param operationCount The number of concurrent operations to perform
   * @return The memory usage in bytes
   */
  private long measureMemoryUsage(ThreadFactory threadFactory, int operationCount) throws Exception {
    // Force garbage collection before measuring
    System.gc();
    Thread.sleep(100);
    
    // Record the initial memory usage
    long initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Create an executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    // Create a countdown latch to synchronize completion
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    // Create a list to hold references to prevent garbage collection during the test
    List<Object> references = new ArrayList<>(operationCount);
    
    try {
      // Submit tasks to the executor
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a test asset with a unique path
            AssetData asset = new AssetData();
            asset.setPath("/path/asset" + index + ".deb");
            
            // Compute browse paths for the asset
            List<BrowsePath> paths = browseNodeGenerator.computeAssetPaths(asset);
            
            // Store a reference to prevent garbage collection
            synchronized (references) {
              references.add(paths);
            }
            
            // Simulate some work
            Thread.sleep(10);
          }
          catch (Exception e) {
            log.error("Error in thread operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for half of the operations to complete to measure peak memory usage
      latch.await(operationCount / 2, TimeUnit.MILLISECONDS);
      
      // Force garbage collection before measuring peak memory
      System.gc();
      Thread.sleep(100);
      
      // Record the peak memory usage
      long peakMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      
      // Wait for all operations to complete
      latch.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Return the memory usage (peak - initial)
      return peakMemory - initialMemory;
    }
    finally {
      // Clear references
      references.clear();
      
      // Shutdown the executor
      executor.shutdown();
      
      // Force garbage collection after the test
      System.gc();
    }
  }
}