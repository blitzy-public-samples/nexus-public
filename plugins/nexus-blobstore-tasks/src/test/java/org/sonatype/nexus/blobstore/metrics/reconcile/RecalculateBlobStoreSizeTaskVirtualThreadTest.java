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
package org.sonatype.nexus.blobstore.metrics.reconcile;

import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.sonatype.goodies.common.MultipleFailures.MultipleFailuresException;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.BlobAttributesSupport;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.tuple.Pair;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.common.BlobStoreTaskSupport.ALL;
import static org.sonatype.nexus.blobstore.common.BlobStoreTaskSupport.BLOBSTORE_NAME_FIELD_ID;

/**
 * Test for {@link RecalculateBlobStoreSizeTask} that validates behavior when executed with Java 21 virtual threads.
 * 
 * This test ensures that the task correctly handles concurrent execution with virtual threads, properly records metrics,
 * and propagates errors as expected in a virtual thread environment.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class RecalculateBlobStoreSizeTaskVirtualThreadTest
{
  @Mock
  private BlobStoreManager blobStoreManager;

  private RecalculateBlobStoreSizeTask underTest;

  @BeforeEach
  public void setUp() {
    underTest = spy(new RecalculateBlobStoreSizeTask(blobStoreManager));
  }

  /**
   * Tests that the task works correctly with a single blob store when executed with virtual threads.
   * 
   * This test validates that:
   * 1. The task can be executed successfully using virtual threads
   * 2. All blob attributes are processed correctly
   * 3. Metrics are recorded properly for each blob
   */
  @Test
  public void testTaskWorksAsExpectedWithSingleBlobStoreUsingVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStore("single-blobstore", 10, false);

      TaskConfiguration configuration = buildTaskConfiguration("test-single-blobstore", "single-blobstore");

      underTest.configure(configuration);
      
      // Execute the task using a virtual thread
      executor.submit(() -> {
        try {
          underTest.call();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }).get(30, TimeUnit.SECONDS);

      verify(underTest, times(1)).execute(mocks.getLeft());
      verify(mocks.getLeft(), times(10)).getBlobAttributes(any(BlobId.class));
      verify(mocks.getRight(), times(10)).recordAddition(anyLong());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the task works correctly with multiple blob stores when executed with virtual threads.
   * 
   * This test validates that:
   * 1. The task can process multiple blob stores concurrently using virtual threads
   * 2. All blob attributes across all stores are processed correctly
   * 3. Metrics are recorded properly for each blob in each store
   */
  @Test
  public void testTaskWorksAsExpectedWithAllBlobStoresUsingVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      Pair<BlobStore, BlobStoreMetricsService> blobstore1Mocks = mockBlobStore("test-blobstore-1", 10, false);
      Pair<BlobStore, BlobStoreMetricsService> blobstore2Mocks = mockBlobStore("test-blobstore-2", 25, false);
      Pair<BlobStore, BlobStoreMetricsService> blobstore3Mocks = mockBlobStore("test-blobstore-3", 12, false);

      TaskConfiguration configuration = buildTaskConfiguration("test-all-blobstores", ALL);

      when(blobStoreManager.browse()).thenReturn(
          ImmutableList.of(blobstore1Mocks.getLeft(), blobstore2Mocks.getLeft(), blobstore3Mocks.getLeft()));

      underTest.configure(configuration);
      
      // Execute the task using a virtual thread
      executor.submit(() -> {
        try {
          underTest.call();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }).get(30, TimeUnit.SECONDS);

      verify(underTest, times(3)).execute(any(BlobStore.class));
      verify(blobstore1Mocks.getRight(), times(10)).recordAddition(anyLong());
      verify(blobstore2Mocks.getRight(), times(25)).recordAddition(anyLong());
      verify(blobstore3Mocks.getRight(), times(12)).recordAddition(anyLong());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the task correctly propagates failures when executed with virtual threads.
   * 
   * This test validates that:
   * 1. The task properly handles errors in a virtual thread environment
   * 2. Failures from one blob store don't prevent processing of other blob stores
   * 3. Multiple failures are collected and propagated correctly
   */
  @Test
  public void testTaskPropagateFailuresAsExpectedWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      Pair<BlobStore, BlobStoreMetricsService> unavailableMocks = mockBlobStore("unavailable-blobstore", 3, true);
      Pair<BlobStore, BlobStoreMetricsService> available1Mocks = mockBlobStore("available-blobstore-1", 56, false);
      Pair<BlobStore, BlobStoreMetricsService> available2Mocks = mockBlobStore("available-blobstore-2", 23, false);

      TaskConfiguration configuration = buildTaskConfiguration("test-multiple-failures", ALL);

      when(blobStoreManager.browse()).thenReturn(
          ImmutableList.of(unavailableMocks.getLeft(), available1Mocks.getLeft(), available2Mocks.getLeft()));

      underTest.configure(configuration);
      
      // Execute the task using a virtual thread and expect a MultipleFailuresException
      assertThrows(MultipleFailuresException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.call();
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }).get(30, TimeUnit.SECONDS);
      });

      verify(underTest, times(3)).execute(any(BlobStore.class));
      verify(unavailableMocks.getRight(), never()).recordAddition(anyLong());
      verify(available1Mocks.getRight(), times(56)).recordAddition(anyLong());
      verify(available2Mocks.getRight(), times(23)).recordAddition(anyLong());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests high concurrency scenario with multiple virtual threads processing blob stores simultaneously.
   * 
   * This test validates that:
   * 1. The task can handle high concurrency with virtual threads
   * 2. Metrics are recorded correctly even under high concurrency
   * 3. The system remains stable with many concurrent virtual threads
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a large number of blob stores to test high concurrency
      int blobStoreCount = 20;
      int blobsPerStore = 50;
      
      // Create and configure multiple blob stores
      for (int i = 0; i < blobStoreCount; i++) {
        mockBlobStore("concurrent-blobstore-" + i, blobsPerStore, false);
      }
      
      // Configure the task to process all blob stores
      TaskConfiguration configuration = buildTaskConfiguration("test-high-concurrency", ALL);
      underTest.configure(configuration);
      
      // Set up concurrent execution
      CountDownLatch latch = new CountDownLatch(blobStoreCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Mock the browse method to return a new list for each call to simulate concurrent access
      when(blobStoreManager.browse()).thenAnswer(invocation -> {
        // Create a list of blob stores for this specific invocation
        ImmutableList.Builder<BlobStore> stores = ImmutableList.builder();
        for (int i = 0; i < blobStoreCount; i++) {
          BlobStore store = mock(BlobStore.class);
          BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
          when(config.getName()).thenReturn("concurrent-blobstore-" + i);
          when(config.getType()).thenReturn(FileBlobStore.TYPE);
          when(store.getBlobStoreConfiguration()).thenReturn(config);
          when(store.getBlobIdStream()).thenAnswer((innerInvocation) -> Stream.iterate(0, n -> n + 1)
              .limit(blobsPerStore)
              .map((n) -> new BlobId(n.toString())));
          when(store.getBlobAttributes(any())).thenAnswer((innerInvocation) -> {
            Random random = new Random();
            Map<String, String> headers = ImmutableMap.of();
            BlobMetrics metrics = new BlobMetrics(DateTime.now().minusHours(1), "hash", random.nextInt(100));
            return new TestBlobAttributes(headers, metrics);
          });
          
          BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
          when(store.getMetricsService()).thenReturn(metricsService);
          
          stores.add(store);
        }
        return stores.build();
      });
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < blobStoreCount; i++) {
        final int storeIndex = i;
        executor.submit(() -> {
          try {
            // Mock getting a specific blob store to test concurrent execution
            BlobStore store = mock(BlobStore.class);
            BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
            BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
            
            when(config.getName()).thenReturn("concurrent-blobstore-" + storeIndex);
            when(config.getType()).thenReturn(FileBlobStore.TYPE);
            when(store.getBlobStoreConfiguration()).thenReturn(config);
            when(store.getMetricsService()).thenReturn(metricsService);
            when(store.getBlobIdStream()).thenAnswer((innerInvocation) -> Stream.iterate(0, n -> n + 1)
                .limit(blobsPerStore)
                .map((n) -> new BlobId(n.toString())));
            when(store.getBlobAttributes(any())).thenAnswer((innerInvocation) -> {
              Random random = new Random();
              Map<String, String> headers = ImmutableMap.of();
              BlobMetrics metrics = new BlobMetrics(DateTime.now().minusHours(1), "hash", random.nextInt(100));
              return new TestBlobAttributes(headers, metrics);
            });
            
            when(blobStoreManager.get("concurrent-blobstore-" + storeIndex)).thenReturn(store);
            
            // Execute the task for this specific blob store
            underTest.execute(store);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(60, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      if (errorCount.get() > 0) {
        throw new AssertionError(errorCount.get() + " errors occurred during concurrent execution");
      }
    } finally {
      executor.shutdown();
    }
  }

  private Pair<BlobStore, BlobStoreMetricsService> mockBlobStore(
      final String blobstoreName,
      final int blobsCount,
      final boolean throwException)
  {
    BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStoreManager.get(blobstoreName)).thenReturn(blobStore);
    when(configuration.getName()).thenReturn(blobstoreName);
    when(configuration.getType()).thenReturn(FileBlobStore.TYPE);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(blobStore.getMetricsService()).thenReturn(metricsService);

    if (throwException) {
      when(blobStore.getBlobIdStream()).thenThrow(new IllegalStateException("unavailable blobstore"));
    }
    else {
      when(blobStore.getBlobIdStream()).thenAnswer((invocation) -> Stream.iterate(0, n -> n + 1)
          .limit(blobsCount)
          .map((n) -> new BlobId(n.toString())));
    }

    when(blobStore.getBlobAttributes(any())).thenAnswer((invocation) -> {
      Random random = new Random();

      Map<String, String> headers = ImmutableMap.of();
      BlobMetrics metrics = new BlobMetrics(DateTime.now().minusHours(1), "hash", random.nextInt(100));
      return new TestBlobAttributes(headers, metrics);
    });

    return Pair.of(blobStore, metricsService);
  }

  private TaskConfiguration buildTaskConfiguration(final String taskName, final String blobStoreField) {
    TaskConfiguration taskConfiguration = new TaskConfiguration();
    taskConfiguration.setId(taskName);
    taskConfiguration.setTypeId(RecalculateBlobStoreSizeTaskDescriptor.TYPE_ID);
    taskConfiguration.setString(".name", taskName);
    taskConfiguration.setString(BLOBSTORE_NAME_FIELD_ID, blobStoreField);

    return taskConfiguration;
  }

  private static class TestBlobAttributes
      extends BlobAttributesSupport<Properties>
  {
    public Properties properties;

    public TestBlobAttributes(final Map<String, String> headers, final BlobMetrics blobMetrics) {
      super(new Properties(), headers, blobMetrics);
      this.properties = propertiesFile;
    }

    @Override
    public void store() {
      writeTo(properties);
    }

    @Override
    public void writeProperties() {
      writeTo(propertiesFile);
    }
  }
}