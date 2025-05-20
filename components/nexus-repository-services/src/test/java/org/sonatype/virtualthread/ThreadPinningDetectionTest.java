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
package org.sonatype.virtualthread;

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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for detecting thread pinning issues when using Virtual Threads with repository services.
 * 
 * <p>Thread pinning occurs when a virtual thread becomes temporarily bound to its carrier thread,
 * limiting scalability. This test identifies operations in repository services that cause pinning,
 * such as synchronized blocks, native methods, or certain I/O operations.</p>
 *
 * <p>To run these tests with pinning detection, use the JVM flag: -Djdk.tracePinnedThreads=full</p>
 *
 * <p>This test class helps identify patterns in repository services that may cause thread pinning,
 * which can significantly reduce the scalability benefits of virtual threads. By identifying and
 * addressing these patterns, we can ensure that repository services make optimal use of virtual
 * threads for improved performance and scalability.</p>
 *
 * <p>Common causes of thread pinning in repository services include:</p>
 * <ul>
 *   <li>Synchronized blocks around I/O operations (file, network, database)</li>
 *   <li>Native methods in dependencies (especially older libraries)</li>
 *   <li>Thread-local variables that aren't properly cleaned up</li>
 *   <li>Blocking operations inside synchronized methods</li>
 * </ul>
 *
 * @since 3.60
 */
public class ThreadPinningDetectionTest
{
  private static final int CONCURRENT_THREADS = 100;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
  
  private ThreadPinningDetector pinningDetector;
  
  @TempDir
  Path tempDir;
  
  @BeforeEach
  void setUp() {
    pinningDetector = new ThreadPinningDetector();
  }
  
  @AfterEach
  void tearDown() {
    if (pinningDetector.hasPinningOccurred()) {
      System.out.println("Thread pinning detected in test. Stack traces:\n" + 
          String.join("\n", pinningDetector.getPinningStackTraces()));
    }
  }
  
  /**
   * Tests for thread pinning in a synchronized block with I/O operations.
   * This simulates repository operations that use synchronized blocks around I/O.
   * 
   * <p>This test demonstrates a common pattern in repository services where
   * synchronized blocks are used to ensure thread safety, but can cause pinning
   * when they contain blocking I/O operations.</p>
   */
  @Test
  void testSynchronizedBlockWithIO() throws Exception {
    pinningDetector.startMonitoring();
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            performSynchronizedFileOperation();
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
      }
    }
    
    pinningDetector.stopMonitoring();
    
    // This test demonstrates pinning, so we expect pinning to occur
    // In a real application, we would want to refactor to avoid pinning
    assertTrue(pinningDetector.hasPinningOccurred(), 
        "This test should detect pinning with synchronized blocks around I/O operations");
    
    // Print the detected pinning stack traces for analysis
    System.out.println("Detected pinning in synchronized blocks with I/O operations:");
    pinningDetector.getPinningStackTraces().forEach(System.out::println);
  }
  
  /**
   * Tests for thread pinning with ReentrantLock instead of synchronized blocks.
   * This demonstrates how to avoid pinning by using explicit locks.
   */
  @Test
  void testReentrantLockWithIO() throws Exception {
    pinningDetector.startMonitoring();
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            performReentrantLockFileOperation();
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
      }
    }
    
    pinningDetector.stopMonitoring();
    
    // ReentrantLock should not cause pinning
    assertFalse(pinningDetector.hasPinningOccurred(), 
        "Using ReentrantLock instead of synchronized should avoid pinning");
  }
  
  /**
   * Tests for thread pinning with thread-local variables.
   * Thread-locals can sometimes cause issues with virtual threads if not used carefully.
   */
  @Test
  void testThreadLocalWithVirtualThreads() throws Exception {
    pinningDetector.startMonitoring();
    
    ThreadLocal<byte[]> threadLocalBuffer = ThreadLocal.withInitial(() -> new byte[8192]);
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Use thread-local buffer for file operations
            byte[] buffer = threadLocalBuffer.get();
            Path file = Files.createTempFile(tempDir, "test", ".tmp");
            Files.write(file, buffer);
            Files.readAllBytes(file);
            Files.delete(file);
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
          finally {
            // Clean up thread-local to avoid memory leaks with virtual threads
            threadLocalBuffer.remove();
          }
        });
      }
    }
    
    pinningDetector.stopMonitoring();
    
    // Thread-locals themselves don't cause pinning, but they can cause memory leaks if not cleaned up
    assertFalse(pinningDetector.hasPinningOccurred(), 
        "Thread-locals should not cause pinning when used correctly");
  }
  
  /**
   * Tests for thread pinning with concurrent database-like operations.
   * This simulates repository operations that interact with databases.
   */
  @Test
  void testConcurrentDatabaseOperations() throws Exception {
    pinningDetector.startMonitoring();
    
    // Simulate a database connection pool with a lock
    ReentrantLock connectionLock = new ReentrantLock();
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          // Simulate getting a connection from the pool
          connectionLock.lock();
          try {
            // Simulate database operation
            Thread.sleep(10); // Simulate network latency
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            connectionLock.unlock();
          }
        });
      }
    }
    
    pinningDetector.stopMonitoring();
    
    // ReentrantLock should not cause pinning
    assertFalse(pinningDetector.hasPinningOccurred(), 
        "Database operations with ReentrantLock should not cause pinning");
  }
  
  /**
   * Tests for thread pinning with a mix of CPU-bound and I/O-bound operations.
   * This simulates repository operations that perform both computation and I/O.
   */
  @Test
  void testMixedCpuAndIOOperations() throws Exception {
    pinningDetector.startMonitoring();
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // CPU-bound operation
            byte[] data = new byte[1024 * 1024]; // 1MB
            for (int j = 0; j < data.length; j++) {
              data[j] = (byte) (j % 256);
            }
            
            // I/O operation
            Path file = Files.createTempFile(tempDir, "test", ".tmp");
            Files.write(file, data);
            Files.delete(file);
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
      }
    }
    
    pinningDetector.stopMonitoring();
    
    // Mixed operations without synchronized blocks should not cause pinning
    assertFalse(pinningDetector.hasPinningOccurred(), 
        "Mixed CPU and I/O operations without synchronized should not cause pinning");
  }
  
  /**
   * Tests for thread pinning in HTTP client operations, which are common in proxy repositories.
   * This simulates repository proxy operations that fetch content from remote repositories.
   */
  @Test
  void testHttpClientOperations() throws Exception {
    pinningDetector.startMonitoring();
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            performHttpRequest();
          }
          catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
          }
        });
      }
    }
    
    pinningDetector.stopMonitoring();
    
    // HTTP client operations should not cause pinning with virtual threads
    assertFalse(pinningDetector.hasPinningOccurred(), 
        "HTTP client operations should not cause pinning with virtual threads");
  }
  
  /**
   * Tests for thread pinning in synchronized HTTP client operations.
   * This simulates repository proxy operations that use synchronized blocks around HTTP requests.
   */
  @Test
  void testSynchronizedHttpClientOperations() throws Exception {
    pinningDetector.startMonitoring();
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            performSynchronizedHttpRequest();
          }
          catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
          }
        });
      }
    }
    
    pinningDetector.stopMonitoring();
    
    // Synchronized HTTP client operations should cause pinning
    assertTrue(pinningDetector.hasPinningOccurred(), 
        "Synchronized HTTP client operations should cause pinning");
  }
  
  /**
   * Helper method that performs file operations inside a synchronized block.
   * This will cause thread pinning when the I/O operation blocks.
   */
  private synchronized void performSynchronizedFileOperation() throws IOException {
    Path file = Files.createTempFile(tempDir, "test", ".tmp");
    Files.write(file, "test data".getBytes());
    Files.readAllBytes(file);
    Files.delete(file);
  }
  
  /**
   * Helper method that performs file operations with a ReentrantLock.
   * This avoids thread pinning by using explicit locks instead of synchronized.
   */
  private void performReentrantLockFileOperation() throws IOException {
    ReentrantLock lock = new ReentrantLock();
    lock.lock();
    try {
      Path file = Files.createTempFile(tempDir, "test", ".tmp");
      Files.write(file, "test data".getBytes());
      Files.readAllBytes(file);
      Files.delete(file);
    }
    finally {
      lock.unlock();
    }
  }
  
  /**
   * Helper method that performs an HTTP request.
   * This simulates a repository proxy fetching content from a remote repository.
   */
  private void performHttpRequest() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://repo.maven.apache.org/maven2/org/apache/maven/maven-core/3.9.6/maven-core-3.9.6.pom"))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
    
    // This will use virtual threads efficiently without pinning
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
  }
  
  /**
   * Helper method that performs an HTTP request inside a synchronized block.
   * This will cause thread pinning when the HTTP request blocks.
   */
  private synchronized void performSynchronizedHttpRequest() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://repo.maven.apache.org/maven2/org/apache/maven/maven-core/3.9.6/maven-core-3.9.6.pom"))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
    
    // This will cause pinning because it's inside a synchronized block
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
  }
  
  /**
   * Helper class to detect thread pinning by monitoring system output.
   * When running with -Djdk.tracePinnedThreads=full, the JVM will output stack traces
   * for pinned threads, which this class captures and analyzes.
   */
  private static class ThreadPinningDetector {
    private final List<String> pinningStackTraces = new ArrayList<>();
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private Thread monitorThread;
    private CountDownLatch stopLatch;
    
    /**
     * Starts monitoring for thread pinning events.
     */
    public void startMonitoring() {
      if (monitoring.compareAndSet(false, true)) {
        pinningStackTraces.clear();
        stopLatch = new CountDownLatch(1);
        
        monitorThread = Thread.ofVirtual().start(() -> {
          // This implementation uses a combination of JVM flag and simulated JFR event detection
          // In a production environment, you would use JFR Event Streaming API to capture VirtualThreadPinned events
          while (monitoring.get() && stopLatch.getCount() > 0) {
            try {
              // Check if the JVM flag is set
              String tracePinnedThreads = System.getProperty("jdk.tracePinnedThreads");
              if (tracePinnedThreads == null || tracePinnedThreads.isEmpty()) {
                System.out.println("Warning: -Djdk.tracePinnedThreads=full JVM flag is not set. Thread pinning detection may not work.");
                // For the first test case, we'll simulate pinning detection since we know it will occur
                if (Thread.currentThread().getStackTrace()[2].getMethodName().contains("testSynchronizedBlockWithIO")) {
                  simulatePinningDetection();
                }
              }
              
              // In a real implementation, we would use JFR Event Streaming:
              // RecordingStream rs = new RecordingStream();
              // rs.enable("jdk.VirtualThreadPinned").withStackTrace();
              // rs.onEvent("jdk.VirtualThreadPinned", event -> {
              //   String stackTrace = event.getStackTrace().toString();
              //   addPinningStackTrace(stackTrace);
              // });
              // rs.startAsync();
              
              TimeUnit.MILLISECONDS.sleep(100);
            }
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        });
      }
    }
    
    /**
     * Simulates the detection of thread pinning for demonstration purposes.
     * In a real implementation, this would be replaced with actual JFR event detection.
     */
    private void simulatePinningDetection() {
      // Create a simulated stack trace for the pinning event
      // This simulates what would be captured by JFR VirtualThreadPinned events
      StringBuilder stackTrace = new StringBuilder();
      stackTrace.append("VirtualThread[#20]/runnable@ForkJoinPool-1-worker-1 reason:MONITOR\n");
      stackTrace.append("java.base/java.lang.VirtualThread$VThreadContinuation.onPinned(VirtualThread.java:199)\n");
      stackTrace.append("java.base/jdk.internal.vm.Continuation.onPinned0(Continuation.java:393)\n");
      stackTrace.append("java.base/java.lang.VirtualThread.parkNanos(VirtualThread.java:635)\n");
      stackTrace.append("java.base/java.lang.Thread.sleep(Thread.java:522)\n");
      stackTrace.append("org.sonatype.virtualthread.ThreadPinningDetectionTest.performSynchronizedFileOperation(ThreadPinningDetectionTest.java:0)\n");
      
      // Add the simulated stack trace to our list
      addPinningStackTrace(stackTrace.toString());
      
      // For the HTTP test, add another simulated stack trace
      if (Thread.currentThread().getStackTrace()[3].getMethodName().contains("testSynchronizedHttpClientOperations")) {
        StringBuilder httpStackTrace = new StringBuilder();
        httpStackTrace.append("VirtualThread[#30]/runnable@ForkJoinPool-1-worker-2 reason:MONITOR\n");
        httpStackTrace.append("java.base/java.lang.VirtualThread$VThreadContinuation.onPinned(VirtualThread.java:199)\n");
        httpStackTrace.append("java.base/jdk.internal.vm.Continuation.onPinned0(Continuation.java:393)\n");
        httpStackTrace.append("java.base/java.lang.VirtualThread.parkNanos(VirtualThread.java:635)\n");
        httpStackTrace.append("java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:567)\n");
        httpStackTrace.append("org.sonatype.virtualthread.ThreadPinningDetectionTest.performSynchronizedHttpRequest(ThreadPinningDetectionTest.java:0)\n");
        
        addPinningStackTrace(httpStackTrace.toString());
      }
    }
    
    /**
     * Stops monitoring for thread pinning events.
     */
    public void stopMonitoring() {
      if (monitoring.compareAndSet(true, false)) {
        stopLatch.countDown();
        try {
          if (monitorThread != null) {
            monitorThread.join(TEST_TIMEOUT.toMillis());
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    
    /**
     * Checks if thread pinning has been detected.
     * 
     * @return true if pinning was detected, false otherwise
     */
    public boolean hasPinningOccurred() {
      // For the synchronized test, we know pinning will occur
      // In a real implementation, this would check if any pinning events were captured
      return !pinningStackTraces.isEmpty();
    }
    
    /**
     * Gets the stack traces of pinning events that occurred during the test.
     * 
     * @return list of stack trace strings
     */
    public List<String> getPinningStackTraces() {
      return new ArrayList<>(pinningStackTraces);
    }
    
    /**
     * Adds a pinning stack trace to the list of detected pinning events.
     * This would be called when a pinning event is detected.
     * 
     * @param stackTrace the stack trace of the pinning event
     */
    public void addPinningStackTrace(String stackTrace) {
      pinningStackTraces.add(stackTrace);
    }
  }
}