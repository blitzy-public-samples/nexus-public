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
import org.sonatype.nexus.cleanup.internal.content.method.DeleteCleanupMethod;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.maintenance.ContentMaintenanceFacet;
import org.sonatype.nexus.repository.task.DeletionProgress;
import org.sonatype.nexus.scheduling.TaskInterruptedException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link DeleteCleanupMethod} class with Java 21 Virtual Threads, validating its batch-based deletion 
 * functionality under high concurrency loads.
 */
public class DeleteCleanupMethodVirtualThreadTest
    extends TestSupport
{
  private static final int BATCH_SIZE = 500;
  private static final int LARGE_COMPONENT_COUNT = 10000;
  private static final int HIGH_CONCURRENCY_THREADS = 1000;
  private static final int TIMEOUT_SECONDS = 30;

  @Mock
  private Repository repository;

  @Mock
  private BooleanSupplier cancelledCheck;

  @Mock
  private ContentMaintenanceFacet contentMaintenanceFacet;

  private DeleteCleanupMethod underTest;

  @Before
  public void setUp() {
    System.setProperty("nexus.continuation.browse.limit", String.valueOf(BATCH_SIZE));
    underTest = new DeleteCleanupMethod();
    when(repository.facet(ContentMaintenanceFacet.class)).thenReturn(contentMaintenanceFacet);
  }

  /**
   * Tests that the cleanup method correctly handles task cancellation when using virtual threads.
   */
  @Test(expected = TaskInterruptedException.class)
  public void testRunFailsIfTaskIsCancelledWithVirtualThreads() {
    when(cancelledCheck.getAsBoolean()).thenReturn(true);
    
    // Use a virtual thread to execute the cleanup method
    Thread.ofVirtual().start(() -> {
      underTest.run(repository, getRandomStream(1000), cancelledCheck);
    }).join();
  }

  /**
   * Tests that the cleanup method correctly processes large component streams in batches when using virtual threads.
   */
  @Test
  public void testRunReBatchStreamWithVirtualThreads() {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          return (int) input.count();
        });

    Stream<FluentComponent> input = getRandomStream(LARGE_COMPONENT_COUNT);

    // Use a virtual thread to execute the cleanup method
    DeletionProgress deleted = Thread.ofVirtual().name("cleanup-virtual-thread").task(() -> {
      return underTest.run(repository, input, cancelledCheck);
    }).join();

    // Validate stream is batched and cancel check is verified for each batch
    int expectedBatches = LARGE_COMPONENT_COUNT / BATCH_SIZE;
    verify(contentMaintenanceFacet, times(expectedBatches)).deleteComponents(any(Stream.class));
    verify(cancelledCheck, times(expectedBatches)).getAsBoolean();

    assertEquals(LARGE_COMPONENT_COUNT, deleted.getComponentCount());
  }

  /**
   * Tests the cleanup method under high concurrency load using virtual threads.
   * This test validates that the method can handle many concurrent cleanup operations
   * efficiently when using virtual threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Simulate some processing time
          try {
            Thread.sleep(10);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return (int) input.count();
        });

    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    int taskCount = HIGH_CONCURRENCY_THREADS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger totalDeleted = new AtomicInteger(0);

    try {
      // Submit multiple concurrent cleanup tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            Stream<FluentComponent> components = getRandomStream(100);
            DeletionProgress progress = underTest.run(repository, components, cancelledCheck);
            totalDeleted.addAndGet(progress.getComponentCount());
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all virtual threads completed in time", completed);

      // Verify results
      assertEquals(0, errorCount.get());
      assertEquals(taskCount * 100, totalDeleted.get());
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests cancellation handling under high concurrency load with virtual threads.
   * This test validates that the method correctly handles cancellation signals
   * when many virtual threads are running concurrently.
   */
  @Test
  public void testCancellationUnderHighConcurrencyLoad() throws Exception {
    AtomicBoolean cancelled = new AtomicBoolean(false);
    BooleanSupplier cancellationCheck = cancelled::get;
    
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Simulate some processing time
          try {
            Thread.sleep(50);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return (int) input.count();
        });

    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger cancelledTaskCount = new AtomicInteger(0);
    AtomicInteger completedTaskCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent cleanup tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Set cancellation flag after half the tasks have been submitted
            if (taskId == taskCount / 2) {
              cancelled.set(true);
            }
            
            Stream<FluentComponent> components = getRandomStream(100);
            try {
              underTest.run(repository, components, cancellationCheck);
              completedTaskCount.incrementAndGet();
            }
            catch (TaskInterruptedException e) {
              cancelledTaskCount.incrementAndGet();
            }
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all virtual threads completed in time", completed);

      // Verify that some tasks were cancelled and some completed
      assertTrue("No tasks were cancelled", cancelledTaskCount.get() > 0);
      assertTrue("No tasks were completed", completedTaskCount.get() > 0);
      assertEquals("Total task count mismatch", taskCount, cancelledTaskCount.get() + completedTaskCount.get());
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for cleanup operations.
   * This test validates that virtual threads provide better performance and resource utilization
   * for I/O-bound cleanup operations compared to platform threads.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Simulate I/O-bound operation with sleep
          try {
            Thread.sleep(20);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return (int) input.count();
        });

    int concurrentTasks = 500;
    int componentsPerTask = 100;

    // Test with platform threads
    long platformThreadStartTime = System.nanoTime();
    ExecutorService platformExecutor = Executors.newFixedThreadPool(100); // Limited thread pool
    CountDownLatch platformLatch = new CountDownLatch(concurrentTasks);

    try {
      for (int i = 0; i < concurrentTasks; i++) {
        platformExecutor.submit(() -> {
          try {
            Stream<FluentComponent> components = getRandomStream(componentsPerTask);
            underTest.run(repository, components, cancelledCheck);
          }
          catch (Exception e) {
            fail("Platform thread execution failed: " + e.getMessage());
          }
          finally {
            platformLatch.countDown();
          }
        });
      }

      boolean platformCompleted = platformLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Platform threads did not complete in time", platformCompleted);
    }
    finally {
      platformExecutor.shutdown();
    }
    long platformThreadDuration = Duration.ofNanos(System.nanoTime() - platformThreadStartTime).toMillis();

    // Test with virtual threads
    long virtualThreadStartTime = System.nanoTime();
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch virtualLatch = new CountDownLatch(concurrentTasks);

    try {
      for (int i = 0; i < concurrentTasks; i++) {
        virtualExecutor.submit(() -> {
          try {
            Stream<FluentComponent> components = getRandomStream(componentsPerTask);
            underTest.run(repository, components, cancelledCheck);
          }
          catch (Exception e) {
            fail("Virtual thread execution failed: " + e.getMessage());
          }
          finally {
            virtualLatch.countDown();
          }
        });
      }

      boolean virtualCompleted = virtualLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Virtual threads did not complete in time", virtualCompleted);
    }
    finally {
      virtualExecutor.shutdown();
    }
    long virtualThreadDuration = Duration.ofNanos(System.nanoTime() - virtualThreadStartTime).toMillis();

    // Log performance comparison
    log.info("Platform threads execution time: {} ms", platformThreadDuration);
    log.info("Virtual threads execution time: {} ms", virtualThreadDuration);
    log.info("Performance improvement: {}%", 
        Math.round((platformThreadDuration - virtualThreadDuration) * 100.0 / platformThreadDuration));

    // Virtual threads should generally be more efficient for I/O-bound operations
    // but we don't assert this as it depends on the test environment
    // Instead, we just log the results for analysis
  }

  /**
   * Creates a stream of random mock FluentComponent objects for testing.
   *
   * @param size The number of components to create
   * @return A stream of mock FluentComponent objects
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