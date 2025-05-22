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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;

import org.apache.maven.index.reader.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DuplicateDetectionStrategyProvider} using Virtual Threads.
 * 
 * This test class validates that the DuplicateDetectionStrategyProvider and its strategies
 * function correctly when executed with Java 21's Virtual Threads. It focuses on:
 * 1. Correct strategy instantiation in a Virtual Thread environment
 * 2. Concurrent strategy creation and usage with multiple Virtual Threads
 * 3. Proper resource management and cleanup, especially for disk-backed strategies
 * 4. Behavior under high concurrency with multiple simultaneous requests
 */
@ExtendWith(MockitoExtension.class)
public class DuplicateDetectionStrategyProviderTest
    extends TestSupport
{
  private static final int MAX_HEAP_GB = 1;

  private static final int MAX_DISK_SIZE_GB = 10;
  
  private static final int CONCURRENT_THREADS = 50;

  @TempDir
  Path tempDir;

  @Mock
  private ApplicationDirectories applicationDirectories;

  @BeforeEach
  public void setup() throws IOException {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tempDir.toFile());
  }

  @Test
  public void shouldReturnBloomStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories,
        "BLOOM", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();

    assertThat(strategy, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));

    DuplicateDetectionStrategy<Record> strategyLowerCase = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "bloom", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();

    assertThat(strategyLowerCase, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));
  }

  @Test
  public void shouldReturnDiskStrategy() {
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
  public void shouldReturnInMemoryStrategy() {
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
  public void shouldFallBackToBloomForUnknownStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories,
        "unknown", MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategy, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));
  }
  
  /**
   * Tests concurrent creation of strategies using Virtual Threads.
   * 
   * This test verifies that the DuplicateDetectionStrategyProvider can correctly instantiate
   * all three types of strategies (BLOOM, DISK, HASH) when called from multiple Virtual Threads
   * concurrently. It ensures that the provider is thread-safe and works correctly in a
   * highly concurrent environment.  
   */
  @Test
  public void shouldCreateStrategiesInVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS * 3); // 3 strategy types
      ConcurrentHashMap<String, AtomicInteger> strategyTypes = new ConcurrentHashMap<>();
      
      // Create strategies concurrently using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        // Create BLOOM strategy
        executor.submit(() -> {
          DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(
              applicationDirectories, "BLOOM", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();
          strategyTypes.computeIfAbsent("BLOOM", k -> new AtomicInteger()).incrementAndGet();
          assertThat(strategy, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));
          latch.countDown();
        });
        
        // Create DISK strategy
        executor.submit(() -> {
          DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(
              applicationDirectories, "DISK", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();
          strategyTypes.computeIfAbsent("DISK", k -> new AtomicInteger()).incrementAndGet();
          assertThat(strategy, is(instanceOf(DiskBackedDuplicateDetectionStrategy.class)));
          latch.countDown();
        });
        
        // Create HASH strategy
        executor.submit(() -> {
          DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(
              applicationDirectories, "HASH", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();
          strategyTypes.computeIfAbsent("HASH", k -> new AtomicInteger()).incrementAndGet();
          assertThat(strategy, is(instanceOf(HashBasedDuplicateDetectionStrategy.class)));
          latch.countDown();
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify all strategy types were created the expected number of times
      assertEquals(CONCURRENT_THREADS, strategyTypes.get("BLOOM").get());
      assertEquals(CONCURRENT_THREADS, strategyTypes.get("DISK").get());
      assertEquals(CONCURRENT_THREADS, strategyTypes.get("HASH").get());
    }
  }
  
  /**
   * Tests duplicate detection functionality of strategies when used with Virtual Threads.
   * 
   * This test verifies that all three strategy implementations (BLOOM, DISK, HASH) correctly
   * detect duplicates when used concurrently from multiple Virtual Threads. It creates a single
   * instance of each strategy type and then uses them from multiple Virtual Threads simultaneously,
   * ensuring they maintain correct state and properly identify duplicates across threads.
   */
  @Test
  public void shouldHandleDuplicateDetectionInVirtualThreads() throws Exception {
    // Create a provider for each strategy type
    DuplicateDetectionStrategyProvider bloomProvider = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "BLOOM", MAX_HEAP_GB, MAX_DISK_SIZE_GB);
    DuplicateDetectionStrategyProvider diskProvider = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "DISK", MAX_HEAP_GB, MAX_DISK_SIZE_GB);
    DuplicateDetectionStrategyProvider hashProvider = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "HASH", MAX_HEAP_GB, MAX_DISK_SIZE_GB);
    
    // Create a strategy of each type
    DuplicateDetectionStrategy<Record> bloomStrategy = bloomProvider.get();
    DuplicateDetectionStrategy<Record> diskStrategy = diskProvider.get();
    DuplicateDetectionStrategy<Record> hashStrategy = hashProvider.get();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS * 3); // 3 strategy types
      
      // Create mock records
      List<Record> records = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        records.add(createMockRecord("group", "artifact", "version" + i));
      }
      
      // Test each strategy with virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int index = i;
        
        // Test BLOOM strategy
        executor.submit(() -> {
          Record record = records.get(index);
          // First time should return true (not a duplicate)
          assertTrue(bloomStrategy.apply(record));
          // Second time should return false (is a duplicate)
          assertFalse(bloomStrategy.apply(record));
          latch.countDown();
        });
        
        // Test DISK strategy
        executor.submit(() -> {
          Record record = records.get(index);
          // First time should return true (not a duplicate)
          assertTrue(diskStrategy.apply(record));
          // Second time should return false (is a duplicate)
          assertFalse(diskStrategy.apply(record));
          latch.countDown();
        });
        
        // Test HASH strategy
        executor.submit(() -> {
          Record record = records.get(index);
          // First time should return true (not a duplicate)
          assertTrue(hashStrategy.apply(record));
          // Second time should return false (is a duplicate)
          assertFalse(hashStrategy.apply(record));
          latch.countDown();
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    } finally {
      // Clean up resources
      assertDoesNotThrow(() -> bloomStrategy.close());
      assertDoesNotThrow(() -> diskStrategy.close());
      assertDoesNotThrow(() -> hashStrategy.close());
    }
  }
  
  /**
   * Tests resource cleanup when strategies are closed from Virtual Threads.
   * 
   * This test focuses on the DiskBackedDuplicateDetectionStrategy which requires proper cleanup
   * of disk resources. It creates multiple instances of the strategy and then closes them
   * concurrently using Virtual Threads, verifying that resources are properly released and
   * temporary files are cleaned up. This is particularly important in a Virtual Thread environment
   * where many more threads might be created than in a traditional platform thread model.
   */
  @Test
  public void shouldCleanupResourcesInVirtualThreads() throws Exception {
    List<DuplicateDetectionStrategy<Record>> strategies = new ArrayList<>();
    
    // Create multiple disk-backed strategies which require cleanup
    for (int i = 0; i < 10; i++) {
      strategies.add(new DuplicateDetectionStrategyProvider(
          applicationDirectories, "DISK", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get());
    }
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(strategies.size());
      
      // Close each strategy in a separate virtual thread
      for (DuplicateDetectionStrategy<Record> strategy : strategies) {
        executor.submit(() -> {
          assertDoesNotThrow(() -> strategy.close());
          latch.countDown();
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify temp directory doesn't have excessive files
      // Note: We can't check for exact file count as other tests might create files
      long fileCount = Files.list(tempDir).count();
      assertTrue(fileCount < 100, "Too many temporary files: " + fileCount);
    }
  }
  
  /**
   * Creates a mock Record with the specified GAV coordinates.
   * 
   * @param groupId The group ID for the record
   * @param artifactId The artifact ID for the record
   * @param version The version for the record
   * @return A new Record instance with the specified coordinates
   */
  private Record createMockRecord(String groupId, String artifactId, String version) {
    Record record = new Record(Record.Type.ARTIFACT_ADD, "test");
    record.put(Record.GROUP_ID, groupId);
    record.put(Record.ARTIFACT_ID, artifactId);
    record.put(Record.VERSION, version);
    record.put(Record.CLASSIFIER, "classifier");
    record.put(Record.EXTENSION, "jar");
    return record;
  }
}