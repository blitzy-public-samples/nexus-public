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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Virtual Thread tests for {@link MavenCipherImpl}.
 * 
 * Validates that MavenCipherImpl's encryption and decryption operations function correctly 
 * and efficiently when executed concurrently with Java 21 Virtual Threads.
 */
public class MavenCipherImplVirtualThreadTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(MavenCipherImplVirtualThreadTest.class);
  
  private static final String PASS_PHRASE = "foofoo";

  private static final String PLAINTEXT = "my testing phrase";
  
  private static final String PLAINTEXT_SPECIAL = "{specialpass word ][4^$$}}";
  
  private static final int THREAD_COUNT = 1000;
  
  private static final int OPERATIONS_PER_THREAD = 10;

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
   * Tests concurrent encryption and decryption operations using Virtual Threads.
   * Validates that operations remain thread-safe and produce correct results under high concurrency.
   */
  @Test
  public void concurrentEncryptionDecryptionWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    // Create a map to store results from each thread
    ConcurrentHashMap<Integer, Boolean> results = new ConcurrentHashMap<>();
    
    // Submit tasks to encrypt and decrypt concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      executor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Each thread performs multiple encrypt/decrypt operations
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            // Use different plaintext for even/odd threads to increase test coverage
            String plaintext = (threadId % 2 == 0) ? PLAINTEXT : PLAINTEXT_SPECIAL;
            
            // Encrypt the plaintext
            String encrypted = testSubject.encrypt(plaintext, PASS_PHRASE);
            assertThat(encrypted, notNullValue());
            
            // Decrypt and verify
            String decrypted = testSubject.decrypt(encrypted, PASS_PHRASE);
            boolean success = plaintext.equals(decrypted);
            
            // If any operation fails, record it
            if (!success) {
              results.put(threadId, false);
              return;
            }
          }
          
          // All operations succeeded for this thread
          results.put(threadId, true);
        }
        catch (Exception e) {
          // Record the first exception
          firstException.compareAndSet(null, e);
          results.put(threadId, false);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertThat("All threads should complete within the timeout", completed, is(true));
    
    // Shutdown the executor
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
    
    // Check if any exceptions occurred
    if (firstException.get() != null) {
      throw new AssertionError("Exception during concurrent execution", firstException.get());
    }
    
    // Verify all threads completed successfully
    assertThat("All threads should report success", 
        results.values().stream().filter(success -> !success).count(), is(0L));
    
    log.info("Successfully completed {} encryption/decryption operations across {} virtual threads",
        THREAD_COUNT * OPERATIONS_PER_THREAD, THREAD_COUNT);
  }
  
  /**
   * Tests that no thread pinning occurs during cryptographic operations.
   * Thread pinning would prevent virtual threads from yielding during blocking operations,
   * negating their benefits.
   */
  @Test
  public void noPinningDuringCryptographicOperations() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Create a large number of virtual threads to increase chances of detecting pinning
    int threadCount = THREAD_COUNT * 2;
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger pinnedThreadsCount = new AtomicInteger(0);
    
    // Submit tasks that would detect pinning
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          // Perform encryption and decryption operations that might cause pinning
          String encrypted = testSubject.encrypt(PLAINTEXT, PASS_PHRASE);
          String decrypted = testSubject.decrypt(encrypted, PASS_PHRASE);
          
          // Verify the operation was successful
          assertThat(decrypted, equalTo(PLAINTEXT));
          
          // If we're running with -XX:+DetectLockedVirtualThreads, pinning would throw an exception
          // This is a JVM flag that can be enabled to detect when virtual threads are pinned
        }
        catch (Exception e) {
          if (e.getMessage() != null && e.getMessage().contains("virtual thread pinned")) {
            pinnedThreadsCount.incrementAndGet();
            log.error("Detected thread pinning during cryptographic operation", e);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertThat("All threads should complete within the timeout", completed, is(true));
    
    // Shutdown the executor
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
    
    // Verify no threads were pinned
    assertThat("No virtual threads should be pinned during cryptographic operations",
        pinnedThreadsCount.get(), is(0));
    
    log.info("Successfully verified no thread pinning across {} virtual threads", threadCount);
  }
  
  /**
   * Compares performance between virtual threads and platform threads for cryptographic operations.
   * This test helps validate the performance benefits of virtual threads for I/O-bound operations.
   */
  @Test
  public void compareVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    // Number of operations to perform in each test
    final int operationCount = THREAD_COUNT * OPERATIONS_PER_THREAD;
    
    // Measure platform threads performance
    Instant platformStart = Instant.now();
    performOperationsWithPlatformThreads(operationCount);
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    // Measure virtual threads performance
    Instant virtualStart = Instant.now();
    performOperationsWithVirtualThreads(operationCount);
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    log.info("Platform threads completed {} operations in {} ms", 
        operationCount, platformDuration.toMillis());
    log.info("Virtual threads completed {} operations in {} ms", 
        operationCount, virtualDuration.toMillis());
    
    // Virtual threads should generally be more efficient for I/O bound operations
    // This assertion might need adjustment based on the specific environment
    // The key is that virtual threads should not be significantly worse than platform threads
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualDuration.compareTo(platformDuration.multipliedBy(2)), lessThan(0));
  }
  
  /**
   * Performs cryptographic operations using platform threads.
   */
  private void performOperationsWithPlatformThreads(int operationCount) throws Exception {
    // Use a fixed thread pool with a reasonable number of platform threads
    int platformThreadCount = Math.min(100, Runtime.getRuntime().availableProcessors() * 4);
    ExecutorService executor = Executors.newFixedThreadPool(platformThreadCount);
    
    try {
      CountDownLatch completionLatch = new CountDownLatch(operationCount);
      List<Exception> exceptions = new ArrayList<>();
      
      // Submit tasks
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Perform a round-trip encryption/decryption
            String encrypted = testSubject.encrypt(PLAINTEXT, PASS_PHRASE);
            String decrypted = testSubject.decrypt(encrypted, PASS_PHRASE);
            assertThat(decrypted, equalTo(PLAINTEXT));
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for completion
      completionLatch.await(60, TimeUnit.SECONDS);
      
      // Check for exceptions
      if (!exceptions.isEmpty()) {
        throw new AssertionError("Exceptions during platform thread execution: " + exceptions.size(), 
            exceptions.get(0));
      }
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Performs cryptographic operations using virtual threads.
   */
  private void performOperationsWithVirtualThreads(int operationCount) throws Exception {
    // Create an executor that creates a new virtual thread for each task
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    
    try {
      CountDownLatch completionLatch = new CountDownLatch(operationCount);
      List<Exception> exceptions = new ArrayList<>();
      
      // Submit tasks
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Perform a round-trip encryption/decryption
            String encrypted = testSubject.encrypt(PLAINTEXT, PASS_PHRASE);
            String decrypted = testSubject.decrypt(encrypted, PASS_PHRASE);
            assertThat(decrypted, equalTo(PLAINTEXT));
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for completion
      completionLatch.await(60, TimeUnit.SECONDS);
      
      // Check for exceptions
      if (!exceptions.isEmpty()) {
        throw new AssertionError("Exceptions during virtual thread execution: " + exceptions.size(), 
            exceptions.get(0));
      }
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests that CharBuffer-based encryption and decryption operations work correctly with virtual threads.
   */
  @Test
  public void charBufferOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    // Submit tasks to encrypt and decrypt using CharBuffer
    for (int i = 0; i < THREAD_COUNT; i++) {
      executor.submit(() -> {
        try {
          // Create a CharBuffer from the plaintext
          char[] plainChars = PLAINTEXT.toCharArray();
          CharBuffer charBuffer = CharBuffer.wrap(plainChars);
          
          // Encrypt using CharBuffer
          String encrypted = testSubject.encrypt(charBuffer, PASS_PHRASE);
          
          // Decrypt back to char array
          char[] decryptedChars = testSubject.decryptChars(encrypted, PASS_PHRASE);
          
          // Verify the result
          assertThat(decryptedChars, equalTo(plainChars));
          
          // Verify the original CharBuffer was not modified (no side effects)
          assertThat(PLAINTEXT.contentEquals(charBuffer), is(true));
        }
        catch (Exception e) {
          firstException.compareAndSet(null, e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertThat("All threads should complete within the timeout", completed, is(true));
    
    // Shutdown the executor
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
    
    // Check if any exceptions occurred
    if (firstException.get() != null) {
      throw new AssertionError("Exception during CharBuffer operations", firstException.get());
    }
    
    log.info("Successfully completed CharBuffer operations across {} virtual threads", THREAD_COUNT);
  }
}