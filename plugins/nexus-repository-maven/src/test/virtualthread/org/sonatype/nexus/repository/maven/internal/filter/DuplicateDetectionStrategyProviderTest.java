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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;

import org.apache.maven.index.reader.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DuplicateDetectionStrategyProvider} using Virtual Threads.
 * 
 * This test class validates that the provider correctly instantiates the appropriate
 * strategy implementations and that these strategies function properly in a Virtual Thread
 * environment, with particular attention to concurrency and resource management.
 */
@ExtendWith(MockitoExtension.class)
class DuplicateDetectionStrategyProviderTest
    extends TestSupport
{
  private static final int MAX_HEAP_GB = 1;

  private static final int MAX_DISK_SIZE_GB = 10;
  
  // Number of concurrent requests to simulate
  private static final int CONCURRENT_REQUESTS = 100;
  
  // Number of iterations for each test
  private static final int TEST_ITERATIONS = 10;

  @TempDir
  Path tempDir;

  @Mock
  private ApplicationDirectories applicationDirectories;

  @BeforeEach
  void setup() {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tempDir.toFile());
  }

  @Test
  void shouldReturnBloomStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories,
        "BLOOM", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();

    assertThat(strategy, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));

    DuplicateDetectionStrategy<Record> strategyLowerCase = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "bloom", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();

    assertThat(strategyLowerCase, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));
  }

  @Test
  void shouldReturnDiskStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories, "DISK",
        MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategy, is(instanceOf(DiskBackedDuplicateDetectionStrategy.class)));

    DuplicateDetectionStrategy<Record> strategyLowerCase = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "disk", MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategyLowerCase, is(instanceOf(DiskBackedDuplicateDetectionStrategy.class)));
  }

  @Test
  void shouldReturnInMemoryStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories, "HASH",
        MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategy, is(instanceOf(HashBasedDuplicateDetectionStrategy.class)));

    DuplicateDetectionStrategy<Record> strategyLowerCase = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "hash", MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategyLowerCase, is(instanceOf(HashBasedDuplicateDetectionStrategy.class)));
  }

  @Test
  void shouldFallBackToBloomForUnknownStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories,
        "unknown", MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategy, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));
  }
  
  /**
   * Tests concurrent creation of BloomFilterDuplicateDetectionStrategy instances using Virtual Threads.
   * This test validates that the provider can handle multiple simultaneous requests
   * in a highly concurrent environment using Java 21's Virtual Threads.
   */
  @Test
  void shouldHandleConcurrentBloomStrategyCreationWithVirtualThreads() throws Exception {
    // Create a Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create multiple strategies concurrently using Virtual Threads
      CompletableFuture<?>[] futures = new CompletableFuture<?>[CONCURRENT_REQUESTS];
      
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        final int index = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(
                applicationDirectories, "BLOOM", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();
            
            // Verify the strategy is of the correct type
            assertThat(strategy, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));
            
            // Verify the strategy works by adding a record and checking for duplicates
            Record record = new Record(Record.Type.ARTIFACT_ADD, "test:artifact:" + index);
            assertThat(strategy.isDuplicate(record), is(false)); // First occurrence
            assertThat(strategy.isDuplicate(record), is(true));  // Second occurrence should be a duplicate
          } catch (Exception e) {
            log.error("Error in virtual thread test", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      latch.await();
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent strategy creation", 
          errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests concurrent creation of DiskBackedDuplicateDetectionStrategy instances using Virtual Threads.
   * This test validates that the provider can handle multiple simultaneous requests for disk-based
   * strategies in a highly concurrent environment using Java 21's Virtual Threads.
   */
  @Test
  void shouldHandleConcurrentDiskStrategyCreationWithVirtualThreads() throws Exception {
    // Create a Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create multiple strategies concurrently using Virtual Threads
      CompletableFuture<?>[] futures = new CompletableFuture<?>[CONCURRENT_REQUESTS];
      
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        final int index = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(
                applicationDirectories, "DISK", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();
            
            // Verify the strategy is of the correct type
            assertThat(strategy, is(instanceOf(DiskBackedDuplicateDetectionStrategy.class)));
            
            // Verify the strategy works by adding a record and checking for duplicates
            Record record = new Record(Record.Type.ARTIFACT_ADD, "test:disk-artifact:" + index);
            assertThat(strategy.isDuplicate(record), is(false)); // First occurrence
            assertThat(strategy.isDuplicate(record), is(true));  // Second occurrence should be a duplicate
            
            // Test resource cleanup by explicitly closing the strategy
            ((DiskBackedDuplicateDetectionStrategy) strategy).close();
          } catch (Exception e) {
            log.error("Error in virtual thread disk strategy test", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      latch.await();
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent disk strategy creation", 
          errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests concurrent creation of HashBasedDuplicateDetectionStrategy instances using Virtual Threads.
   * This test validates that the provider can handle multiple simultaneous requests for hash-based
   * strategies in a highly concurrent environment using Java 21's Virtual Threads.
   */
  @Test
  void shouldHandleConcurrentHashStrategyCreationWithVirtualThreads() throws Exception {
    // Create a Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create multiple strategies concurrently using Virtual Threads
      CompletableFuture<?>[] futures = new CompletableFuture<?>[CONCURRENT_REQUESTS];
      
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        final int index = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(
                applicationDirectories, "HASH", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();
            
            // Verify the strategy is of the correct type
            assertThat(strategy, is(instanceOf(HashBasedDuplicateDetectionStrategy.class)));
            
            // Verify the strategy works by adding a record and checking for duplicates
            Record record = new Record(Record.Type.ARTIFACT_ADD, "test:hash-artifact:" + index);
            assertThat(strategy.isDuplicate(record), is(false)); // First occurrence
            assertThat(strategy.isDuplicate(record), is(true));  // Second occurrence should be a duplicate
          } catch (Exception e) {
            log.error("Error in virtual thread hash strategy test", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      latch.await();
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent hash strategy creation", 
          errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests the provider's behavior when multiple different strategy types are requested concurrently
   * using Virtual Threads. This test validates that the provider can correctly handle a mix of
   * strategy requests in a highly concurrent environment.
   */
  @Test
  void shouldHandleMixedStrategyTypesWithVirtualThreads() throws Exception {
    // Create a Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS * 3); // 3 strategy types
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicInteger bloomCount = new AtomicInteger(0);
      AtomicInteger diskCount = new AtomicInteger(0);
      AtomicInteger hashCount = new AtomicInteger(0);
      
      // Create multiple strategies concurrently using Virtual Threads
      CompletableFuture<?>[] futures = new CompletableFuture<?>[CONCURRENT_REQUESTS * 3];
      
      for (int i = 0; i < CONCURRENT_REQUESTS * 3; i++) {
        final int index = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Determine strategy type based on index
            String strategyType;
            switch (index % 3) {
              case 0:
                strategyType = "BLOOM";
                break;
              case 1:
                strategyType = "DISK";
                break;
              default:
                strategyType = "HASH";
                break;
            }
            
            DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(
                applicationDirectories, strategyType, MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();
            
            // Verify the strategy is of the correct type and increment counter
            if (strategy instanceof BloomFilterDuplicateDetectionStrategy) {
              bloomCount.incrementAndGet();
            } else if (strategy instanceof DiskBackedDuplicateDetectionStrategy) {
              diskCount.incrementAndGet();
              // Close disk-backed strategy to clean up resources
              ((DiskBackedDuplicateDetectionStrategy) strategy).close();
            } else if (strategy instanceof HashBasedDuplicateDetectionStrategy) {
              hashCount.incrementAndGet();
            }
            
            // Verify the strategy works by adding a record and checking for duplicates
            Record record = new Record(Record.Type.ARTIFACT_ADD, "test:mixed:" + index);
            assertThat(strategy.isDuplicate(record), is(false)); // First occurrence
            assertThat(strategy.isDuplicate(record), is(true));  // Second occurrence should be a duplicate
          } catch (Exception e) {
            log.error("Error in mixed strategy test", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      latch.await();
      
      // Verify no errors occurred
      assertThat("No errors should occur during mixed strategy creation", 
          errorCount.get(), is(0));
      
      // Verify we got the expected distribution of strategy types
      assertThat("Should have created BLOOM strategies", bloomCount.get(), is(CONCURRENT_REQUESTS));
      assertThat("Should have created DISK strategies", diskCount.get(), is(CONCURRENT_REQUESTS));
      assertThat("Should have created HASH strategies", hashCount.get(), is(CONCURRENT_REQUESTS));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests the resource cleanup behavior of strategies created by the provider when used with
   * Virtual Threads. This test focuses specifically on the DiskBackedDuplicateDetectionStrategy
   * which requires explicit cleanup of temporary files.
   */
  @Test
  void shouldCleanupResourcesProperlyWithVirtualThreads() throws Exception {
    // Create a Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Run multiple iterations to stress test resource cleanup
      for (int iteration = 0; iteration < TEST_ITERATIONS; iteration++) {
        CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Create multiple disk strategies concurrently using Virtual Threads
        CompletableFuture<?>[] futures = new CompletableFuture<?>[CONCURRENT_REQUESTS];
        
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
          final int index = i;
          futures[i] = CompletableFuture.runAsync(() -> {
            DiskBackedDuplicateDetectionStrategy strategy = null;
            try {
              // Create a disk-backed strategy
              strategy = (DiskBackedDuplicateDetectionStrategy) new DuplicateDetectionStrategyProvider(
                  applicationDirectories, "DISK", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();
              
              // Use the strategy
              Record record = new Record(Record.Type.ARTIFACT_ADD, "test:cleanup:" + iteration + ":" + index);
              strategy.isDuplicate(record);
            } catch (Exception e) {
              log.error("Error in resource cleanup test", e);
              errorCount.incrementAndGet();
            } finally {
              // Always close the strategy to clean up resources
              if (strategy != null) {
                try {
                  strategy.close();
                } catch (Exception e) {
                  log.error("Error closing strategy", e);
                  errorCount.incrementAndGet();
                }
              }
              latch.countDown();
            }
          }, executor);
        }
        
        // Wait for all tasks to complete
        latch.await();
        
        // Verify no errors occurred
        assertThat("No errors should occur during resource cleanup in iteration " + iteration, 
            errorCount.get(), is(0));
      }
    } finally {
      executor.shutdown();
    }
  }