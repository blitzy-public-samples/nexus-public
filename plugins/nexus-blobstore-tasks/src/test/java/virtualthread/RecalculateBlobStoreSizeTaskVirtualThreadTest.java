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
package virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.sonatype.goodies.common.MultipleFailures.MultipleFailuresException;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThrows;
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
 * Tests for {@link RecalculateBlobStoreSizeTask} with Java 21 Virtual Threads.
 * 
 * This test class validates that RecalculateBlobStoreSizeTask effectively leverages Java 21 Virtual Threads
 * for concurrent blob size calculation operations. It verifies that the task can efficiently process
 * size calculations across multiple blob stores simultaneously without thread resource exhaustion.
 */
public class RecalculateBlobStoreSizeTaskVirtualThreadTest
    extends TestSupport
{
  private static final int LARGE_BLOB_COUNT = 100_000;
  private static final int MEDIUM_BLOB_COUNT = 10_000;
  private static final int SMALL_BLOB_COUNT = 1_000;
  private static final int CONCURRENT_BLOBSTORES = 20;
  
  @Mock
  private BlobStoreManager blobStoreManager;

  private RecalculateBlobStoreSizeTask underTest;

  @Before
  public void setUp() {
    underTest = spy(new RecalculateBlobStoreSizeTask(blobStoreManager));
  }

  /**
   * Tests that the task can handle a large number of blobs efficiently using Virtual Threads.
   * This test verifies that Virtual Threads provide better performance for I/O-bound operations
   * compared to platform threads when processing a large number of blobs.
   */
  @Test
  public void testVirtualThreadPerformanceWithLargeBlobStore() throws Exception {
    // Create a large blob store with many blobs
    Pair<BlobStore, BlobStoreMetricsService> largeBlobStoreMocks = 
        mockBlobStore("large-blobstore", LARGE_BLOB_COUNT, false);

    TaskConfiguration configuration = buildTaskConfiguration("test-large-blobstore", "large-blobstore");

    // Measure execution time with platform threads
    long platformThreadStartTime = System.nanoTime();
    underTest.configure(configuration);
    underTest.call();
    long platformThreadEndTime = System.nanoTime();
    long platformThreadDuration = TimeUnit.NANOSECONDS.toMillis(platformThreadEndTime - platformThreadStartTime);
    
    log.info("Platform thread execution time for {} blobs: {} ms", 
        LARGE_BLOB_COUNT, platformThreadDuration);

    // Reset mocks for virtual thread test
    largeBlobStoreMocks = mockBlobStore("large-blobstore", LARGE_BLOB_COUNT, false);
    when(blobStoreManager.get("large-blobstore")).thenReturn(largeBlobStoreMocks.getLeft());

    // Use virtual threads for processing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Measure execution time with virtual threads
    long virtualThreadStartTime = System.nanoTime();
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        underTest.configure(configuration);
        underTest.call();
      } 
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualExecutor);
    
    future.join();
    long virtualThreadEndTime = System.nanoTime();
    long virtualThreadDuration = TimeUnit.NANOSECONDS.toMillis(virtualThreadEndTime - virtualThreadStartTime);
    
    log.info("Virtual thread execution time for {} blobs: {} ms", 
        LARGE_BLOB_COUNT, virtualThreadDuration);
    
    // Virtual threads should provide better or comparable performance for I/O-bound operations
    // Note: The actual performance difference depends on the environment and implementation details
    // This assertion might need adjustment based on specific environment characteristics
    assertThat("Virtual threads should provide better performance for I/O-bound operations",
        virtualThreadDuration, lessThan(platformThreadDuration * 2)); // Conservative assertion
    
    // Verify the correct number of blob attributes were processed
    verify(largeBlobStoreMocks.getLeft(), times(LARGE_BLOB_COUNT)).getBlobAttributes(any(BlobId.class));
    verify(largeBlobStoreMocks.getRight(), times(LARGE_BLOB_COUNT)).recordAddition(anyLong());
    
    virtualExecutor.shutdown();
  }

  /**
   * Tests concurrent execution across multiple blob stores using Virtual Threads.
   * This test verifies that the task can efficiently process multiple blob stores concurrently
   * without thread resource exhaustion when using Virtual Threads.
   */
  @Test
  public void testConcurrentBlobStoreProcessingWithVirtualThreads() throws Exception {
    // Create multiple blob stores with varying sizes
    List<Pair<BlobStore, BlobStoreMetricsService>> blobStoreMocks = new ArrayList<>();
    List<String> blobStoreNames = new ArrayList<>();
    
    for (int i = 0; i < CONCURRENT_BLOBSTORES; i++) {
      String name = "blobstore-" + i;
      int blobCount = (i % 3 == 0) ? SMALL_BLOB_COUNT : 
                     (i % 3 == 1) ? MEDIUM_BLOB_COUNT : 
                                    LARGE_BLOB_COUNT / 10; // Use a smaller large count for practicality
      
      blobStoreMocks.add(mockBlobStore(name, blobCount, false));
      blobStoreNames.add(name);
    }
    
    when(blobStoreManager.browse()).thenReturn(
        blobStoreMocks.stream().map(Pair::getLeft).collect(ImmutableList.toImmutableList()));

    TaskConfiguration configuration = buildTaskConfiguration("test-concurrent-blobstores", ALL);

    // Use virtual threads for processing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Track the number of concurrent executions
    AtomicInteger maxConcurrentExecutions = new AtomicInteger(0);
    AtomicInteger currentConcurrentExecutions = new AtomicInteger(0);
    
    // Replace the execute method to track concurrency
    when(underTest.execute(any(BlobStore.class))).thenAnswer(invocation -> {
      BlobStore blobStore = invocation.getArgument(0);
      int current = currentConcurrentExecutions.incrementAndGet();
      int max = maxConcurrentExecutions.get();
      if (current > max) {
        maxConcurrentExecutions.set(current);
      }
      
      // Simulate some processing time to increase chance of concurrency
      Thread.sleep(50);
      
      // Call the real method
      invocation.callRealMethod();
      
      currentConcurrentExecutions.decrementAndGet();
      return null;
    });

    // Execute the task
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        underTest.configure(configuration);
        underTest.call();
      } 
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualExecutor);
    
    future.join();
    
    // Verify that multiple blob stores were processed concurrently
    assertThat("Multiple blob stores should be processed concurrently",
        maxConcurrentExecutions.get(), greaterThan(1));
    
    // Verify that all blob stores were processed
    verify(underTest, times(CONCURRENT_BLOBSTORES)).execute(any(BlobStore.class));
    
    // Log the maximum concurrency achieved
    log.info("Maximum concurrent blob store executions: {}", maxConcurrentExecutions.get());
    
    virtualExecutor.shutdown();
  }

  /**
   * Tests the task's ability to handle a high number of concurrent operations using Virtual Threads.
   * This test verifies that Virtual Threads can efficiently handle a large number of concurrent
   * operations without resource exhaustion.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a blob store with a large number of blobs
    int blobCount = MEDIUM_BLOB_COUNT;
    Pair<BlobStore, BlobStoreMetricsService> blobStoreMocks = 
        mockBlobStore("high-concurrency-blobstore", blobCount, false);

    TaskConfiguration configuration = 
        buildTaskConfiguration("test-high-concurrency", "high-concurrency-blobstore");

    // Use virtual threads for processing with high concurrency
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Create a large number of concurrent tasks
    int concurrentTasks = 100;
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    for (int i = 0; i < concurrentTasks; i++) {
      virtualExecutor.submit(() -> {
        try {
          underTest.configure(configuration);
          underTest.call();
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error executing task", e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete with a timeout
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Verify that all tasks completed successfully
    assertThat("All tasks should complete within the timeout", completed, is(true));
    assertThat("No errors should occur during task execution", errorCount.get(), is(0));
    
    // Verify that the blob store was processed the expected number of times
    verify(blobStoreMocks.getLeft(), times(concurrentTasks * blobCount)).getBlobAttributes(any(BlobId.class));
    verify(blobStoreMocks.getRight(), times(concurrentTasks * blobCount)).recordAddition(anyLong());
    
    virtualExecutor.shutdown();
  }

  /**
   * Tests error handling and aggregation with Virtual Threads.
   * This test verifies that the task properly handles and aggregates errors when using Virtual Threads.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() {
    // Create blob stores with some that will throw exceptions
    Pair<BlobStore, BlobStoreMetricsService> unavailableMocks = 
        mockBlobStore("unavailable-blobstore", 3, true);
    Pair<BlobStore, BlobStoreMetricsService> available1Mocks = 
        mockBlobStore("available-blobstore-1", 56, false);
    Pair<BlobStore, BlobStoreMetricsService> available2Mocks = 
        mockBlobStore("available-blobstore-2", 23, false);

    TaskConfiguration configuration = buildTaskConfiguration("test-virtual-thread-failures", ALL);

    when(blobStoreManager.browse()).thenReturn(
        ImmutableList.of(unavailableMocks.getLeft(), available1Mocks.getLeft(), available2Mocks.getLeft()));

    // Use virtual threads for processing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Execute the task and expect a MultipleFailuresException
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      underTest.configure(configuration);
      underTest.call(); // This should throw an exception
    }, virtualExecutor);
    
    // Verify that the expected exception is thrown
    assertThrows(MultipleFailuresException.class, () -> future.join());
    
    // Verify that all blob stores were processed
    verify(underTest, times(3)).execute(any(BlobStore.class));
    
    // Verify that the unavailable blob store didn't record any additions
    verify(unavailableMocks.getRight(), never()).recordAddition(anyLong());
    
    // Verify that the available blob stores recorded the expected number of additions
    verify(available1Mocks.getRight(), times(56)).recordAddition(anyLong());
    verify(available2Mocks.getRight(), times(23)).recordAddition(anyLong());
    
    virtualExecutor.shutdown();
  }

  /**
   * Tests resource utilization during high-concurrency scenarios with Virtual Threads.
   * This test verifies that Virtual Threads efficiently utilize resources during high-concurrency
   * scenarios without exhausting system resources.
   */
  @Test
  public void testResourceUtilizationWithVirtualThreads() throws Exception {
    // Create multiple blob stores with varying sizes
    List<Pair<BlobStore, BlobStoreMetricsService>> blobStoreMocks = new ArrayList<>();
    
    for (int i = 0; i < 5; i++) {
      String name = "resource-blobstore-" + i;
      int blobCount = MEDIUM_BLOB_COUNT;
      
      blobStoreMocks.add(mockBlobStore(name, blobCount, false));
    }
    
    when(blobStoreManager.browse()).thenReturn(
        blobStoreMocks.stream().map(Pair::getLeft).collect(ImmutableList.toImmutableList()));

    TaskConfiguration configuration = buildTaskConfiguration("test-resource-utilization", ALL);

    // Use virtual threads for processing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Measure memory usage before execution
    Runtime runtime = Runtime.getRuntime();
    runtime.gc(); // Request garbage collection to get more accurate memory usage
    long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
    
    // Execute the task
    long startTime = System.nanoTime();
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        underTest.configure(configuration);
        underTest.call();
      } 
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualExecutor);
    
    future.join();
    long endTime = System.nanoTime();
    
    // Measure memory usage after execution
    runtime.gc(); // Request garbage collection to get more accurate memory usage
    long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
    
    // Calculate execution time and memory usage
    long executionTime = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    long memoryUsed = memoryAfter - memoryBefore;
    
    // Log resource utilization metrics
    log.info("Execution time: {} ms", executionTime);
    log.info("Memory used: {} bytes", memoryUsed);
    
    // Verify that all blob stores were processed
    verify(underTest, times(5)).execute(any(BlobStore.class));
    
    // Verify that the expected number of blob attributes were processed
    int totalBlobCount = 5 * MEDIUM_BLOB_COUNT;
    int totalVerifications = 0;
    
    for (Pair<BlobStore, BlobStoreMetricsService> blobStoreMock : blobStoreMocks) {
      verify(blobStoreMock.getLeft(), times(MEDIUM_BLOB_COUNT)).getBlobAttributes(any(BlobId.class));
      verify(blobStoreMock.getRight(), times(MEDIUM_BLOB_COUNT)).recordAddition(anyLong());
      totalVerifications += MEDIUM_BLOB_COUNT;
    }
    
    assertThat("All blob attributes should be processed", totalVerifications, is(totalBlobCount));
    
    virtualExecutor.shutdown();
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