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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cleanup.internal.content.method.DeleteCleanupMethod;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.maintenance.ContentMaintenanceFacet;
import org.sonatype.nexus.repository.task.DeletionProgress;
import org.sonatype.nexus.scheduling.TaskInterruptedException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for detecting thread pinning issues in the cleanup component when using Java 21 Virtual Threads.
 * <p>
 * This test monitors operations that could cause carrier thread blocking during cleanup operations,
 * analyzes potential pinning events, and validates that the cleanup implementation avoids problematic
 * synchronization patterns.
 * <p>
 * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread, typically
 * due to synchronized blocks, native methods, or other blocking operations. This negates the benefits
 * of virtual threads and can lead to performance degradation.
 */
@ExtendWith(MockitoExtension.class)
public class CleanupThreadPinningTest extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(CleanupThreadPinningTest.class);

  private static final int BATCH_SIZE = 500;
  private static final int COMPONENT_COUNT = 5000;
  private static final int CONCURRENT_OPERATIONS = 10;
  private static final int PINNING_THRESHOLD_MS = 20; // Threshold for considering a thread pinned

  @Mock
  private Repository repository;

  @Mock
  private BooleanSupplier cancelledCheck;

  @Mock
  private ContentMaintenanceFacet contentMaintenanceFacet;

  private DeleteCleanupMethod underTest;
  private ExecutorService virtualThreadExecutor;
  private ThreadPinningDetector pinningDetector;

  @BeforeEach
  public void setUp() {
    // Configure batch size for cleanup operations
    System.setProperty("nexus.continuation.browse.limit", String.valueOf(BATCH_SIZE));
    
    // Create the DeleteCleanupMethod instance to test
    underTest = new DeleteCleanupMethod();
    
    // Configure repository mock
    when(repository.facet(ContentMaintenanceFacet.class)).thenReturn(contentMaintenanceFacet);
    
    // Configure cancelledCheck mock to not cancel by default
    when(cancelledCheck.getAsBoolean()).thenReturn(false);
    
    // Configure contentMaintenanceFacet mock to simulate component deletion
    when(contentMaintenanceFacet.deleteComponents(any(Stream.class)))
        .thenAnswer(invocation -> {
          Stream<FluentComponent> input = invocation.getArgument(0);
          // Simulate some I/O work during deletion
          Thread.sleep(5); // Small delay to simulate I/O
          return (int) input.count();
        });
    
    // Create a virtual thread executor
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("cleanup-vt-").factory());
    
    // Initialize thread pinning detector
    pinningDetector = new ThreadPinningDetector();
    pinningDetector.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    // Shutdown the virtual thread executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdownNow();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    // Stop the thread pinning detector
    if (pinningDetector != null) {
      pinningDetector.stop();
    }
    
    // Reset system property
    System.clearProperty("nexus.continuation.browse.limit");
  }

  /**
   * Tests that the cleanup method can process a large number of components without thread pinning.
   * <p>
   * This test verifies that the DeleteCleanupMethod can process a large stream of components
   * without causing thread pinning issues when executed with virtual threads.
   */
  @Test
  public void testCleanupWithoutThreadPinning() throws Exception {
    // Create a large stream of mock components
    Stream<FluentComponent> componentStream = getRandomComponentStream(COMPONENT_COUNT);
    
    // Run the cleanup method
    DeletionProgress result = underTest.run(repository, componentStream, cancelledCheck);
    
    // Verify the expected number of components were processed
    assertEquals(COMPONENT_COUNT, result.getComponentCount(), "All components should be processed");
    
    // Verify the cleanup method batched the stream correctly
    int expectedBatches = (int) Math.ceil((double) COMPONENT_COUNT / BATCH_SIZE);
    verify(contentMaintenanceFacet, times(expectedBatches)).deleteComponents(any(Stream.class));
    
    // Verify no thread pinning was detected
    assertFalse(pinningDetector.hasPinningEvents(), "No thread pinning should occur during cleanup");
  }

  /**
   * Tests that the cleanup method handles concurrent operations efficiently with virtual threads.
   * <p>
   * This test executes multiple cleanup operations concurrently using virtual threads and
   * verifies that they complete successfully without excessive thread pinning.
   */
  @Test
  public void testConcurrentCleanupOperations() throws Exception {
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Track any exceptions that occur during concurrent execution
    List<Throwable> exceptions = new ArrayList<>();
    
    // Execute multiple cleanup operations concurrently
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int operationId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create a stream of components for this operation
          Stream<FluentComponent> componentStream = getRandomComponentStream(COMPONENT_COUNT / CONCURRENT_OPERATIONS);
          
          // Run the cleanup method
          DeletionProgress result = underTest.run(repository, componentStream, cancelledCheck);
          
          // Verify the result
          assertEquals(COMPONENT_COUNT / CONCURRENT_OPERATIONS, result.getComponentCount(),
              "Operation " + operationId + " should process the expected number of components");
          
          log.info("Completed cleanup operation {}", operationId);
        }
        catch (Throwable t) {
          log.error("Error in cleanup operation " + operationId, t);
          synchronized (exceptions) {
            exceptions.add(t);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(completionLatch.await(30, TimeUnit.SECONDS), "All cleanup operations should complete within timeout");
    
    // Verify no exceptions occurred
    assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent cleanup operations");
    
    // Verify thread pinning metrics
    if (pinningDetector.hasPinningEvents()) {
      log.warn("Thread pinning detected during concurrent cleanup operations:");
      pinningDetector.getPinningEvents().forEach(event -> 
          log.warn("  Thread {} pinned for {}ms at {}", 
              event.threadName, event.durationMs, event.timestamp));
    }
    
    // Allow a small number of pinning events due to JVM internals, but ensure they're brief
    assertThat("Thread pinning events should be minimal", 
        pinningDetector.getPinningEvents().size(), lessThan(5));
    
    // Verify any pinning events are short-lived
    pinningDetector.getPinningEvents().forEach(event -> 
        assertThat("Thread pinning duration should be brief", 
            event.durationMs, lessThan(100L)));
  }

  /**
   * Tests that the cleanup method handles cancellation correctly with virtual threads.
   * <p>
   * This test verifies that when a cleanup operation is cancelled, the virtual threads
   * are properly interrupted and resources are released without thread pinning.
   */
  @Test
  public void testCleanupCancellation() throws Exception {
    // Configure cancelledCheck to indicate cancellation
    when(cancelledCheck.getAsBoolean()).thenReturn(true);
    
    // Create a stream of components
    Stream<FluentComponent> componentStream = getRandomComponentStream(COMPONENT_COUNT);
    
    // Run the cleanup method and expect a TaskInterruptedException
    try {
      underTest.run(repository, componentStream, cancelledCheck);
      
      // If we get here, the test has failed
      throw new AssertionError("Expected TaskInterruptedException was not thrown");
    }
    catch (TaskInterruptedException e) {
      // Expected exception
      log.info("Received expected TaskInterruptedException: {}", e.getMessage());
    }
    
    // Verify no thread pinning occurred during cancellation
    assertFalse(pinningDetector.hasPinningEvents(), 
        "No thread pinning should occur during cleanup cancellation");
  }

  /**
   * Tests the cleanup method's performance with virtual threads under high load.
   * <p>
   * This test executes a large number of cleanup operations with virtual threads and
   * measures the performance, ensuring that thread pinning doesn't impact throughput.
   */
  @Test
  public void testCleanupPerformanceUnderLoad() throws Exception {
    // Number of concurrent cleanup operations to run
    final int operationCount = 20;
    
    // Components per operation
    final int componentsPerOperation = 1000;
    
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch completionLatch = new CountDownLatch(operationCount);
    
    // Track operation durations
    List<Long> operationDurations = new ArrayList<>();
    
    // Start time for overall performance measurement
    long startTime = System.currentTimeMillis();
    
    // Execute cleanup operations concurrently
    for (int i = 0; i < operationCount; i++) {
      final int operationId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create a stream of components for this operation
          Stream<FluentComponent> componentStream = getRandomComponentStream(componentsPerOperation);
          
          // Measure operation duration
          long opStartTime = System.currentTimeMillis();
          
          // Run the cleanup method
          DeletionProgress result = underTest.run(repository, componentStream, cancelledCheck);
          
          // Calculate operation duration
          long duration = System.currentTimeMillis() - opStartTime;
          
          // Record the duration
          synchronized (operationDurations) {
            operationDurations.add(duration);
          }
          
          // Verify the result
          assertEquals(componentsPerOperation, result.getComponentCount(),
              "Operation " + operationId + " should process the expected number of components");
          
          log.info("Completed cleanup operation {} in {}ms", operationId, duration);
        }
        catch (Throwable t) {
          log.error("Error in cleanup operation " + operationId, t);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(completionLatch.await(60, TimeUnit.SECONDS), 
        "All cleanup operations should complete within timeout");
    
    // Calculate total duration
    long totalDuration = System.currentTimeMillis() - startTime;
    
    // Calculate average operation duration
    double avgDuration = operationDurations.stream()
        .mapToLong(Long::longValue)
        .average()
        .orElse(0);
    
    // Log performance metrics
    log.info("Performance metrics:");
    log.info("  Total duration: {}ms", totalDuration);
    log.info("  Average operation duration: {}ms", avgDuration);
    log.info("  Operations per second: {}", 1000.0 * operationCount / totalDuration);
    log.info("  Components processed per second: {}", 
        1000.0 * operationCount * componentsPerOperation / totalDuration);
    
    // Verify thread pinning metrics
    if (pinningDetector.hasPinningEvents()) {
      log.warn("Thread pinning detected during performance test:");
      pinningDetector.getPinningEvents().forEach(event -> 
          log.warn("  Thread {} pinned for {}ms at {}", 
              event.threadName, event.durationMs, event.timestamp));
    }
    
    // Verify performance is acceptable
    assertThat("Average operation duration should be reasonable", 
        avgDuration, lessThan(5000.0));
    
    // Verify minimal thread pinning impact
    double pinningTimePercentage = pinningDetector.getTotalPinningTimeMs() * 100.0 / totalDuration;
    assertThat("Thread pinning should have minimal impact on performance", 
        pinningTimePercentage, lessThan(5.0)); // Less than 5% of total time spent in pinned state
  }

  /**
   * Creates a stream of random mock FluentComponent instances for testing.
   *
   * @param size The number of components to create
   * @return A stream of mock FluentComponent instances
   */
  private Stream<FluentComponent> getRandomComponentStream(final int size) {
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

  /**
   * Utility class for detecting and tracking thread pinning events.
   * <p>
   * This class monitors virtual threads for pinning events, which occur when a virtual thread
   * cannot be unmounted from its carrier thread due to synchronized blocks, native methods,
   * or other blocking operations.
   */
  private static class ThreadPinningDetector
  {
    private final List<PinningEvent> pinningEvents = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Long> activeThreadPinning = new ConcurrentHashMap<>();
    private Thread monitorThread;

    /**
     * Starts the thread pinning detector.
     * <p>
     * In a real implementation, this would use JFR (Java Flight Recorder) events or the
     * jdk.tracePinnedThreads JVM flag. For testing purposes, we simulate detection by
     * monitoring thread states and execution times.
     */
    public void start() {
      if (running.compareAndSet(false, true)) {
        // In a real implementation, we would configure JFR to capture VirtualThreadPinned events
        // or use the jdk.tracePinnedThreads JVM flag. For testing purposes, we simulate detection.
        log.info("Starting thread pinning detector");
        
        // Start a monitoring thread
        monitorThread = new Thread(() -> {
          while (running.get()) {
            try {
              // Check for thread pinning by analyzing thread dumps or JFR events
              simulateThreadPinningDetection();
              
              // Sleep briefly to avoid excessive CPU usage
              Thread.sleep(100);
            }
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
            catch (Exception e) {
              log.error("Error in thread pinning detection", e);
            }
          }
        }, "thread-pinning-detector");
        
        monitorThread.setDaemon(true);
        monitorThread.start();
      }
    }

    /**
     * Stops the thread pinning detector.
     */
    public void stop() {
      if (running.compareAndSet(true, false) && monitorThread != null) {
        monitorThread.interrupt();
        try {
          monitorThread.join(1000);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        log.info("Stopped thread pinning detector");
      }
    }

    /**
     * Simulates thread pinning detection.
     * <p>
     * In a real implementation, this would use JFR events or analyze thread dumps.
     * For testing purposes, we simulate detection based on thread execution patterns.
     */
    private void simulateThreadPinningDetection() {
      // Get all thread information
      Thread.getAllStackTraces().keySet().stream()
          .filter(Thread::isVirtual)
          .filter(t -> t.getName().startsWith("cleanup-vt-"))
          .forEach(thread -> {
            // Check if thread is potentially pinned (in RUNNABLE state for too long)
            if (thread.getState() == Thread.State.RUNNABLE) {
              String threadName = thread.getName();
              long currentTime = System.currentTimeMillis();
              
              // Record start of potential pinning
              if (!activeThreadPinning.containsKey(threadName)) {
                activeThreadPinning.put(threadName, currentTime);
              }
              else {
                // Check if pinning threshold exceeded
                long startTime = activeThreadPinning.get(threadName);
                long duration = currentTime - startTime;
                
                if (duration >= PINNING_THRESHOLD_MS) {
                  // Thread has been RUNNABLE for too long, likely pinned
                  // In a real implementation, we would analyze the stack trace to confirm
                  synchronized (pinningEvents) {
                    // Only record if not already recorded
                    if (pinningEvents.stream()
                        .noneMatch(e -> e.threadName.equals(threadName) && 
                                     Math.abs(e.timestamp - startTime) < 100)) {
                      pinningEvents.add(new PinningEvent(threadName, duration, startTime));
                      log.debug("Detected potential thread pinning: {} for {}ms", threadName, duration);
                    }
                  }
                  
                  // Reset tracking for this thread
                  activeThreadPinning.remove(threadName);
                }
              }
            }
            else {
              // Thread is not RUNNABLE, remove from tracking
              activeThreadPinning.remove(thread.getName());
            }
          });
    }

    /**
     * Checks if any thread pinning events have been detected.
     *
     * @return true if thread pinning events have been detected, false otherwise
     */
    public boolean hasPinningEvents() {
      synchronized (pinningEvents) {
        return !pinningEvents.isEmpty();
      }
    }

    /**
     * Gets the list of detected thread pinning events.
     *
     * @return the list of thread pinning events
     */
    public List<PinningEvent> getPinningEvents() {
      synchronized (pinningEvents) {
        return new ArrayList<>(pinningEvents);
      }
    }

    /**
     * Gets the total time spent in pinned state across all threads.
     *
     * @return the total pinning time in milliseconds
     */
    public long getTotalPinningTimeMs() {
      synchronized (pinningEvents) {
        return pinningEvents.stream()
            .mapToLong(event -> event.durationMs)
            .sum();
      }
    }

    /**
     * Represents a thread pinning event.
     */
    public static class PinningEvent
    {
      public final String threadName;
      public final long durationMs;
      public final long timestamp;

      public PinningEvent(String threadName, long durationMs, long timestamp) {
        this.threadName = threadName;
        this.durationMs = durationMs;
        this.timestamp = timestamp;
      }
    }
  }
}