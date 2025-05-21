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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.thread.io.StreamCopier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for detecting and mitigating thread pinning issues when using Java 21 Virtual Threads.
 * 
 * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
 * which prevents the carrier thread from being reused for other tasks. This can significantly
 * reduce the scalability benefits of virtual threads.
 * 
 * Common causes of thread pinning include:
 * - Synchronized blocks or methods
 * - Native methods
 * - Monitor waits
 * 
 * This test class verifies that pinning can be detected and validates strategies to avoid
 * pinning in critical code paths.
 */
@EnabledOnJre(JRE.JAVA_21)
public class VirtualThreadPinningDetectionTest extends TestSupport
{
  private static final int CONCURRENT_TASKS = 100;
  private static final int TASK_DURATION_MS = 50;
  private static final int PINNING_DETECTION_THRESHOLD_MS = 20; // Default JFR threshold
  
  private ExecutorService virtualThreadExecutor;
  private RecordingStream jfrRecordingStream;
  private AtomicInteger pinnedThreadCount;
  
  @BeforeEach
  public void setUp() {
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Initialize counter for pinned thread events
    pinnedThreadCount = new AtomicInteger(0);
    
    // Set up JFR recording to detect pinned threads
    jfrRecordingStream = new RecordingStream();
    jfrRecordingStream.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(PINNING_DETECTION_THRESHOLD_MS));
    jfrRecordingStream.onEvent("jdk.VirtualThreadPinned", event -> {
      pinnedThreadCount.incrementAndGet();
      log.info("Virtual thread pinning detected: {}", event);
    });
    
    // Start recording in a separate thread
    Thread recordingThread = Thread.ofPlatform().name("jfr-recording").start(() -> {
      try {
        jfrRecordingStream.start();
      } catch (Exception e) {
        log.error("Error in JFR recording", e);
      }
    });
  }
  
  @AfterEach
  public void tearDown() {
    // Close the JFR recording stream
    if (jfrRecordingStream != null) {
      jfrRecordingStream.close();
    }
    
    // Shutdown the executor service
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        virtualThreadExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Test that synchronized blocks cause virtual thread pinning.
   * 
   * This test verifies that when a virtual thread executes a synchronized block
   * that contains a blocking operation, the thread becomes pinned to its carrier thread.
   */
  @Test
  public void testSynchronizedBlockCausesPinning() throws Exception {
    // Reset pinned thread counter
    pinnedThreadCount.set(0);
    
    // Create a shared object for synchronization
    Object lock = new Object();
    
    // Run a task that uses synchronized block with a blocking operation
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      synchronized (lock) {
        try {
          // Simulate a blocking operation that would normally allow unmounting
          Thread.sleep(TASK_DURATION_MS * 2);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }, virtualThreadExecutor);
    
    // Wait for the task to complete
    future.join();
    
    // Give JFR time to process the event
    Thread.sleep(100);
    
    // Verify that pinning was detected
    assertThat("Virtual thread pinning should be detected", pinnedThreadCount.get(), greaterThanOrEqualTo(1));
  }
  
  /**
   * Test that synchronized methods cause virtual thread pinning.
   * 
   * This test verifies that when a virtual thread executes a synchronized method
   * that contains a blocking operation, the thread becomes pinned to its carrier thread.
   */
  @Test
  public void testSynchronizedMethodCausesPinning() throws Exception {
    // Reset pinned thread counter
    pinnedThreadCount.set(0);
    
    // Create an instance with a synchronized method
    SynchronizedBlockingOperations operations = new SynchronizedBlockingOperations();
    
    // Run a task that calls the synchronized method
    CompletableFuture<Void> future = CompletableFuture.runAsync(
        () -> operations.synchronizedBlockingOperation(TASK_DURATION_MS * 2),
        virtualThreadExecutor);
    
    // Wait for the task to complete
    future.join();
    
    // Give JFR time to process the event
    Thread.sleep(100);
    
    // Verify that pinning was detected
    assertThat("Virtual thread pinning should be detected", pinnedThreadCount.get(), greaterThanOrEqualTo(1));
  }
  
  /**
   * Test that ReentrantLock avoids virtual thread pinning.
   * 
   * This test verifies that when a virtual thread uses ReentrantLock instead of
   * synchronized blocks, it can be unmounted during blocking operations, avoiding pinning.
   */
  @Test
  public void testReentrantLockAvoidsPinning() throws Exception {
    // Reset pinned thread counter
    pinnedThreadCount.set(0);
    
    // Create a ReentrantLock
    ReentrantLock lock = new ReentrantLock();
    
    // Run a task that uses ReentrantLock with a blocking operation
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      lock.lock();
      try {
        // Simulate a blocking operation
        Thread.sleep(TASK_DURATION_MS * 2);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lock.unlock();
      }
    }, virtualThreadExecutor);
    
    // Wait for the task to complete
    future.join();
    
    // Give JFR time to process the event
    Thread.sleep(100);
    
    // Verify that no pinning was detected
    assertEquals(0, pinnedThreadCount.get(), "Virtual thread should not be pinned when using ReentrantLock");
  }
  
  /**
   * Test performance comparison between synchronized blocks and ReentrantLock.
   * 
   * This test compares the performance of executing concurrent tasks using
   * synchronized blocks (which cause pinning) versus ReentrantLock (which avoids pinning).
   */
  @Test
  public void testPerformanceComparisonSynchronizedVsReentrantLock() throws Exception {
    // Number of concurrent tasks to run
    int taskCount = CONCURRENT_TASKS;
    
    // Measure execution time with synchronized blocks (causes pinning)
    long synchronizedTime = measureExecutionTime(() -> {
      Object lock = new Object();
      CountDownLatch latch = new CountDownLatch(taskCount);
      
      for (int i = 0; i < taskCount; i++) {
        virtualThreadExecutor.submit(() -> {
          synchronized (lock) {
            try {
              Thread.sleep(TASK_DURATION_MS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          latch.countDown();
        });
      }
      
      try {
        latch.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    // Measure execution time with ReentrantLock (avoids pinning)
    long reentrantLockTime = measureExecutionTime(() -> {
      ReentrantLock lock = new ReentrantLock();
      CountDownLatch latch = new CountDownLatch(taskCount);
      
      for (int i = 0; i < taskCount; i++) {
        virtualThreadExecutor.submit(() -> {
          lock.lock();
          try {
            Thread.sleep(TASK_DURATION_MS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            lock.unlock();
          }
          latch.countDown();
        });
      }
      
      try {
        latch.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    log.info("Execution time with synchronized blocks: {} ms", synchronizedTime);
    log.info("Execution time with ReentrantLock: {} ms", reentrantLockTime);
    
    // Verify that ReentrantLock performs better with virtual threads
    // due to avoiding pinning
    assertThat("ReentrantLock should be faster than synchronized with virtual threads",
        reentrantLockTime, lessThan(synchronizedTime));
  }
  
  /**
   * Test that StreamCopier properly handles virtual threads without pinning.
   * 
   * This test verifies that the StreamCopier utility class can be used with
   * virtual threads without causing pinning issues.
   */
  @Test
  public void testStreamCopierWithVirtualThreads() throws Exception {
    // Reset pinned thread counter
    pinnedThreadCount.set(0);
    
    // Create test data
    byte[] testData = new byte[1024 * 1024]; // 1MB of data
    for (int i = 0; i < testData.length; i++) {
      testData[i] = (byte) (i % 256);
    }
    
    // Create input stream from test data
    ByteArrayInputStream inputStream = new ByteArrayInputStream(testData);
    
    // Create a virtual thread executor for StreamCopier
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Define the write operation (copy from input stream to output stream)
      Consumer<OutputStream> writeOperation = outputStream -> {
        try {
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
          }
        } catch (IOException e) {
          throw new RuntimeException("Error writing to output stream", e);
        }
      };
      
      // Define the read operation (collect data from input stream)
      Function<InputStream, byte[]> readOperation = stream -> {
        try {
          ByteArrayOutputStream result = new ByteArrayOutputStream();
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = stream.read(buffer)) != -1) {
            result.write(buffer, 0, bytesRead);
          }
          return result.toByteArray();
        } catch (IOException e) {
          throw new RuntimeException("Error reading from input stream", e);
        }
      };
      
      // Create StreamCopier with virtual thread executor
      StreamCopier<byte[]> streamCopier = new StreamCopier<>(writeOperation, readOperation, virtualExecutor);
      
      // Execute the copy operation
      byte[] result = streamCopier.read();
      
      // Verify the result
      assertEquals(testData.length, result.length, "Copied data length should match original");
      for (int i = 0; i < testData.length; i++) {
        assertEquals(testData[i], result[i], "Copied data should match original at index " + i);
      }
      
      // Give JFR time to process any events
      Thread.sleep(100);
      
      // Verify that no pinning was detected
      assertEquals(0, pinnedThreadCount.get(), "StreamCopier should not cause virtual thread pinning");
    } finally {
      virtualExecutor.shutdown();
    }
  }
  
  /**
   * Test concurrent execution with high thread count to verify scalability.
   * 
   * This test creates a large number of virtual threads to verify that they can
   * execute concurrently without issues when proper pinning avoidance techniques are used.
   */
  @Test
  public void testConcurrentExecutionWithHighThreadCount() throws Exception {
    // Number of concurrent tasks to run (much higher than available CPU cores)
    int taskCount = 1000;
    
    // Create a countdown latch to wait for all tasks to complete
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    // Track if any errors occur during execution
    AtomicBoolean errorOccurred = new AtomicBoolean(false);
    List<Exception> exceptions = new ArrayList<>();
    
    // Get the number of available processors for comparison
    int availableProcessors = Runtime.getRuntime().availableProcessors();
    log.info("Available processors: {}", availableProcessors);
    
    // Submit tasks that use ReentrantLock to avoid pinning
    ReentrantLock lock = new ReentrantLock();
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Occasionally acquire the lock (not all tasks need it)
          if (taskId % 10 == 0) {
            lock.lock();
            try {
              // Perform a short operation under the lock
              Thread.sleep(5);
            } finally {
              lock.unlock();
            }
          } else {
            // Just do some work without locking
            Thread.sleep(TASK_DURATION_MS);
          }
        } catch (Exception e) {
          errorOccurred.set(true);
          synchronized (exceptions) {
            exceptions.add(e);
          }
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Log any exceptions that occurred
    if (!exceptions.isEmpty()) {
      log.error("Exceptions occurred during concurrent execution:");
      for (Exception e : exceptions) {
        log.error("Exception: ", e);
      }
    }
    
    // Verify that all tasks completed successfully
    assertTrue(completed, "All tasks should complete within the timeout");
    assertFalse(errorOccurred.get(), "No errors should occur during concurrent execution");
    
    // Verify that the number of tasks far exceeds the number of available processors,
    // demonstrating the scalability advantage of virtual threads
    assertThat("Task count should far exceed available processors",
        taskCount, greaterThan(availableProcessors * 10));
  }
  
  /**
   * Helper method to measure execution time of a runnable task.
   * 
   * @param task The task to measure
   * @return Execution time in milliseconds
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Helper class with synchronized methods that cause pinning.
   */
  private static class SynchronizedBlockingOperations {
    /**
     * A synchronized method that performs a blocking operation,
     * which will cause virtual thread pinning.
     */
    public synchronized void synchronizedBlockingOperation(long sleepTimeMs) {
      try {
        Thread.sleep(sleepTimeMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}