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

import java.lang.Thread.Builder.OfVirtual;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.sonatype.nexus.cleanup.internal.content.method.DeleteCleanupMethod;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.maintenance.ContentMaintenanceFacet;
import org.sonatype.nexus.repository.task.DeletionProgress;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers.isNotPinned;
import static org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers.isVirtualThread;

/**
 * Tests to detect thread pinning issues in the cleanup component when using Java 21 Virtual Threads.
 * <p>
 * These tests monitor operations that could cause carrier thread blocking during cleanup operations,
 * analyze potential pinning events, and validate that the cleanup implementation avoids problematic
 * synchronization patterns.
 */
public class CleanupThreadPinningTest
    extends VirtualThreadTestSupport
{
  private static final int BATCH_SIZE = 500;
  private static final int COMPONENT_COUNT = 5000;
  private static final int CONCURRENT_OPERATIONS = 10;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  @Mock
  private Repository repository;

  @Mock
  private BooleanSupplier cancelledCheck;

  @Mock
  private ContentMaintenanceFacet contentMaintenanceFacet;

  private DeleteCleanupMethod underTest;
  private ThreadPinningDetector pinningDetector;
  private ExecutorService virtualThreadExecutor;

  @BeforeClass
  public static void checkVirtualThreadSupport() {
    // Skip tests if running on a JVM that doesn't support Virtual Threads
    Assume.assumeTrue("These tests require Java 21 or later with Virtual Thread support",
        Thread.class.isRecord() || Thread.class.getModule().getName().equals("java.base"));
    
    // Enable JVM thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
  }

  @Before
  public void setUp() {
    System.setProperty("nexus.continuation.browse.limit", String.valueOf(BATCH_SIZE));
    underTest = new DeleteCleanupMethod();
    when(repository.facet(ContentMaintenanceFacet.class)).thenReturn(contentMaintenanceFacet);
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    
    // Setup thread pinning detector
    pinningDetector = new ThreadPinningDetector();
    pinningDetector.startMonitoring();
    
    // Create a virtual thread executor for concurrent testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @After
  public void tearDown() throws Exception {
    pinningDetector.stopMonitoring();
    virtualThreadExecutor.shutdownNow();
    virtualThreadExecutor.awaitTermination(5, SECONDS);
  }

  /**
   * Tests that cleanup operations can be executed on Virtual Threads without pinning.
   * This verifies that the DeleteCleanupMethod implementation is compatible with Virtual Threads
   * and doesn't contain synchronization patterns that would cause thread pinning.
   */
  @Test
  public void testCleanupOperationsDoNotCauseThreadPinning() throws Exception {
    // Configure mock to simulate component deletion without blocking
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          return (int) input.count();
        });

    // Create a virtual thread to run the cleanup operation
    Thread cleanupThread = Thread.ofVirtual().name("cleanup-test-thread").start(() -> {
      Stream<FluentComponent> input = getRandomStream(COMPONENT_COUNT);
      DeletionProgress deleted = underTest.run(repository, input, cancelledCheck);
      assertThat(deleted.getComponentCount(), is(COMPONENT_COUNT));
    });

    // Wait for the thread to complete
    cleanupThread.join(TEST_TIMEOUT.toMillis());
    
    // Verify the thread was a virtual thread and wasn't pinned
    assertThat(cleanupThread, isVirtualThread());
    assertThat(cleanupThread, isNotPinned());
    
    // Verify the expected number of batch operations occurred
    int expectedBatches = COMPONENT_COUNT / BATCH_SIZE;
    verify(contentMaintenanceFacet, times(expectedBatches)).deleteComponents(any(Stream.class));
  }

  /**
   * Tests cleanup operations under high concurrency using Virtual Threads.
   * This verifies that the DeleteCleanupMethod can handle multiple concurrent cleanup operations
   * without thread pinning or other concurrency issues.
   */
  @Test
  public void testConcurrentCleanupOperationsWithVirtualThreads() throws Exception {
    // Configure mock to simulate component deletion with a small delay to increase concurrency
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Add a small delay to simulate I/O operations
          Thread.sleep(10);
          return (int) input.count();
        });

    // Create a countdown latch to synchronize the start of all threads
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Submit multiple concurrent cleanup operations
    List<Future<DeletionProgress>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      futures.add(virtualThreadExecutor.submit(() -> {
        // Wait for the signal to start
        startLatch.await();
        
        // Run the cleanup operation
        Stream<FluentComponent> input = getRandomStream(COMPONENT_COUNT);
        return underTest.run(repository, input, cancelledCheck);
      }));
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete
    for (Future<DeletionProgress> future : futures) {
      DeletionProgress result = future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(result.getComponentCount(), is(COMPONENT_COUNT));
    }
    
    // Check for thread pinning events
    int pinningEvents = pinningDetector.getPinningEvents().size();
    assertThat("Cleanup operations should not cause thread pinning", pinningEvents, is(0));
  }

  /**
   * Tests that cleanup operations complete faster with Virtual Threads than with platform threads
   * under high concurrency conditions. This verifies that the DeleteCleanupMethod implementation
   * can take advantage of Virtual Threads for improved performance.
   */
  @Test
  public void testCleanupPerformanceWithVirtualThreads() throws Exception {
    // Configure mock to simulate component deletion with I/O delay
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Simulate I/O operations with a delay
          Thread.sleep(50);
          return (int) input.count();
        });

    // Measure time with platform threads
    long platformThreadTime = measureCleanupTime(Thread.ofPlatform().factory());
    
    // Measure time with virtual threads
    long virtualThreadTime = measureCleanupTime(Thread.ofVirtual().factory());
    
    // Virtual threads should be faster due to more efficient scheduling
    assertThat("Virtual threads should complete cleanup operations faster than platform threads",
        virtualThreadTime, lessThan(platformThreadTime));
  }

  /**
   * Measures the time taken to complete multiple concurrent cleanup operations using the specified thread factory.
   */
  private long measureCleanupTime(OfVirtual threadFactory) throws Exception {
    // Create threads for concurrent cleanup operations
    Thread[] threads = new Thread[CONCURRENT_OPERATIONS];
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      threads[i] = threadFactory.name("cleanup-perf-" + i).start(() -> {
        try {
          startLatch.await();
          Stream<FluentComponent> input = getRandomStream(COMPONENT_COUNT / 10); // Smaller size for performance test
          underTest.run(repository, input, cancelledCheck);
        }
        catch (Exception e) {
          // Log and continue
          log.error("Error in cleanup operation", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start timing
    long startTime = System.currentTimeMillis();
    startLatch.countDown();
    
    // Wait for all operations to complete
    completionLatch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    long endTime = System.currentTimeMillis();
    
    return endTime - startTime;
  }

  /**
   * Creates a stream of random FluentComponent mocks for testing.
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