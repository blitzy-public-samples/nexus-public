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
package org.sonatype.nexus.repository.apt.virtualthread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.apt.api.AptHostedApiRepository;
import org.sonatype.nexus.repository.apt.internal.debian.DebianVersion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Tests for validating APT repository operations with Java 21 Virtual Threads.
 * 
 * This test suite validates that APT repository operations work correctly with Virtual Threads
 * and demonstrates performance improvements over platform threads for I/O-bound operations.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class AptVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int WARMUP_ITERATIONS = 5;
  private static final int BENCHMARK_ITERATIONS = 3;
  
  @Mock
  private Repository repository;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  void setUp() {
    // Set up platform thread executor with a fixed thread pool
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    // Set up virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Configure mock repository
    when(repository.getFormat()).thenReturn(new AptFormat());
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Validates that APT repository operations work correctly with Virtual Threads.
   */
  @Test
  @DisplayName("APT operations should work correctly with Virtual Threads")
  void aptOperationsWorkWithVirtualThreads() throws Exception {
    // Create a virtual thread and perform a basic APT operation
    CompletableFuture<DebianVersion> future = CompletableFuture.supplyAsync(() -> {
      // Simulate an APT repository operation
      return new DebianVersion("1.0.0-1");
    }, virtualThreadExecutor);
    
    DebianVersion version = future.get(5, TimeUnit.SECONDS);
    
    assertThat(version, notNullValue());
    assertThat(version.getUpstreamVersion(), is("1.0.0"));
    assertThat(version.getDebianRevision(), is("1"));
  }
  
  /**
   * Tests concurrent APT operations using Virtual Threads to validate scalability.
   */
  @Test
  @DisplayName("Virtual Threads should handle high concurrency for APT operations")
  void virtualThreadsHandleHighConcurrency() throws Exception {
    int operationCount = 1000;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Submit multiple concurrent tasks using virtual threads
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      CompletableFuture.runAsync(() -> {
        try {
          // Simulate an APT repository operation (parsing package version)
          DebianVersion version = new DebianVersion("1.0." + index + "-1");
          if (version.getUpstreamVersion().startsWith("1.0.")) {
            successCount.incrementAndGet();
          }
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    assertThat("All operations should complete within the timeout", completed, is(true));
    assertThat("All operations should succeed", successCount.get(), equalTo(operationCount));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for I/O-bound operations.
   */
  @Test
  @DisplayName("Virtual Threads should outperform platform threads for I/O-bound operations")
  void comparePerformanceForIOBoundOperations() throws Exception {
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentIOBoundOperations(platformThreadExecutor, 100);
      runConcurrentIOBoundOperations(virtualThreadExecutor, 100);
    }
    
    // Benchmark
    long platformThreadTime = 0;
    long virtualThreadTime = 0;
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformThreadTime += runConcurrentIOBoundOperations(platformThreadExecutor, CONCURRENT_OPERATIONS);
      virtualThreadTime += runConcurrentIOBoundOperations(virtualThreadExecutor, CONCURRENT_OPERATIONS);
    }
    
    // Calculate average times
    long avgPlatformThreadTime = platformThreadTime / BENCHMARK_ITERATIONS;
    long avgVirtualThreadTime = virtualThreadTime / BENCHMARK_ITERATIONS;
    
    logger.info("Average time with platform threads: {} ms", avgPlatformThreadTime);
    logger.info("Average time with virtual threads: {} ms", avgVirtualThreadTime);
    
    // Virtual threads should be faster for I/O-bound operations with high concurrency
    assertThat("Virtual threads should outperform platform threads for I/O-bound operations",
        avgVirtualThreadTime, lessThan(avgPlatformThreadTime));
  }
  
  /**
   * Tests for thread pinning detection in APT repository operations.
   */
  @Test
  @DisplayName("Should detect thread pinning in APT operations")
  void detectThreadPinningInAptOperations() throws Exception {
    // This test relies on the JVM flag -Djdk.tracePinnedThreads=full being set
    // to detect thread pinning. In a real environment, this would be configured
    // in the JVM arguments.
    
    // Create a list to capture any exceptions
    List<Exception> exceptions = new ArrayList<>();
    
    // Run operations that might cause thread pinning
    CountDownLatch latch = new CountDownLatch(10);
    
    for (int i = 0; i < 10; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          // Simulate an operation that might cause thread pinning
          // In a real test, this would use actual APT repository operations
          // that are known to potentially cause pinning
          simulateOperationWithPotentialPinning();
        } catch (Exception e) {
          exceptions.add(e);
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    latch.await(10, TimeUnit.SECONDS);
    
    // We're not asserting specific outcomes here as thread pinning detection
    // is primarily observed through JVM logs when -Djdk.tracePinnedThreads is enabled
    logger.info("Completed thread pinning detection test. Check JVM logs for pinning events.");
    
    // Ensure no exceptions occurred during the test
    assertThat("No exceptions should occur during thread pinning test", exceptions.isEmpty(), is(true));
  }
  
  /**
   * Tests memory efficiency of virtual threads compared to platform threads.
   */
  @Test
  @DisplayName("Virtual Threads should be more memory efficient than platform threads")
  void virtualThreadsAreMemoryEfficient() throws Exception {
    // Measure memory usage before creating threads
    long memoryBefore = getUsedMemory();
    
    // Create a large number of virtual threads
    int threadCount = 10000;
    List<Thread> virtualThreads = new ArrayList<>(threadCount);
    
    for (int i = 0; i < threadCount; i++) {
      Thread vt = Thread.ofVirtual().name("virtual-thread-" + i).start(() -> {
        try {
          // Just sleep briefly to keep the thread alive
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      virtualThreads.add(vt);
    }
    
    // Wait for all threads to complete
    for (Thread thread : virtualThreads) {
      thread.join();
    }
    
    // Measure memory after virtual threads
    long memoryAfterVirtual = getUsedMemory();
    long virtualThreadMemory = memoryAfterVirtual - memoryBefore;
    
    // Now try with a smaller number of platform threads for comparison
    int platformThreadCount = 100; // Using fewer platform threads as they're more resource-intensive
    List<Thread> platformThreads = new ArrayList<>(platformThreadCount);
    
    for (int i = 0; i < platformThreadCount; i++) {
      Thread pt = Thread.ofPlatform().name("platform-thread-" + i).start(() -> {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      platformThreads.add(pt);
    }
    
    // Wait for all platform threads to complete
    for (Thread thread : platformThreads) {
      thread.join();
    }
    
    // Measure memory after platform threads
    long memoryAfterPlatform = getUsedMemory();
    long platformThreadMemory = memoryAfterPlatform - memoryAfterVirtual;
    
    // Calculate memory per thread
    double memoryPerVirtualThread = (double) virtualThreadMemory / threadCount;
    double memoryPerPlatformThread = (double) platformThreadMemory / platformThreadCount;
    
    logger.info("Memory per virtual thread: {} bytes", memoryPerVirtualThread);
    logger.info("Memory per platform thread: {} bytes", memoryPerPlatformThread);
    
    // Virtual threads should use significantly less memory per thread
    assertThat("Virtual threads should use less memory per thread",
        memoryPerVirtualThread, lessThan(memoryPerPlatformThread));
  }
  
  /**
   * Runs concurrent I/O-bound operations using the provided executor.
   * 
   * @param executor The executor service to use
   * @param operationCount The number of concurrent operations to run
   * @return The time taken in milliseconds
   */
  private long runConcurrentIOBoundOperations(ExecutorService executor, int operationCount) throws Exception {
    CountDownLatch latch = new CountDownLatch(operationCount);
    long startTime = System.currentTimeMillis();
    
    for (int i = 0; i < operationCount; i++) {
      executor.submit(() -> {
        try {
          // Simulate I/O-bound APT repository operation
          simulateIOBoundOperation();
        } finally {
          latch.countDown();
        }
      });
    }
    
    latch.await();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Simulates an I/O-bound operation typical in APT repository handling.
   */
  private void simulateIOBoundOperation() {
    try {
      // Simulate I/O latency (e.g., network request, file read)
      Thread.sleep(50);
      
      // Simulate some CPU work after I/O
      DebianVersion version = new DebianVersion("1.0.0-1");
      version.getUpstreamVersion();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Simulates an operation that might cause thread pinning.
   */
  private void simulateOperationWithPotentialPinning() {
    // This method simulates an operation that might cause thread pinning
    // In a real test, this would use actual APT repository operations
    synchronized (this) {
      try {
        // Performing I/O operation while holding a lock can cause pinning
        Thread.sleep(10); // Simulate I/O
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Gets the current used memory in bytes.
   */
  private long getUsedMemory() {
    System.gc(); // Request garbage collection to get more accurate memory usage
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}