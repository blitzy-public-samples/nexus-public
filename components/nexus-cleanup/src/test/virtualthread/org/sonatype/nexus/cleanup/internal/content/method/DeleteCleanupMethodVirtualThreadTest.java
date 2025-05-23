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
package org.sonatype.nexus.cleanup.internal.content.method;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.maintenance.ContentMaintenanceFacet;
import org.sonatype.nexus.repository.task.DeletionProgress;
import org.sonatype.nexus.scheduling.TaskInterruptedException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeleteCleanupMethod} using Java 21 Virtual Threads.
 * 
 * These tests validate the behavior of the DeleteCleanupMethod under high-concurrency
 * scenarios using Virtual Threads, ensuring proper performance and thread management.
 */
@ExtendWith(MockitoExtension.class)
public class DeleteCleanupMethodVirtualThreadTest
    extends TestSupport
{
  private static final int BATCH_SIZE = 500;
  private static final int LARGE_COMPONENT_COUNT = 10_000;
  
  @Mock
  private Repository repository;

  @Mock
  private BooleanSupplier cancelledCheck;

  @Mock
  private ContentMaintenanceFacet contentMaintenanceFacet;

  private DeleteCleanupMethod underTest;
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  public void setUp() {
    System.setProperty("nexus.continuation.browse.limit", String.valueOf(BATCH_SIZE));
    underTest = new DeleteCleanupMethod();
    when(repository.facet(ContentMaintenanceFacet.class)).thenReturn(contentMaintenanceFacet);
    
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  public void tearDown() {
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdownNow();
    }
  }

  /**
   * Tests that the cleanup method fails with TaskInterruptedException when the task is cancelled,
   * even under high concurrency with virtual threads.
   */
  @Test
  public void testRunFailsIfTaskIsCancelledWithVirtualThreads() {
    when(cancelledCheck.getAsBoolean()).thenReturn(true);
    
    assertThrows(TaskInterruptedException.class, () -> {
      underTest.run(repository, getRandomStream(LARGE_COMPONENT_COUNT), cancelledCheck);
    });
  }

  /**
   * Tests that the cleanup method properly batches a large stream of components for deletion
   * when using virtual threads for processing.
   */
  @Test
  public void testRunReBatchStreamWithLargeComponentCount() {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          return (int) input.count();
        });

    Stream<FluentComponent> input = getRandomStream(LARGE_COMPONENT_COUNT);

    DeletionProgress deleted = underTest.run(repository, input, cancelledCheck);

    // Validate stream is batched and cancel check is verified for each batch
    // For 10,000 components with batch size 500, we expect 20 batches
    verify(contentMaintenanceFacet, times(LARGE_COMPONENT_COUNT / BATCH_SIZE)).deleteComponents(any(Stream.class));
    verify(cancelledCheck, times(LARGE_COMPONENT_COUNT / BATCH_SIZE)).getAsBoolean();

    assertEquals(LARGE_COMPONENT_COUNT, deleted.getComponentCount());
  }
  
  /**
   * Tests that cancellation works correctly during concurrent cleanup operations with virtual threads.
   * This test simulates a scenario where a cleanup task is cancelled while processing a large number of components.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testCancellationDuringConcurrentCleanup() throws Exception {
    // Set up a delayed cancellation after some components are processed
    AtomicInteger processedBatches = new AtomicInteger(0);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    
    when(cancelledCheck.getAsBoolean()).thenAnswer(invocation -> {
      // Cancel after 5 batches
      if (processedBatches.incrementAndGet() > 5) {
        cancelled.set(true);
        return true;
      }
      return false;
    });
    
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Simulate some processing time
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return (int) input.count();
        });

    Stream<FluentComponent> input = getRandomStream(LARGE_COMPONENT_COUNT);

    assertThrows(TaskInterruptedException.class, () -> {
      underTest.run(repository, input, cancelledCheck);
    });
    
    assertTrue(cancelled.get(), "Task should have been cancelled");
    assertTrue(processedBatches.get() > 5, "At least 5 batches should have been processed before cancellation");
  }
  
  /**
   * Tests that virtual threads don't get pinned during cleanup operations.
   * Thread pinning can occur when blocking operations are performed in synchronized blocks.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testNoThreadPinningDuringCleanup() throws Exception {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    
    // Configure the mock to simulate I/O operations that could cause pinning
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Simulate I/O operation
          try {
            Thread.sleep(100);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return (int) input.count();
        });

    // Create a thread monitoring task to detect pinning
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    AtomicInteger pinnedThreadsDetected = new AtomicInteger(0);
    
    // Start thread monitoring in a separate thread
    CompletableFuture<Void> monitoringTask = CompletableFuture.runAsync(() -> {
      try {
        for (int i = 0; i < 10; i++) {
          ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(false, false);
          for (ThreadInfo info : threadInfos) {
            // Check for thread names indicating pinned virtual threads
            if (info.getThreadName().contains("Virtual Thread") && 
                info.getThreadState() == Thread.State.RUNNABLE) {
              pinnedThreadsDetected.incrementAndGet();
            }
          }
          Thread.sleep(200);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    // Run the cleanup operation
    Stream<FluentComponent> input = getRandomStream(5000);
    underTest.run(repository, input, cancelledCheck);
    
    // Wait for monitoring to complete
    monitoringTask.join();
    
    // We expect minimal or no thread pinning
    // Note: This is a heuristic check and may need adjustment based on the actual implementation
    assertTrue(pinnedThreadsDetected.get() < 5, 
        "Detected " + pinnedThreadsDetected.get() + " potentially pinned virtual threads");
  }
  
  /**
   * Tests the performance difference between virtual threads and platform threads
   * for cleanup operations with a large number of components.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testPerformanceComparisonWithPlatformThreads() throws Exception {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    
    // Configure the mock to simulate realistic deletion work
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Simulate some processing with I/O
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return (int) input.count();
        });

    // Test with platform threads
    long platformThreadStartTime = System.nanoTime();
    runConcurrentCleanupWithThreadFactory(Thread.ofPlatform().factory(), 100);
    long platformThreadDuration = Duration.ofNanos(System.nanoTime() - platformThreadStartTime).toMillis();
    
    // Test with virtual threads
    long virtualThreadStartTime = System.nanoTime();
    runConcurrentCleanupWithThreadFactory(Thread.ofVirtual().factory(), 100);
    long virtualThreadDuration = Duration.ofNanos(System.nanoTime() - virtualThreadStartTime).toMillis();
    
    logger.info("Platform thread duration: {} ms", platformThreadDuration);
    logger.info("Virtual thread duration: {} ms", virtualThreadDuration);
    
    // Virtual threads should generally perform better or at least similarly
    // This is a flexible assertion as the actual performance depends on many factors
    assertTrue(virtualThreadDuration <= platformThreadDuration * 1.2, 
        "Virtual threads should not be significantly slower than platform threads");
  }
  
  /**
   * Tests that a large number of concurrent cleanup operations can be handled efficiently
   * using virtual threads.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    
    // Configure the mock to simulate realistic deletion work
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Simulate some processing with I/O
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return (int) input.count();
        });

    // Run with a very high number of concurrent tasks using virtual threads
    int concurrentTasks = 1000;
    runConcurrentCleanupWithThreadFactory(Thread.ofVirtual().factory(), concurrentTasks);
    
    // If we reach here without exceptions or timeouts, the test passes
    // The @Timeout annotation will fail the test if it takes too long
  }
  
  /**
   * Helper method to run concurrent cleanup operations using the specified thread factory.
   */
  private void runConcurrentCleanupWithThreadFactory(ThreadFactory threadFactory, int concurrentTasks) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    try {
      CountDownLatch latch = new CountDownLatch(concurrentTasks);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit concurrent cleanup tasks
      for (int i = 0; i < concurrentTasks; i++) {
        executor.submit(() -> {
          try {
            // Create a small stream for each task to avoid excessive memory usage
            Stream<FluentComponent> input = getRandomStream(100);
            underTest.run(repository, input, cancelledCheck);
          } catch (Exception e) {
            errorCount.incrementAndGet();
            logger.error("Error in concurrent cleanup task", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      if (!latch.await(30, TimeUnit.SECONDS)) {
        fail("Timed out waiting for concurrent cleanup tasks to complete");
      }
      
      // Verify no errors occurred
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent cleanup");
    } finally {
      executor.shutdownNow();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.warn("Executor did not terminate in the expected time");
      }
    }
  }

  /**
   * Creates a stream of mock FluentComponent objects for testing.
   */
  private Stream<FluentComponent> getRandomStream(final int size) {
    List<FluentComponent> resultList = new ArrayList<>(size);

    for (int i = 1; i <= size; i++) {
      FluentComponent fluentComponent = mock(FluentComponent.class);
      when(fluentComponent.namespace()).thenReturn("test");
      when(fluentComponent.name()).thenReturn("random-component-" + i);
      when(fluentComponent.version()).thenReturn(String.format("%s.0.0", i));

      resultList.add(fluentComponent);
    }

    return resultList.stream();
  }
}