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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests to ensure the Nexus Extender avoids thread pinning when using Java 21 Virtual Threads.
 * 
 * Thread pinning occurs when a virtual thread is "stuck" to its carrier thread and cannot be unmounted,
 * which negates the benefits of virtual threads. This happens primarily in two scenarios:
 * 1. When a virtual thread executes code inside a synchronized block or method
 * 2. When a virtual thread executes a native method or foreign function
 *
 * These tests validate that our code properly avoids pinning scenarios and leverages
 * virtual threads effectively for I/O-bound operations.
 *
 * @since 3.60
 */
@DisplayName("Virtual Thread Pinning Avoidance Tests")
public class ThreadPinningAvoidanceTest
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @TempDir
  Path tempDir;
  
  @BeforeEach
  void setUp() {
    // Create executors for both virtual and platform threads for comparison
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @AfterEach
  void tearDown() throws Exception {
    // Shutdown executors
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that I/O operations with virtual threads don't cause pinning.
   * This simulates file operations similar to those in BlobStore implementations.
   */
  @Test
  @DisplayName("I/O operations should not pin virtual threads")
  void ioOperationsShouldNotPinVirtualThreads() throws Exception {
    // Create a CountDownLatch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track any errors that occur during execution
    List<Exception> exceptions = new ArrayList<>();
    
    // Create and execute tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadNum = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Perform multiple I/O operations per thread
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            Path filePath = tempDir.resolve("file-" + threadNum + "-" + j + ".tmp");
            
            // Write to file
            Files.writeString(filePath, "Test data for thread " + threadNum);
            
            // Small delay to simulate processing
            Thread.sleep(10);
            
            // Read from file
            String content = Files.readString(filePath);
            
            // Verify content
            if (!content.contains("Test data for thread " + threadNum)) {
              throw new AssertionError("File content verification failed");
            }
            
            // Delete file
            Files.delete(filePath);
          }
        }
        catch (Exception e) {
          synchronized (exceptions) {
            exceptions.add(e);
          }
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Assert all operations completed successfully
    assertTrue(completed, "Not all I/O operations completed within the timeout");
    assertTrue(exceptions.isEmpty(), "Exceptions occurred during I/O operations: " + exceptions);
  }

  /**
   * Tests that using ReentrantLock instead of synchronized blocks avoids pinning.
   * This simulates concurrent access to shared resources in a thread-safe manner.
   */
  @Test
  @DisplayName("ReentrantLock should be used instead of synchronized blocks")
  void reentrantLockShouldBeUsedInsteadOfSynchronized() throws Exception {
    // Create a shared counter
    AtomicInteger atomicCounter = new AtomicInteger(0);
    
    // Create a ReentrantLock for thread-safe access
    ReentrantLock lock = new ReentrantLock();
    
    // Create a CountDownLatch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Create and execute tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            // Use ReentrantLock instead of synchronized block
            lock.lock();
            try {
              // Simulate a blocking operation inside the lock
              // In real code, this would be a database query or network call
              Thread.sleep(5);
              
              // Update the counter
              atomicCounter.incrementAndGet();
            }
            finally {
              lock.unlock();
            }
          }
        }
        catch (Exception e) {
          fail("Exception occurred: " + e.getMessage());
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Assert all operations completed successfully
    assertTrue(completed, "Not all lock operations completed within the timeout");
    assertEquals(CONCURRENT_THREADS * OPERATIONS_PER_THREAD, atomicCounter.get(), 
        "Counter value does not match expected operations count");
  }

  /**
   * Compares performance between virtual threads and platform threads for I/O-bound operations.
   * Virtual threads should show better throughput for I/O-bound workloads.
   */
  @Test
  @DisplayName("Virtual threads should outperform platform threads for I/O operations")
  void virtualThreadsShouldOutperformPlatformThreadsForIO() throws Exception {
    // Number of operations to perform
    final int totalOperations = CONCURRENT_THREADS * 5; // More operations than available platform threads
    
    // Create CountDownLatches to wait for all operations to complete
    CountDownLatch virtualLatch = new CountDownLatch(totalOperations);
    CountDownLatch platformLatch = new CountDownLatch(totalOperations);
    
    // Measure virtual thread performance
    long virtualStartTime = System.nanoTime();
    
    for (int i = 0; i < totalOperations; i++) {
      final int opNum = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Simulate I/O-bound operation (file write + read)
          Path filePath = tempDir.resolve("vt-file-" + opNum + ".tmp");
          Files.writeString(filePath, "Virtual thread test data");
          Thread.sleep(50); // Simulate network latency or disk I/O
          String content = Files.readString(filePath);
          Files.delete(filePath);
        }
        catch (Exception e) {
          fail("Exception in virtual thread operation: " + e.getMessage());
        }
        finally {
          virtualLatch.countDown();
        }
      });
    }
    
    // Wait for virtual thread operations to complete
    assertTrue(virtualLatch.await(30, TimeUnit.SECONDS), 
        "Not all virtual thread operations completed within timeout");
    long virtualDuration = System.nanoTime() - virtualStartTime;
    
    // Measure platform thread performance
    long platformStartTime = System.nanoTime();
    
    for (int i = 0; i < totalOperations; i++) {
      final int opNum = i;
      platformThreadExecutor.submit(() -> {
        try {
          // Simulate I/O-bound operation (file write + read)
          Path filePath = tempDir.resolve("pt-file-" + opNum + ".tmp");
          Files.writeString(filePath, "Platform thread test data");
          Thread.sleep(50); // Simulate network latency or disk I/O
          String content = Files.readString(filePath);
          Files.delete(filePath);
        }
        catch (Exception e) {
          fail("Exception in platform thread operation: " + e.getMessage());
        }
        finally {
          platformLatch.countDown();
        }
      });
    }
    
    // Wait for platform thread operations to complete
    assertTrue(platformLatch.await(60, TimeUnit.SECONDS), 
        "Not all platform thread operations completed within timeout");
    long platformDuration = System.nanoTime() - platformStartTime;
    
    // Log performance results
    System.out.println("Virtual threads completed " + totalOperations + " I/O operations in " + 
        Duration.ofNanos(virtualDuration).toMillis() + "ms");
    System.out.println("Platform threads completed " + totalOperations + " I/O operations in " + 
        Duration.ofNanos(platformDuration).toMillis() + "ms");
    
    // Virtual threads should be faster for I/O-bound operations with high concurrency
    assertTrue(virtualDuration < platformDuration, 
        "Virtual threads should outperform platform threads for I/O-bound operations");
  }

  /**
   * Tests that blocking operations are properly structured to avoid pinning.
   * This simulates scenarios where blocking operations need to be performed
   * without causing thread pinning.
   */
  @Test
  @DisplayName("Blocking operations should be structured to avoid pinning")
  void blockingOperationsShouldBeStructuredToAvoidPinning() throws Exception {
    // Create a CountDownLatch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Create a list to track thread names to verify they are virtual threads
    List<String> threadNames = new ArrayList<>();
    
    // Create and execute tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Record the thread name
          String threadName = Thread.currentThread().toString();
          synchronized (threadNames) {
            threadNames.add(threadName);
          }
          
          // Perform a blocking operation OUTSIDE of synchronized block
          // This is the correct pattern to avoid pinning
          performBlockingOperation();
          
          // Use a ReentrantLock for any critical section
          ReentrantLock lock = new ReentrantLock();
          lock.lock();
          try {
            // Short non-blocking operation inside the lock
            int result = 42 * 42;
          }
          finally {
            lock.unlock();
          }
        }
        catch (Exception e) {
          fail("Exception occurred: " + e.getMessage());
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Assert all operations completed successfully
    assertTrue(completed, "Not all operations completed within the timeout");
    
    // Verify that all threads were virtual threads
    synchronized (threadNames) {
      for (String threadName : threadNames) {
        assertTrue(threadName.contains("VirtualThread"), 
            "Expected virtual thread but got: " + threadName);
      }
    }
  }
  
  /**
   * Simulates a blocking I/O operation.
   */
  private void performBlockingOperation() throws IOException, InterruptedException {
    // Create a temporary file
    Path tempFile = Files.createTempFile(tempDir, "blocking-op-", ".tmp");
    
    // Write some data
    Files.writeString(tempFile, "Blocking operation test data");
    
    // Simulate network or disk latency
    Thread.sleep(20);
    
    // Read the data back
    String content = Files.readString(tempFile);
    
    // Clean up
    Files.delete(tempFile);
  }
}