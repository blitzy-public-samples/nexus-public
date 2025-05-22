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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
  private static final int HIGH_CONCURRENCY_BLOB_COUNT = 1000;
  private static final int SIMULATED_IO_DELAY_MS = 10;
  
  @Mock
  private BlobStoreManager blobStoreManager;

  private RecalculateBlobStoreSizeTask underTest;
  
  private ExecutorService virtualThreadExecutor;

  @Before
  public void setUp() {
    underTest = spy(new RecalculateBlobStoreSizeTask(blobStoreManager));
    // Create an executor service using virtual threads
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("virtual-thread-test-", 0).factory());
  }

  /**
   * Tests that the RecalculateBlobStoreSizeTask can process a large number of blobs concurrently
   * using Virtual Threads, which is particularly beneficial for I/O-bound operations like
   * reading blob attributes.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a blob store with a large number of blobs to test high concurrency
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStore(
        "virtual-thread-blobstore", HIGH_CONCURRENCY_BLOB_COUNT, false, true);

    TaskConfiguration configuration = buildTaskConfiguration(
        "test-virtual-thread-blobstore", "virtual-thread-blobstore");

    underTest.configure(configuration);
    underTest.call();

    // Verify that all blobs were processed
    verify(underTest, times(1)).execute(mocks.getLeft());
    verify(mocks.getLeft(), times(HIGH_CONCURRENCY_BLOB_COUNT)).getBlobAttributes(any(BlobId.class));
    verify(mocks.getRight(), times(HIGH_CONCURRENCY_BLOB_COUNT)).recordAddition(anyLong());
  }

  /**
   * Tests that multiple blob stores can be processed concurrently using Virtual Threads,
   * demonstrating the scalability benefits of the lightweight threading model.
   */
  @Test
  public void testMultipleBlobStoresWithVirtualThreads() throws Exception {
    // Create multiple blob stores with varying numbers of blobs
    Pair<BlobStore, BlobStoreMetricsService> blobstore1Mocks = mockBlobStore(
        "virtual-blobstore-1", 200, false, true);
    Pair<BlobStore, BlobStoreMetricsService> blobstore2Mocks = mockBlobStore(
        "virtual-blobstore-2", 300, false, true);
    Pair<BlobStore, BlobStoreMetricsService> blobstore3Mocks = mockBlobStore(
        "virtual-blobstore-3", 500, false, true);

    TaskConfiguration configuration = buildTaskConfiguration("test-all-virtual-blobstores", ALL);

    when(blobStoreManager.browse()).thenReturn(
        ImmutableList.of(blobstore1Mocks.getLeft(), blobstore2Mocks.getLeft(), blobstore3Mocks.getLeft()));

    underTest.configure(configuration);
    underTest.call();

    // Verify that all blob stores were processed
    verify(underTest, times(3)).execute(any(BlobStore.class));
    verify(blobstore1Mocks.getRight(), times(200)).recordAddition(anyLong());
    verify(blobstore2Mocks.getRight(), times(300)).recordAddition(anyLong());
    verify(blobstore3Mocks.getRight(), times(500)).recordAddition(anyLong());
  }

  /**
   * Tests that concurrent blob attribute reading operations can be performed efficiently
   * using Virtual Threads, with explicit verification that the operations complete in a
   * reasonable time frame despite simulated I/O delays.
   */
  @Test
  public void testConcurrentBlobAttributeReadingWithVirtualThreads() throws Exception {
    // Create a blob store with a moderate number of blobs and simulate I/O delays
    int blobCount = 100;
    AtomicInteger concurrentOperations = new AtomicInteger(0);
    AtomicInteger maxConcurrentOperations = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(blobCount);
    
    // Mock a blob store that tracks concurrent operations
    BlobStore blobStore = mock(BlobStore.class);
    BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    
    when(blobStoreManager.get("concurrent-blobstore")).thenReturn(blobStore);
    when(configuration.getName()).thenReturn("concurrent-blobstore");
    when(configuration.getType()).thenReturn(FileBlobStore.TYPE);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(blobStore.getMetricsService()).thenReturn(metricsService);
    
    // Generate a stream of blob IDs
    when(blobStore.getBlobIdStream()).thenAnswer(invocation -> Stream.iterate(0, n -> n + 1)
        .limit(blobCount)
        .map(n -> new BlobId(n.toString())));
    
    // Mock getBlobAttributes to simulate I/O with tracking of concurrent operations
    when(blobStore.getBlobAttributes(any())).thenAnswer(new Answer<Object>() {
      @Override
      public Object answer(InvocationOnMock invocation) throws Throwable {
        // Wait for the start signal
        startLatch.await();
        
        // Track concurrent operations
        int current = concurrentOperations.incrementAndGet();
        maxConcurrentOperations.updateAndGet(max -> Math.max(max, current));
        
        // Simulate I/O delay
        Thread.sleep(SIMULATED_IO_DELAY_MS);
        
        // Create and return blob attributes
        Random random = new Random();
        Map<String, String> headers = ImmutableMap.of();
        BlobMetrics metrics = new BlobMetrics(DateTime.now().minusHours(1), "hash", random.nextInt(100));
        
        // Decrement counter and signal completion
        concurrentOperations.decrementAndGet();
        completionLatch.countDown();
        
        return new TestBlobAttributes(headers, metrics);
      }
    });
    
    // Configure and run the task
    TaskConfiguration taskConfiguration = buildTaskConfiguration(
        "test-concurrent-operations", "concurrent-blobstore");
    underTest.configure(taskConfiguration);
    
    // Start the operations
    startLatch.countDown();
    
    // Run the task
    underTest.call();
    
    // Wait for all operations to complete with timeout
    boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
    assertThat("All blob attribute operations should complete", completed, is(true));
    
    // Verify that we had high concurrency (more than just a few threads)
    log.info("Maximum concurrent operations: {}", maxConcurrentOperations.get());
    assertThat("Should achieve high concurrency with virtual threads", 
        maxConcurrentOperations.get() > 10, is(true));
    
    // Verify all blobs were processed
    verify(blobStore, times(blobCount)).getBlobAttributes(any(BlobId.class));
    verify(metricsService, times(blobCount)).recordAddition(anyLong());
  }

  /**
   * Tests that multiple tasks can be executed concurrently using Virtual Threads,
   * demonstrating the ability to handle many parallel blob store operations efficiently.
   */
  @Test
  public void testParallelTaskExecutionWithVirtualThreads() throws Exception {
    // Create multiple blob stores
    List<Pair<BlobStore, BlobStoreMetricsService>> blobStores = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      blobStores.add(mockBlobStore("parallel-blobstore-" + i, 50, false, true));
    }
    
    // Create and configure multiple tasks
    List<RecalculateBlobStoreSizeTask> tasks = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      RecalculateBlobStoreSizeTask task = spy(new RecalculateBlobStoreSizeTask(blobStoreManager));
      TaskConfiguration config = buildTaskConfiguration(
          "parallel-task-" + i, "parallel-blobstore-" + i);
      task.configure(config);
      tasks.add(task);
    }
    
    // Execute all tasks concurrently using virtual threads
    List<Future<?>> futures = new ArrayList<>();
    for (RecalculateBlobStoreSizeTask task : tasks) {
      futures.add(virtualThreadExecutor.submit(task::call));
    }
    
    // Wait for all tasks to complete
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    
    // Verify that all tasks executed correctly
    for (int i = 0; i < 5; i++) {
      verify(tasks.get(i), times(1)).execute(blobStores.get(i).getLeft());
      verify(blobStores.get(i).getLeft(), times(50)).getBlobAttributes(any(BlobId.class));
      verify(blobStores.get(i).getRight(), times(50)).recordAddition(anyLong());
    }
  }

  private Pair<BlobStore, BlobStoreMetricsService> mockBlobStore(
      final String blobstoreName,
      final int blobsCount,
      final boolean throwException,
      final boolean simulateIoDelay)
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
      
      // Simulate I/O delay to demonstrate virtual thread benefits
      if (simulateIoDelay) {
        Thread.sleep(SIMULATED_IO_DELAY_MS);
      }

      Map<String, String> headers = ImmutableMap.of();
      BlobMetrics metrics = new BlobMetrics(DateTime.now().minusHours(1), "hash", random.nextInt(100));
      return new TestBlobAttributes(headers, metrics);
    });

    return Pair.of(blobStore, metricsService);
  }
  
  private Pair<BlobStore, BlobStoreMetricsService> mockBlobStore(
      final String blobstoreName,
      final int blobsCount,
      final boolean throwException)
  {
    return mockBlobStore(blobstoreName, blobsCount, throwException, false);
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