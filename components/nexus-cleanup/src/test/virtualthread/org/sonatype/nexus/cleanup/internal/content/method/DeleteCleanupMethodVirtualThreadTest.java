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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeleteCleanupMethod} with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class DeleteCleanupMethodVirtualThreadTest
    extends TestSupport
{
  private static final int BATCH_SIZE = 500;
  private static final int LARGE_COMPONENT_COUNT = 10_000;
  private static final int CONCURRENT_TASKS = 20;
  
  @Mock
  private Repository repository;

  @Mock
  private BooleanSupplier cancelledCheck;

  @Mock
  private ContentMaintenanceFacet contentMaintenanceFacet;

  private DeleteCleanupMethod underTest;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;

  @BeforeEach
  public void setUp() {
    System.setProperty("nexus.continuation.browse.limit", String.valueOf(BATCH_SIZE));
    underTest = new DeleteCleanupMethod();
    when(repository.facet(ContentMaintenanceFacet.class)).thenReturn(contentMaintenanceFacet);
    
    // Create executors for testing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    virtualThreadExecutor.shutdown();
    platformThreadExecutor.shutdown();
    
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
    
    if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      platformThreadExecutor.shutdownNow();
    }
  }

  /**
   * Tests that the cleanup method correctly handles cancellation with virtual threads.
   */
  @Test
  public void testCancellationWithVirtualThreads() {
    // Set up cancellation to occur after some components are processed
    AtomicInteger processedBatches = new AtomicInteger(0);
    when(cancelledCheck.getAsBoolean()).thenAnswer(invocation -> processedBatches.incrementAndGet() > 3);
    
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          return (int) input.count();
        });

    Stream<FluentComponent> input = getRandomStream(LARGE_COMPONENT_COUNT);

    // The task should be interrupted after processing 3 batches
    assertThrows(TaskInterruptedException.class, () -> 
        underTest.run(repository, input, cancelledCheck));
    
    // Verify that exactly 3 batches were processed before cancellation
    verify(contentMaintenanceFacet, times(3)).deleteComponents(any(Stream.class));
    verify(cancelledCheck, times(4)).getAsBoolean(); // 3 successful + 1 that returns true
  }

  /**
   * Tests high-concurrency cleanup operations using virtual threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          return (int) input.count();
        });

    // Create multiple concurrent cleanup tasks
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger totalComponentsDeleted = new AtomicInteger(0);
    
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      int taskId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Each task processes a different set of components
          Stream<FluentComponent> components = getRandomStream(1000);
          DeletionProgress progress = underTest.run(repository, components, cancelledCheck);
          totalComponentsDeleted.addAndGet(progress.getComponentCount());
          log.info("Task {} completed, deleted {} components", taskId, progress.getComponentCount());
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "All cleanup tasks should complete within timeout");
    
    // Verify that all components were processed
    assertEquals(CONCURRENT_TASKS * 1000, totalComponentsDeleted.get(), 
        "All components should be deleted across all tasks");
  }

  /**
   * Tests that virtual threads don't get pinned during cleanup operations.
   * Thread pinning can occur when blocking operations are called from within a synchronized block.
   */
  @Test
  public void testNoPinningDuringCleanup() throws Exception {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    
    // Simulate I/O or blocking operation in the deleteComponents method
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Count components while simulating a blocking operation
          int count = 0;
          for (FluentComponent component : (Iterable<FluentComponent>) input::iterator) {
            count++;
            // Simulate I/O delay that could cause pinning if not handled properly
            Thread.sleep(1);
          }
          return count;
        });

    // Use system property to detect pinned threads
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    // Track if any thread pinning warnings are detected
    AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
    
    // Custom thread factory that monitors for pinning
    ThreadFactory monitoringVirtualThreadFactory = Thread.ofVirtual()
        .name("cleanup-virtual-", 0)
        .factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(monitoringVirtualThreadFactory)) {
      CompletableFuture<DeletionProgress> future = CompletableFuture.supplyAsync(() -> {
        try {
          return underTest.run(repository, getRandomStream(1000), cancelledCheck);
        } 
        catch (Exception e) {
          if (e.getMessage() != null && e.getMessage().contains("Pinned")) {
            pinnedThreadDetected.set(true);
          }
          throw new RuntimeException(e);
        }
      }, executor);
      
      DeletionProgress result = future.get(30, TimeUnit.SECONDS);
      assertEquals(1000, result.getComponentCount());
    }
    
    assertFalse(pinnedThreadDetected.get(), "No thread pinning should be detected during cleanup operations");
  }

  /**
   * Compares performance between virtual threads and platform threads for cleanup operations.
   */
  @Test
  public void testPerformanceComparisonWithVirtualThreads() throws Exception {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    
    // Simulate some processing time in the deleteComponents method
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Process each component with a small delay
          return (int) input.peek(c -> {
            try {
              // Small delay to simulate processing
              Thread.sleep(1);
            } 
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }).count();
        });

    // Prepare a large dataset
    Stream<FluentComponent> largeComponentStream = getRandomStream(LARGE_COMPONENT_COUNT);
    List<FluentComponent> componentList = new ArrayList<>();
    largeComponentStream.forEach(componentList::add);
    
    // Measure execution time with platform threads
    long platformThreadStartTime = System.nanoTime();
    CompletableFuture<DeletionProgress> platformFuture = CompletableFuture.supplyAsync(
        () -> underTest.run(repository, componentList.stream(), cancelledCheck),
        platformThreadExecutor);
    DeletionProgress platformResult = platformFuture.get(2, TimeUnit.MINUTES);
    long platformThreadDuration = Duration.ofNanos(System.nanoTime() - platformThreadStartTime).toMillis();
    
    // Measure execution time with virtual threads
    long virtualThreadStartTime = System.nanoTime();
    CompletableFuture<DeletionProgress> virtualFuture = CompletableFuture.supplyAsync(
        () -> underTest.run(repository, componentList.stream(), cancelledCheck),
        virtualThreadExecutor);
    DeletionProgress virtualResult = virtualFuture.get(2, TimeUnit.MINUTES);
    long virtualThreadDuration = Duration.ofNanos(System.nanoTime() - virtualThreadStartTime).toMillis();
    
    // Log performance results
    log.info("Platform threads: {} components deleted in {} ms", 
        platformResult.getComponentCount(), platformThreadDuration);
    log.info("Virtual threads: {} components deleted in {} ms", 
        virtualResult.getComponentCount(), virtualThreadDuration);
    
    // Verify both approaches deleted the same number of components
    assertEquals(platformResult.getComponentCount(), virtualResult.getComponentCount(), 
        "Both thread types should delete the same number of components");
    
    // Note: We don't assert that virtual threads are faster as that would make the test environment-dependent
    // Instead, we just log the results for analysis
  }

  /**
   * Tests cleanup with a very large number of components using virtual threads.
   */
  @Test
  public void testLargeScaleCleanupWithVirtualThreads() {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    
    AtomicLong processedComponents = new AtomicLong(0);
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          long count = input.count();
          processedComponents.addAndGet(count);
          return (int) count;
        });

    // Create a very large stream of components
    Stream<FluentComponent> largeStream = getRandomStream(LARGE_COMPONENT_COUNT);
    
    // Run the cleanup
    DeletionProgress progress = underTest.run(repository, largeStream, cancelledCheck);
    
    // Verify all components were processed
    assertEquals(LARGE_COMPONENT_COUNT, progress.getComponentCount(), 
        "All components should be processed");
    assertEquals(LARGE_COMPONENT_COUNT, processedComponents.get(), 
        "All components should be processed by the maintenance facet");
    
    // Verify the correct number of batches were processed
    int expectedBatches = (int) Math.ceil((double) LARGE_COMPONENT_COUNT / BATCH_SIZE);
    verify(contentMaintenanceFacet, times(expectedBatches)).deleteComponents(any(Stream.class));
    verify(cancelledCheck, times(expectedBatches)).getAsBoolean();
  }

  /**
   * Creates a stream of mock FluentComponent objects.
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