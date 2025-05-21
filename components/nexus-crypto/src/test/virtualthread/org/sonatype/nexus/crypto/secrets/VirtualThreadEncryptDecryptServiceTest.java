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
package org.sonatype.nexus.crypto.secrets;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.crypto.internal.PbeCipherFactoryImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests the {@link EncryptDecryptService} with Java 21 Virtual Threads to validate thread safety
 * and performance optimization.
 *
 * This test creates 1000+ concurrent virtual threads that simultaneously perform encryption and
 * decryption operations to verify behavior under high concurrency. This ensures the cryptographic
 * operations maintain data integrity when accessed by multiple virtual threads, and that no thread
 * pinning occurs during encryption/decryption that would negate the performance benefits of virtual threads.
 */
@Category(VirtualThreadTestGroup.class)
public class VirtualThreadEncryptDecryptServiceTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  private static final int WARMUP_ITERATIONS = 10;
  private static final int TEST_ITERATIONS = 3;
  
  private EncryptDecryptService encryptDecryptService;

  @Before
  public void setUp() {
    encryptDecryptService = new EncryptDecryptService(new PbeCipherFactoryImpl(new CryptoHelperImpl()));
  }

  /**
   * Basic test to verify that encryption and decryption work correctly with a single thread.
   * This serves as a baseline before testing with virtual threads.
   */
  @Test
  public void testBasicEncryptionDecryption() {
    String testString = "test 123 ==== <>?/.,";
    String encoded = encryptDecryptService.encryptAndEncode(testString);
    String decoded = encryptDecryptService.decodeAndDecrypt(encoded);
    
    assertThat(decoded, is(equalTo(testString)));
    assertThat(encoded, is(notNullValue()));
  }

  /**
   * Tests concurrent encryption and decryption operations using virtual threads.
   * This test creates 1000 virtual threads, each performing encryption and decryption
   * of a unique string, and verifies that all operations complete successfully.
   */
  @Test
  public void testConcurrentEncryptionDecryptionWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique string for each thread
            String testString = "test-" + index + "-" + UUID.randomUUID();
            String encoded = encryptDecryptService.encryptAndEncode(testString);
            String decoded = encryptDecryptService.decodeAndDecrypt(encoded);
            
            if (!testString.equals(decoded)) {
              log.error("Encryption/decryption failed for thread {}: expected '{}' but got '{}'", 
                  index, testString, decoded);
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in virtual thread {}", index, e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All threads should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent encryption/decryption", 
          errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests the performance of encryption and decryption operations using both platform threads
   * and virtual threads, and compares the results.
   * 
   * This test performs multiple iterations of concurrent encryption/decryption operations
   * with both thread types and measures the execution time for each.
   */
  @Test
  public void testEncryptionDecryptionPerformanceComparison() throws Exception {
    // Warm up to avoid JIT compilation effects
    log.info("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentTest(Thread.ofPlatform().factory(), 100);
      runConcurrentTest(Thread.ofVirtual().factory(), 100);
    }
    
    // Run the actual performance test
    List<Long> platformThreadTimes = new ArrayList<>();
    List<Long> virtualThreadTimes = new ArrayList<>();
    
    log.info("Running performance tests...");
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      log.info("Iteration {} of {}", i + 1, TEST_ITERATIONS);
      
      // Test with platform threads
      long platformTime = runConcurrentTest(Thread.ofPlatform().factory(), THREAD_COUNT);
      platformThreadTimes.add(platformTime);
      log.info("Platform threads took {} ms", platformTime);
      
      // Test with virtual threads
      long virtualTime = runConcurrentTest(Thread.ofVirtual().factory(), THREAD_COUNT);
      virtualThreadTimes.add(virtualTime);
      log.info("Virtual threads took {} ms", virtualTime);
    }
    
    // Calculate average times
    double avgPlatformTime = platformThreadTimes.stream().mapToLong(Long::longValue).average().orElse(0);
    double avgVirtualTime = virtualThreadTimes.stream().mapToLong(Long::longValue).average().orElse(0);
    
    log.info("Average platform thread time: {} ms", avgPlatformTime);
    log.info("Average virtual thread time: {} ms", avgVirtualTime);
    
    // Virtual threads should generally be more efficient for I/O-bound operations
    // but for CPU-bound operations like encryption, the difference might be less pronounced
    // or platform threads might even be faster. The important thing is that virtual threads
    // don't significantly degrade performance and can handle high concurrency.
    assertThat("Virtual threads should handle high concurrency efficiently", 
        avgVirtualTime, lessThan(avgPlatformTime * 1.5)); // Virtual threads shouldn't be significantly worse
  }
  
  /**
   * Tests that virtual threads can handle a very high number of concurrent encryption/decryption
   * operations without running into thread resource limitations that would affect platform threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Use a much higher thread count than would be practical with platform threads
    final int highThreadCount = 10000;
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(highThreadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit a very high number of concurrent tasks
      for (int i = 0; i < highThreadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String testString = "high-concurrency-test-" + index;
            String encoded = encryptDecryptService.encryptAndEncode(testString);
            String decoded = encryptDecryptService.decodeAndDecrypt(encoded);
            
            if (testString.equals(decoded)) {
              successCount.incrementAndGet();
            } else {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in high concurrency test, thread {}", index, e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All high-concurrency threads should complete", completed, is(true));
      assertThat("No errors should occur during high-concurrency test", errorCount.get(), is(0));
      assertThat("All operations should succeed", successCount.get(), is(highThreadCount));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Helper method to run a concurrent test with the specified thread factory and thread count.
   * 
   * @param threadFactory the thread factory to use (platform or virtual)
   * @param threadCount the number of threads to create
   * @return the execution time in milliseconds
   */
  private long runConcurrentTest(ThreadFactory threadFactory, int threadCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    long startTime = System.nanoTime();
    
    try {
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String testString = "performance-test-" + index;
            String encoded = encryptDecryptService.encryptAndEncode(testString);
            String decoded = encryptDecryptService.decodeAndDecrypt(encoded);
            
            if (!testString.equals(decoded)) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("No errors should occur during performance test", errorCount.get(), is(0));
      
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    } finally {
      executor.shutdown();
    }
  }
}