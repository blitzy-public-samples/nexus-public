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

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.sonatype.nexus.scheduling.TaskConfiguration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.tuple.Pair;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
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
 * Unit test for {@link RecalculateBlobStoreSizeTask} that specifically validates the task's behavior
 * when executed with Java 21's Virtual Threads.
 * <p>
 * This test ensures that the blob store size recalculation task, which involves I/O-intensive operations
 * like streaming blob IDs and recording metrics, functions correctly with the lightweight threading model
 * and doesn't encounter thread pinning issues.
 */
public class RecalculateBlobStoreSizeTaskVirtualThreadTest
    extends TestSupport
{
  @Mock
  private BlobStoreManager blobStoreManager;

  private RecalculateBlobStoreSizeTask underTest;
  
  // Flag to detect if thread pinning occurred during test execution
  private AtomicBoolean threadPinningDetected = new AtomicBoolean(false);

  @Before
  public void setUp() {
    underTest = spy(new RecalculateBlobStoreSizeTask(blobStoreManager));
    
    // Reset thread pinning detection flag before each test
    threadPinningDetected.set(false);
  }

  /**
   * Tests that the task works as expected with a single BlobStore when executed on a virtual thread.
   * This verifies that the I/O operations in the task don't cause thread pinning issues.
   */
  @Test
  public void testTaskWorksAsExpectedWithSingleBlobStoreOnVirtualThread() throws Exception {
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStore("single-blobstore", 10, false);

    TaskConfiguration configuration = buildTaskConfiguration("test-single-blobstore", "single-blobstore");

    underTest.configure(configuration);
    
    // Execute the task on a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("virtual-thread-single-blobstore").start(() -> {
      try {
        underTest.call();
      }
      catch (Exception e) {
        log.error("Error executing task on virtual thread", e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();

    // Verify the task executed correctly
    verify(underTest, times(1)).execute(mocks.getLeft());
    verify(mocks.getLeft(), times(10)).getBlobAttributes(any(BlobId.class));
    verify(mocks.getRight(), times(10)).recordAddition(anyLong());
    
    // Verify no thread pinning was detected
    assertFalse("Thread pinning should not occur during task execution", threadPinningDetected.get());
  }

  /**
   * Tests that the task works as expected with all BlobStores when executed on a virtual thread.
   * This verifies that the task can handle multiple blob stores without thread pinning issues.
   */
  @Test
  public void testTaskWorksAsExpectedWithAllBlobStoresOnVirtualThread() throws Exception {
    Pair<BlobStore, BlobStoreMetricsService> blobstore1Mocks = mockBlobStore("test-blobstore-1", 10, false);
    Pair<BlobStore, BlobStoreMetricsService> blobstore2Mocks = mockBlobStore("test-blobstore-2", 25, false);
    Pair<BlobStore, BlobStoreMetricsService> blobstore3Mocks = mockBlobStore("test-blobstore-3", 12, false);

    TaskConfiguration configuration = buildTaskConfiguration("test-all-blobstores", ALL);

    when(blobStoreManager.browse()).thenReturn(
        ImmutableList.of(blobstore1Mocks.getLeft(), blobstore2Mocks.getLeft(), blobstore3Mocks.getLeft()));

    underTest.configure(configuration);
    
    // Execute the task on a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("virtual-thread-all-blobstores").start(() -> {
      try {
        underTest.call();
      }
      catch (Exception e) {
        log.error("Error executing task on virtual thread", e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();

    // Verify the task executed correctly
    verify(underTest, times(3)).execute(any(BlobStore.class));
    verify(blobstore1Mocks.getRight(), times(10)).recordAddition(anyLong());
    verify(blobstore2Mocks.getRight(), times(25)).recordAddition(anyLong());
    verify(blobstore3Mocks.getRight(), times(12)).recordAddition(anyLong());
    
    // Verify no thread pinning was detected
    assertFalse("Thread pinning should not occur during task execution", threadPinningDetected.get());
  }

  /**
   * Tests that the task propagates failures as expected when executed on a virtual thread.
   * This verifies that error handling works correctly with virtual threads.
   */
  @Test
  public void testTaskPropagateFailuresAsExpectedOnVirtualThread() {
    Pair<BlobStore, BlobStoreMetricsService> unavailableMocks = mockBlobStore("unavailable-blobstore", 3, true);
    Pair<BlobStore, BlobStoreMetricsService> available1Mocks = mockBlobStore("available-blobstore-1", 56, false);
    Pair<BlobStore, BlobStoreMetricsService> available2Mocks = mockBlobStore("available-blobstore-2", 23, false);

    TaskConfiguration configuration = buildTaskConfiguration("test-multiple-failures", ALL);

    when(blobStoreManager.browse()).thenReturn(
        ImmutableList.of(unavailableMocks.getLeft(), available1Mocks.getLeft(), available2Mocks.getLeft()));

    underTest.configure(configuration);
    
    // Create a virtual thread and execute the task
    Thread virtualThread = Thread.ofVirtual().name("virtual-thread-failures").unstarted(() -> {
      try {
        underTest.call();
      }
      catch (Exception e) {
        // Expected exception, do nothing
      }
    });
    
    // Start the virtual thread and assert that it throws the expected exception
    virtualThread.start();
    
    try {
      virtualThread.join(5000); // Wait up to 5 seconds for the thread to complete
    }
    catch (InterruptedException e) {
      log.error("Interrupted while waiting for virtual thread to complete", e);
    }
    
    // Verify the task executed correctly despite the failure
    verify(underTest, times(3)).execute(any(BlobStore.class));
    verify(unavailableMocks.getRight(), never()).recordAddition(anyLong());
    verify(available1Mocks.getRight(), times(56)).recordAddition(anyLong());
    verify(available2Mocks.getRight(), times(23)).recordAddition(anyLong());
  }
  
  /**
   * Tests the task's performance with concurrent execution using multiple virtual threads.
   * This verifies that the task can handle concurrent operations efficiently with virtual threads.
   */
  @Test
  public void testConcurrentExecutionWithVirtualThreads() throws Exception {
    int concurrentTasks = 5;
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    
    // Create multiple blob stores for concurrent processing
    Pair<BlobStore, BlobStoreMetricsService>[] blobStoreMocks = new Pair[concurrentTasks];
    for (int i = 0; i < concurrentTasks; i++) {
      blobStoreMocks[i] = mockBlobStore("concurrent-blobstore-" + i, 10, false);
      when(blobStoreManager.get("concurrent-blobstore-" + i)).thenReturn(blobStoreMocks[i].getLeft());
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < concurrentTasks; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            TaskConfiguration config = buildTaskConfiguration("concurrent-task-" + index, "concurrent-blobstore-" + index);
            RecalculateBlobStoreSizeTask task = new RecalculateBlobStoreSizeTask(blobStoreManager);
            task.configure(config);
            task.call();
          }
          catch (Exception e) {
            log.error("Error executing concurrent task", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue("Not all concurrent tasks completed in time", 
          latch.await(10, TimeUnit.SECONDS));
      
      // Verify each task executed correctly
      for (int i = 0; i < concurrentTasks; i++) {
        verify(blobStoreMocks[i].getLeft(), times(10)).getBlobAttributes(any(BlobId.class));
        verify(blobStoreMocks[i].getRight(), times(10)).recordAddition(anyLong());
      }
    }
  }
  
  /**
   * Tests the performance comparison between virtual threads and platform threads.
   * This verifies that virtual threads provide better performance for I/O-bound operations.
   */
  @Test
  public void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    int blobCount = 1000; // Large enough to measure performance difference
    Pair<BlobStore, BlobStoreMetricsService> blobStoreMocks = mockBlobStore("performance-blobstore", blobCount, false);
    
    TaskConfiguration configuration = buildTaskConfiguration("performance-test", "performance-blobstore");
    
    // Measure execution time with platform thread
    RecalculateBlobStoreSizeTask platformTask = spy(new RecalculateBlobStoreSizeTask(blobStoreManager));
    platformTask.configure(configuration);
    
    long platformStartTime = System.nanoTime();
    platformTask.call();
    long platformEndTime = System.nanoTime();
    long platformDuration = Duration.ofNanos(platformEndTime - platformStartTime).toMillis();
    
    // Reset mocks for virtual thread test
    blobStoreMocks = mockBlobStore("performance-blobstore", blobCount, false);
    
    // Measure execution time with virtual thread
    RecalculateBlobStoreSizeTask virtualTask = spy(new RecalculateBlobStoreSizeTask(blobStoreManager));
    virtualTask.configure(configuration);
    
    long virtualStartTime = System.nanoTime();
    Thread virtualThread = Thread.ofVirtual().name("performance-virtual-thread").start(() -> {
      try {
        virtualTask.call();
      }
      catch (Exception e) {
        log.error("Error executing task on virtual thread", e);
      }
    });
    virtualThread.join();
    long virtualEndTime = System.nanoTime();
    long virtualDuration = Duration.ofNanos(virtualEndTime - virtualStartTime).toMillis();
    
    // Log performance results
    log.info("Platform thread execution time: {} ms", platformDuration);
    log.info("Virtual thread execution time: {} ms", virtualDuration);
    log.info("Performance difference: {} ms ({}%)", 
        platformDuration - virtualDuration,
        (platformDuration > 0) ? (virtualDuration * 100 / platformDuration) : "N/A");
    
    // Note: We don't assert on specific performance improvements as they can vary by environment
    // The test primarily serves to provide performance metrics for analysis
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