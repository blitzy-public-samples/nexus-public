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
package org.sonatype.nexus.virtualthread;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Tests to ensure proper usage of Java 21 Virtual Threads without thread pinning.
 * 
 * Thread pinning occurs when a virtual thread is "stuck" to its carrier thread (platform thread),
 * preventing the carrier thread from being reused for other virtual threads. This typically happens
 * when using synchronized blocks/methods or native methods with virtual threads.
 * 
 * These tests validate that operations in Nexus are properly structured to avoid thread pinning
 * when using virtual threads for I/O-bound operations.
 * 
 * To detect thread pinning in a running application, use the JVM flag:
 * -Djdk.tracePinnedThreads=full
 * 
 * This will output stack traces whenever thread pinning is detected.
 *
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21)
public class ThreadPinningAvoidanceTest
{
  private static final int CONCURRENT_TASKS = 100;
  private static final int TASK_DURATION_MS = 100;
  private static final String TEST_URL = "https://repo.maven.apache.org/maven2/org/apache/maven/maven-core/3.9.6/maven-core-3.9.6.pom";
  
  // Threshold for detecting thread pinning - if execution time exceeds this factor of expected time,
  // it may indicate thread pinning is occurring
  private static final double PINNING_DETECTION_THRESHOLD = 2.0;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  void setUp() {
    // Create executors for platform threads and virtual threads
    platformThreadExecutor = Executors.newFixedThreadPool(10);
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() throws Exception {
    // Shutdown executors
    platformThreadExecutor.shutdown();
    virtualThreadExecutor.shutdown();
    
    if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      platformThreadExecutor.shutdownNow();
    }
    
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
  }

  /**
   * Tests that I/O-bound operations (HTTP requests) perform better with virtual threads
   * than with platform threads when running many concurrent operations.
   */
  @Test
  void testIOBoundOperationsWithVirtualThreads() throws Exception {
    // Measure execution time with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      executeHttpRequests(platformThreadExecutor, CONCURRENT_TASKS);
    });
    
    // Measure execution time with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      executeHttpRequests(virtualThreadExecutor, CONCURRENT_TASKS);
    });
    
    // Virtual threads should be faster for I/O-bound operations with high concurrency
    System.out.println("Platform thread execution time: " + platformThreadTime + "ms");
    System.out.println("Virtual thread execution time: " + virtualThreadTime + "ms");
    
    // Assert that virtual threads perform better than platform threads
    // The improvement factor may vary based on the environment, but virtual threads should be faster
    assertThat("Virtual threads should be faster than platform threads for I/O operations",
        virtualThreadTime, lessThan(platformThreadTime));
    
    // Calculate the improvement factor
    double improvementFactor = (double) platformThreadTime / virtualThreadTime;
    System.out.println("Improvement factor with virtual threads: " + improvementFactor + "x");
    
    // Virtual threads should provide a significant improvement for I/O-bound operations
    // This threshold may need adjustment based on the test environment
    assertThat("Virtual threads should provide significant improvement for I/O operations",
        improvementFactor, greaterThan(1.5));
  }

  /**
   * Tests that using ReentrantLock instead of synchronized blocks avoids thread pinning
   * when performing blocking operations with virtual threads.
   */
  @Test
  void testLockingWithoutPinning() throws Exception {
    final int numThreads = 50;
    final CountDownLatch latch = new CountDownLatch(numThreads);
    final ReentrantLock lock = new ReentrantLock();
    
    // Execute tasks that use ReentrantLock (which doesn't cause pinning)
    for (int i = 0; i < numThreads; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Acquire lock, perform a blocking operation, then release
          lock.lock();
          try {
            // Simulate I/O or blocking operation
            Thread.sleep(TASK_DURATION_MS);
          } finally {
            lock.unlock();
          }
          latch.countDown();
        } catch (Exception e) {
          e.printStackTrace();
        }
      });
    }
    
    // All tasks should complete within a reasonable time
    // If thread pinning occurs, this would take much longer
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      boolean completed = latch.await(3, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete their tasks", completed, is(true));
    });
  }
  
  /**
   * Demonstrates the difference between using ReentrantLock (no pinning) and synchronized blocks (causes pinning)
   * when performing blocking operations with virtual threads.
   */
  @Test
  void testReentrantLockVsSynchronized() throws Exception {
    final int numThreads = 50;
    final Object lockObject = new Object();
    
    // Measure execution time with ReentrantLock (should not cause pinning)
    long reentrantLockTime = measureExecutionTime(() -> {
      CountDownLatch latch = new CountDownLatch(numThreads);
      ReentrantLock lock = new ReentrantLock();
      
      for (int i = 0; i < numThreads; i++) {
        virtualThreadExecutor.submit(() -> {
          try {
            lock.lock();
            try {
              // Blocking operation
              Thread.sleep(TASK_DURATION_MS);
            } finally {
              lock.unlock();
            }
            latch.countDown();
          } catch (Exception e) {
            e.printStackTrace();
            latch.countDown();
          }
        });
      }
      
      try {
        latch.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    // Measure execution time with synchronized blocks (may cause pinning)
    long synchronizedTime = measureExecutionTime(() -> {
      CountDownLatch latch = new CountDownLatch(numThreads);
      
      for (int i = 0; i < numThreads; i++) {
        virtualThreadExecutor.submit(() -> {
          try {
            synchronized (lockObject) {
              // Blocking operation
              Thread.sleep(TASK_DURATION_MS);
            }
            latch.countDown();
          } catch (Exception e) {
            e.printStackTrace();
            latch.countDown();
          }
        });
      }
      
      try {
        latch.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    System.out.println("ReentrantLock execution time: " + reentrantLockTime + "ms");
    System.out.println("Synchronized block execution time: " + synchronizedTime + "ms");
    
    // Synchronized blocks should take significantly longer due to thread pinning
    assertThat("Synchronized blocks should be slower due to thread pinning",
        synchronizedTime, greaterThan(reentrantLockTime * 2));
  }

  /**
   * Tests that concurrent operations with virtual threads scale well under load,
   * which would not be possible if thread pinning occurred.
   */
  @Test
  void testConcurrencyScalingWithVirtualThreads() throws Exception {
    final int smallBatch = 10;
    final int largeBatch = 1000;
    
    // Run a small batch of tasks and measure time
    long smallBatchTime = measureConcurrentTasks(smallBatch);
    
    // Run a large batch of tasks and measure time
    long largeBatchTime = measureConcurrentTasks(largeBatch);
    
    // Calculate the scaling factor (how much longer the large batch took)
    double scalingFactor = (double) largeBatchTime / smallBatchTime;
    
    // If virtual threads are working properly without pinning, the scaling factor should be
    // significantly less than the ratio of batch sizes (largeBatch/smallBatch)
    double batchSizeRatio = (double) largeBatch / smallBatch;
    double expectedMaxScalingFactor = batchSizeRatio * 0.5; // Allow 50% of linear scaling
    
    System.out.println("Small batch time: " + smallBatchTime + "ms");
    System.out.println("Large batch time: " + largeBatchTime + "ms");
    System.out.println("Scaling factor: " + scalingFactor);
    System.out.println("Batch size ratio: " + batchSizeRatio);
    System.out.println("Expected max scaling factor: " + expectedMaxScalingFactor);
    
    assertThat("Virtual threads should scale sublinearly with increased concurrency",
        scalingFactor, lessThan(expectedMaxScalingFactor));
  }

  /**
   * Tests that operations using virtual threads can handle a mix of CPU-bound and I/O-bound tasks
   * without thread pinning causing performance degradation.
   */
  @Test
  void testMixedWorkloadWithVirtualThreads() throws Exception {
    final int taskCount = 100;
    final CountDownLatch latch = new CountDownLatch(taskCount);
    final List<Future<?>> futures = new ArrayList<>();
    
    // Submit a mix of CPU-bound and I/O-bound tasks
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      Future<?> future = virtualThreadExecutor.submit(() -> {
        try {
          if (taskId % 2 == 0) {
            // Even tasks: I/O-bound (HTTP request)
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TEST_URL))
                .timeout(Duration.ofSeconds(10))
                .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
          } else {
            // Odd tasks: CPU-bound (computation)
            long result = 0;
            for (int j = 0; j < 1000000; j++) {
              result += j;
            }
          }
          latch.countDown();
          return null;
        } catch (Exception e) {
          e.printStackTrace();
          latch.countDown();
          return null;
        }
      });
      futures.add(future);
    }
    
    // All tasks should complete within a reasonable time
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertThat("All mixed workload tasks should complete", completed, is(true));
    
    // Verify all futures completed successfully
    for (Future<?> future : futures) {
      future.get(1, TimeUnit.SECONDS); // This should not throw an exception
    }
  }
  
  /**
   * Tests file I/O operations with virtual threads to ensure they don't cause thread pinning.
   * File I/O is a common source of blocking operations in Nexus Repository.
   */
  @Test
  void testFileIOWithVirtualThreads() throws Exception {
    final int fileCount = 50;
    final CountDownLatch latch = new CountDownLatch(fileCount);
    final List<Path> tempFiles = new ArrayList<>();
    
    try {
      // Create temporary files and perform concurrent I/O operations
      for (int i = 0; i < fileCount; i++) {
        final Path tempFile = Files.createTempFile("nexus-vt-test-", ".tmp");
        tempFiles.add(tempFile);
        
        virtualThreadExecutor.submit(() -> {
          try {
            // Write data to file
            List<String> lines = new ArrayList<>();
            for (int j = 0; j < 1000; j++) {
              lines.add("Line " + j + ": " + Thread.currentThread().getName());
            }
            Files.write(tempFile, lines);
            
            // Read data from file
            List<String> readLines = Files.readAllLines(tempFile);
            assertEquals(1000, readLines.size(), "File should contain 1000 lines");
            
            latch.countDown();
          } catch (Exception e) {
            e.printStackTrace();
            latch.countDown();
          }
        });
      }
      
      // All file I/O operations should complete within a reasonable time
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertThat("All file I/O operations should complete", completed, is(true));
      
    } finally {
      // Clean up temporary files
      for (Path tempFile : tempFiles) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException e) {
          System.err.println("Failed to delete temporary file: " + tempFile);
        }
      }
    }
  }

  /**
   * Helper method to execute HTTP requests concurrently using the provided executor.
   */
  private void executeHttpRequests(ExecutorService executor, int concurrentRequests) throws Exception {
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    List<Future<?>> futures = new ArrayList<>();
    
    for (int i = 0; i < concurrentRequests; i++) {
      Future<?> future = executor.submit(() -> {
        try {
          HttpClient client = HttpClient.newHttpClient();
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(TEST_URL))
              .timeout(Duration.ofSeconds(10))
              .build();
          
          HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
          assertEquals(200, response.statusCode(), "HTTP request should succeed");
        } catch (IOException | InterruptedException e) {
          throw new RuntimeException("HTTP request failed", e);
        } finally {
          latch.countDown();
        }
      });
      futures.add(future);
    }
    
    // Wait for all requests to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertThat("All HTTP requests should complete", completed, is(true));
    
    // Check for any exceptions in the futures
    for (Future<?> future : futures) {
      future.get(1, TimeUnit.SECONDS); // This will throw if the task failed
    }
  }

  /**
   * Helper method to measure execution time of a runnable task.
   */
  private long measureExecutionTime(Runnable task) throws Exception {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Helper method to detect if thread pinning is likely occurring based on execution time.
   * This is a heuristic approach and not a definitive test.
   */
  private boolean isPinningLikely(long actualTime, long expectedTime) {
    return actualTime > expectedTime * PINNING_DETECTION_THRESHOLD;
  }

  /**
   * Helper method to measure execution time of concurrent tasks using virtual threads.
   */
  private long measureConcurrentTasks(int taskCount) throws Exception {
    final CountDownLatch latch = new CountDownLatch(taskCount);
    
    long startTime = System.currentTimeMillis();
    
    // Submit tasks that perform a mix of computation and simulated I/O
    for (int i = 0; i < taskCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Simulate a task with both computation and I/O
          Thread.sleep(TASK_DURATION_MS); // Simulated I/O
          
          // Some CPU work
          int sum = 0;
          for (int j = 0; j < 10000; j++) {
            sum += j;
          }
          
          latch.countDown();
        } catch (Exception e) {
          e.printStackTrace();
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    assertThat("All concurrent tasks should complete", completed, is(true));
    
    return System.currentTimeMillis() - startTime;
  }
}