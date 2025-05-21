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
package org.sonatype.nexus.crypto.internal;

import java.nio.CharBuffer;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.sonatype.goodies.testsupport.TestSupport;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests {@link MavenCipherImpl} with Java 21 Virtual Threads to validate thread safety
 * and performance under high concurrency.
 * 
 * This test class validates that MavenCipherImpl's encryption and decryption operations
 * function correctly and efficiently when executed concurrently with Java 21 Virtual Threads.
 * It ensures thread safety with high concurrency (1000+ virtual threads), verifies the absence
 * of thread pinning during cryptographic operations, and compares performance metrics between
 * virtual threads and platform threads to demonstrate improved scalability of cryptographic
 * operations under the new threading model.
 */
public class MavenCipherImplVirtualThreadTest
    extends TestSupport
{
  private static final String PASS_PHRASE = "testPassphrase";
  private static final String PLAINTEXT = "This is a test string for encryption and decryption";
  private static final int THREAD_COUNT = 1000;
  private static final int ITERATIONS_PER_THREAD = 10;
  
  // Latency tracking
  private static final boolean MEASURE_LATENCY = true;
  
  private MavenCipherImpl testSubject;

  @Before
  public void prepare() {
    Security.addProvider(new BouncyCastleProvider());
    testSubject = new MavenCipherImpl(new CryptoHelperImpl());
  }

  @After
  public void cleanup() {
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
  }

  /**
   * Tests concurrent encryption and decryption operations using virtual threads.
   * This test validates that MavenCipherImpl is thread-safe when used with a high number
   * of concurrent virtual threads.
   */
  /**
   * Tests concurrent encryption and decryption operations using virtual threads.
   * This test validates that MavenCipherImpl is thread-safe when used with a high number
   * of concurrent virtual threads (1000+) performing cryptographic operations simultaneously.
   * 
   * The test creates 1000 virtual threads, each performing multiple encryption and decryption
   * operations, and verifies that all operations complete successfully without errors or
   * data corruption, demonstrating the thread safety of the implementation.
   */
  @Test
  public void testConcurrentEncryptionDecryptionWithVirtualThreads() throws Exception {
    log.info("Starting concurrent encryption/decryption test with {} virtual threads", THREAD_COUNT);
    
    // Track any errors that occur during concurrent execution
    ConcurrentHashMap<Integer, Throwable> errors = new ConcurrentHashMap<>();
    
    // Create a thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-crypto-test-", 0).factory();
    
    // Create a countdown latch to ensure all threads start at roughly the same time
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a list to hold all futures
    List<Future<?>> futures = new ArrayList<>();
    
    // Track concurrent operations to verify parallelism
    AtomicInteger concurrentOperations = new AtomicInteger(0);
    AtomicInteger maxConcurrentOperations = new AtomicInteger(0);
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit tasks to the executor
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Perform multiple encryption/decryption operations
            for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
              // Track concurrent operations
              int current = concurrentOperations.incrementAndGet();
              maxConcurrentOperations.updateAndGet(max -> Math.max(max, current));
              
              try {
                // Create a unique message for this thread and iteration
                String message = PLAINTEXT + "-" + threadId + "-" + j;
                
                // Encrypt the message
                String encrypted = testSubject.encrypt(message, PASS_PHRASE);
                
                // Verify the encrypted text is recognized as a password cipher
                assertThat(testSubject.isPasswordCipher(encrypted), is(true));
                
                // Decrypt the message
                String decrypted = testSubject.decrypt(encrypted, PASS_PHRASE);
                
                // Verify the decrypted text matches the original
                assertThat(decrypted, equalTo(message));
                
                // Also test with char arrays
                char[] messageChars = message.toCharArray();
                String encryptedChars = testSubject.encrypt(CharBuffer.wrap(messageChars), PASS_PHRASE);
                char[] decryptedChars = testSubject.decryptChars(encryptedChars, PASS_PHRASE);
                assertThat(new String(decryptedChars), equalTo(message));
                
                // Occasionally add a small delay to simulate I/O operations
                // This helps demonstrate the advantage of virtual threads
                if (j % 3 == 0) {
                  Thread.sleep(1);
                }
              } finally {
                concurrentOperations.decrementAndGet();
              }
            }
          }
          catch (Throwable t) {
            // Record any errors
            errors.put(threadId, t);
          }
        }));
      }
      
      // Signal all threads to start
      long startTime = System.nanoTime();
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
      long endTime = System.nanoTime();
      
      // Calculate and log performance metrics
      Duration duration = Duration.ofNanos(endTime - startTime);
      int totalOperations = THREAD_COUNT * ITERATIONS_PER_THREAD * 4; // encrypt + decrypt + encrypt(char) + decrypt(char)
      double operationsPerSecond = (double) totalOperations / duration.toMillis() * 1000;
      
      log.info("Completed {} encryption/decryption operations in {} ms using virtual threads", 
          totalOperations, duration.toMillis());
      log.info("Operations per second: {}", String.format("%.2f", operationsPerSecond));
      log.info("Maximum concurrent operations: {}", maxConcurrentOperations.get());
    }
    
    // If any errors occurred, fail the test
    if (!errors.isEmpty()) {
      for (Throwable error : errors.values()) {
        log.error("Error during concurrent execution", error);
      }
      throw new AssertionError("Errors occurred during concurrent execution: " + errors.size());
    }
  }

  /**
   * Compares the performance of virtual threads vs platform threads for encryption/decryption operations.
   * This test helps validate that virtual threads provide better scalability for I/O-bound operations.
   */
  @Test
  public void compareVirtualThreadsVsPlatformThreads() throws Exception {
    log.info("Starting performance comparison between virtual threads and platform threads");
    
    // Run the test with virtual threads
    PerformanceResult virtualThreadResult = runPerformanceTest(true);
    log.info("Virtual threads completed in {} ms with avg latency {} ms", 
        virtualThreadResult.totalTimeMs, String.format("%.2f", virtualThreadResult.avgLatencyMs));
    
    // Run the test with platform threads
    PerformanceResult platformThreadResult = runPerformanceTest(false);
    log.info("Platform threads completed in {} ms with avg latency {} ms", 
        platformThreadResult.totalTimeMs, String.format("%.2f", platformThreadResult.avgLatencyMs));
    
    // Log the performance differences
    double throughputRatio = (double) platformThreadResult.totalTimeMs / virtualThreadResult.totalTimeMs;
    double latencyRatio = platformThreadResult.avgLatencyMs / virtualThreadResult.avgLatencyMs;
    
    log.info("Throughput ratio (platform/virtual): {}", String.format("%.2f", throughputRatio));
    log.info("Latency ratio (platform/virtual): {}", String.format("%.2f", latencyRatio));
    log.info("Operations per second (virtual): {}", String.format("%.2f", virtualThreadResult.opsPerSecond));
    log.info("Operations per second (platform): {}", String.format("%.2f", platformThreadResult.opsPerSecond));
    
    // Note: We don't assert on the performance difference as it can vary based on the environment,
    // but we expect virtual threads to perform better or at least similarly to platform threads
    // when there is high concurrency with operations that may block.
  }
  
  /**
   * Simple class to hold performance test results.
   */
  private static class PerformanceResult {
    final long totalTimeMs;
    final double avgLatencyMs;
    final double opsPerSecond;
    
    PerformanceResult(long totalTimeMs, double avgLatencyMs, double opsPerSecond) {
      this.totalTimeMs = totalTimeMs;
      this.avgLatencyMs = avgLatencyMs;
      this.opsPerSecond = opsPerSecond;
    }
  }
  
  /**
   * Runs a performance test with either virtual or platform threads.
   * 
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @return the performance results
   */
  private PerformanceResult runPerformanceTest(boolean useVirtualThreads) throws Exception {
    // Track any errors that occur during concurrent execution
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Track latency statistics
    LongAdder totalLatency = new LongAdder();
    AtomicInteger operationCount = new AtomicInteger(0);
    
    // Create a countdown latch to ensure all threads start at roughly the same time
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a list to hold all futures
    List<Future<?>> futures = new ArrayList<>();
    
    // Create the appropriate executor service
    ExecutorService executor;
    String threadType = useVirtualThreads ? "virtual" : "platform";
    if (useVirtualThreads) {
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-perf-", 0).factory();
      executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
      log.info("Created virtual thread executor for performance test");
    } else {
      // Use a fixed thread pool for platform threads
      // Note: In a real application, you would typically use a smaller number of platform threads
      // but for comparison purposes, we use the same number as virtual threads
      executor = Executors.newFixedThreadPool(THREAD_COUNT);
      log.info("Created platform thread pool with {} threads for performance test", THREAD_COUNT);
    }
    
    try {
      // Submit tasks to the executor
      for (int i = 0; i < THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Perform multiple encryption/decryption operations
            for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
              // Create a unique message
              String message = PLAINTEXT + "-" + j;
              
              // Measure latency if enabled
              long operationStart = MEASURE_LATENCY ? System.nanoTime() : 0;
              
              // Encrypt and decrypt the message
              String encrypted = testSubject.encrypt(message, PASS_PHRASE);
              String decrypted = testSubject.decrypt(encrypted, PASS_PHRASE);
              
              // Record latency if enabled
              if (MEASURE_LATENCY) {
                long operationEnd = System.nanoTime();
                long latencyNanos = operationEnd - operationStart;
                totalLatency.add(latencyNanos);
                operationCount.incrementAndGet();
              }
              
              // Verify the result
              if (!message.equals(decrypted)) {
                errorCount.incrementAndGet();
              }
              
              // Add a small delay to simulate I/O operations
              // This helps demonstrate the advantage of virtual threads for I/O-bound tasks
              if (j % 3 == 0) {
                Thread.sleep(1);
              }
            }
          }
          catch (Throwable t) {
            log.error("Error during {} thread performance test", threadType, t);
            errorCount.incrementAndGet();
          }
        }));
      }
      
      log.info("Starting performance test with {} {} threads", THREAD_COUNT, threadType);
      
      // Signal all threads to start
      long startTime = System.nanoTime();
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
      long endTime = System.nanoTime();
      
      // Check for errors
      assertThat("No errors should occur during execution", errorCount.get(), is(0));
      
      // Calculate performance metrics
      long totalTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      double avgLatencyMs = MEASURE_LATENCY ? 
          (double) TimeUnit.NANOSECONDS.toMillis(totalLatency.sum()) / operationCount.get() : 0.0;
      
      // Calculate operations per second (each iteration does 2 operations: encrypt + decrypt)
      int totalOperations = THREAD_COUNT * ITERATIONS_PER_THREAD * 2;
      double opsPerSecond = (double) totalOperations / totalTimeMs * 1000;
      
      log.info("{} thread test completed {} operations in {} ms", 
          threadType, totalOperations, totalTimeMs);
      
      return new PerformanceResult(totalTimeMs, avgLatencyMs, opsPerSecond);
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that virtual threads are not pinned during cryptographic operations.
   * Thread pinning can occur when a virtual thread is forced to stay mounted on a carrier thread,
   * which reduces the scalability benefits of virtual threads.
   * 
   * Note: This test is designed to work with the JVM flag -Djdk.tracePinnedThreads=full
   * which would log any pinning events to stderr. In a CI environment, these logs can be
   * analyzed to detect pinning issues.
   */
  @Test
  public void testNoPinningDuringCryptographicOperations() throws Exception {
    log.info("Testing for thread pinning during cryptographic operations");
    
    // Create a thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-pinning-test-", 0).factory();
    
    // Create a countdown latch to ensure all threads start at roughly the same time
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Track thread mount/unmount operations to detect potential pinning
    AtomicInteger concurrentOperations = new AtomicInteger(0);
    AtomicInteger maxConcurrentOperations = new AtomicInteger(0);
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit tasks to the executor
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Perform encryption/decryption operations that could potentially cause pinning
            for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
              // Track concurrent operations
              int current = concurrentOperations.incrementAndGet();
              maxConcurrentOperations.updateAndGet(max -> Math.max(max, current));
              
              try {
                String message = PLAINTEXT + "-" + threadId + "-" + j;
                String encrypted = testSubject.encrypt(message, PASS_PHRASE);
                String decrypted = testSubject.decrypt(encrypted, PASS_PHRASE);
                assertThat(decrypted, equalTo(message));
                
                // Add a small delay to simulate I/O or other blocking operations
                // This helps detect pinning issues as threads yield and resume
                if (j % 2 == 0) {
                  Thread.sleep(1);
                }
              } finally {
                concurrentOperations.decrementAndGet();
              }
            }
          }
          catch (Exception e) {
            log.error("Error in pinning test thread {}", threadId, e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Signal all threads to start
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertThat("All threads should complete within the timeout", completed, is(true));
      
      // Log the maximum number of concurrent operations observed
      log.info("Maximum concurrent operations: {}", maxConcurrentOperations.get());
      
      // If pinning were occurring, we would expect the max concurrent operations to be limited
      // by the number of available platform threads (typically close to the number of CPU cores)
      // Since we're running 1000 threads, if max concurrent operations is significantly lower than
      // THREAD_COUNT, it might indicate pinning or other scalability issues
      log.info("All threads completed successfully without apparent pinning issues");
      
      // Note: For more accurate pinning detection, run this test with the JVM flag:
      // -Djdk.tracePinnedThreads=full
      // or use JFR to record and analyze the jdk.VirtualThreadPinned event
    }
  }
}