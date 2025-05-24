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
package org.sonatype.virtualthread;

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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.BlobAttributesSupport;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.metrics.reconcile.RecalculateBlobStoreSizeTask;
import org.sonatype.nexus.blobstore.metrics.reconcile.RecalculateBlobStoreSizeTaskDescriptor;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.tuple.Pair;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.common.BlobStoreTaskSupport.ALL;
import static org.sonatype.nexus.blobstore.common.BlobStoreTaskSupport.BLOBSTORE_NAME_FIELD_ID;

/**
 * Tests the {@link RecalculateBlobStoreSizeTask} with Java 21 Virtual Threads to verify that
 * blob size calculation operations can efficiently utilize the lightweight threading model.
 */
public class RecalculateBlobStoreSizeTaskVirtualThreadTest
    extends TestSupport
{
  @Mock
  private BlobStoreManager blobStoreManager;

  private RecalculateBlobStoreSizeTask underTest;

  @Before
  public void setUp() {
    underTest = new RecalculateBlobStoreSizeTask(blobStoreManager);
  }

  /**
   * Tests that the RecalculateBlobStoreSizeTask works correctly with a single BlobStore
   * when executed with Virtual Threads.
   */
  @Test
  public void testTaskWorksWithVirtualThreads() throws Exception {
    // Create a BlobStore with 100 blobs for testing
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStore("virtual-thread-blobstore", 100, false);

    TaskConfiguration configuration = buildTaskConfiguration("test-virtual-threads", "virtual-thread-blobstore");

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Execute the task in a virtual thread
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        underTest.configure(configuration);
        underTest.call();
      }
      catch (Exception e) {
        log.error("Error executing task in virtual thread", e);
      }
    });
    
    virtualThread.start();
    virtualThread.join();

    // Verify that the task processed all blobs and recorded metrics correctly
    verify(mocks.getLeft(), times(100)).getBlobAttributes(any(BlobId.class));
    verify(mocks.getRight(), times(100)).recordAddition(anyLong());
  }

  /**
   * Tests high concurrency scenario with multiple BlobStores using Virtual Threads.
   * This test verifies that I/O-bound operations in blob size calculation benefit from Virtual Threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create multiple BlobStores with different numbers of blobs
    Pair<BlobStore, BlobStoreMetricsService> blobstore1Mocks = mockBlobStore("vt-blobstore-1", 50, false);
    Pair<BlobStore, BlobStoreMetricsService> blobstore2Mocks = mockBlobStore("vt-blobstore-2", 75, false);
    Pair<BlobStore, BlobStoreMetricsService> blobstore3Mocks = mockBlobStore("vt-blobstore-3", 100, false);

    when(blobStoreManager.browse()).thenReturn(
        ImmutableList.of(blobstore1Mocks.getLeft(), blobstore2Mocks.getLeft(), blobstore3Mocks.getLeft()));

    TaskConfiguration configuration = buildTaskConfiguration("test-virtual-threads-all", ALL);
    underTest.configure(configuration);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Execute the task
      executor.submit(() -> {
        try {
          underTest.call();
        }
        catch (Exception e) {
          log.error("Error executing task with virtual threads", e);
        }
      }).get(30, TimeUnit.SECONDS); // Add timeout to prevent test hanging
    }

    // Verify that all BlobStores were processed correctly
    verify(blobstore1Mocks.getRight(), times(50)).recordAddition(anyLong());
    verify(blobstore2Mocks.getRight(), times(75)).recordAddition(anyLong());
    verify(blobstore3Mocks.getRight(), times(100)).recordAddition(anyLong());
  }

  /**
   * Tests that multiple concurrent blob size calculations can run efficiently with Virtual Threads.
   * This test creates a large number of Virtual Threads to simulate high concurrency.
   */
  @Test
  public void testConcurrentBlobSizeCalculations() throws Exception {
    // Create a BlobStore with a large number of blobs
    final int blobCount = 1000;
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStore("concurrent-vt-blobstore", blobCount, false);

    TaskConfiguration configuration = buildTaskConfiguration("test-concurrent-vt", "concurrent-vt-blobstore");
    underTest.configure(configuration);

    // Create a counter to track completed operations
    AtomicInteger completedOperations = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(blobCount);

    // Create a custom BlobStore that counts operations
    BlobStore countingBlobStore = mock(BlobStore.class);
    when(countingBlobStore.getBlobStoreConfiguration()).thenReturn(mocks.getLeft().getBlobStoreConfiguration());
    when(countingBlobStore.getMetricsService()).thenReturn(mocks.getRight());
    
    // Return a stream of BlobIds and count each getBlobAttributes call
    when(countingBlobStore.getBlobIdStream()).thenAnswer((invocation) -> Stream.iterate(0, n -> n + 1)
        .limit(blobCount)
        .map((n) -> new BlobId(n.toString())));
    
    when(countingBlobStore.getBlobAttributes(any())).thenAnswer((invocation) -> {
      // Simulate I/O operation with a small delay
      Thread.sleep(5);
      completedOperations.incrementAndGet();
      latch.countDown();
      return mocks.getLeft().getBlobAttributes(invocation.getArgument(0));
    });
    
    when(blobStoreManager.get("concurrent-vt-blobstore")).thenReturn(countingBlobStore);

    // Execute the task with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          underTest.call();
        }
        catch (Exception e) {
          log.error("Error executing concurrent task with virtual threads", e);
        }
      });
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue("Not all blob operations completed in time", completed);
      
      // Verify that all operations were completed
      assertEquals(blobCount, completedOperations.get());
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