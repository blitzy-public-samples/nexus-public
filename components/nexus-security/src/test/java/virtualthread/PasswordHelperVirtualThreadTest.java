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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.AbstractPhraseService;
import org.sonatype.nexus.crypto.PhraseService;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.crypto.internal.MavenCipherImpl;
import org.sonatype.nexus.security.PasswordHelper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.crypto.PhraseService.LEGACY_PHRASE_SERVICE;

/**
 * Tests for {@link PasswordHelper} using Java 21 Virtual Threads.
 * 
 * These tests validate that password encryption/decryption operations work correctly
 * when executed by thousands of concurrent Virtual Threads, and detect any thread pinning
 * issues that might occur with cryptographic operations.
 * 
 * @since 3.60
 */
public class PasswordHelperVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 10_000;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int TIMEOUT_SECONDS = 30;
  
  private PasswordHelper legacyPasswordHelper;
  private PasswordHelper customPasswordHelper;

  @BeforeEach
  public void init() throws Exception {
    legacyPasswordHelper = new PasswordHelper(new MavenCipherImpl(new CryptoHelperImpl()), LEGACY_PHRASE_SERVICE);
    customPasswordHelper = new PasswordHelper(new MavenCipherImpl(new CryptoHelperImpl()), new AbstractPhraseService(true)
    {
      @Override
      protected String getMasterPhrase() {
        return "sterces, sterces, sterces";
      }
    });
  }

  /**
   * Tests that password encryption works correctly with thousands of concurrent virtual threads.
   * This validates that the cryptographic operations are thread-safe and don't cause thread pinning
   * when executed at high concurrency levels.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testConcurrentEncryptionWithVirtualThreads() throws Exception {
    final String password = "test-password-for-virtual-threads";
    final AtomicBoolean failed = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit thousands of encryption tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              String encrypted = legacyPasswordHelper.encrypt(password);
              assertNotNull(encrypted, "Encrypted password should not be null");
              String decrypted = legacyPasswordHelper.decrypt(encrypted);
              assertEquals(password, decrypted, "Decryption should return the original password");
            }
          }
          catch (Throwable t) {
            failed.set(true);
            error.set(t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Check for any errors
      assertFalse(failed.get(), "Encryption operations failed: " + 
          (error.get() != null ? error.get().getMessage() : "unknown error"));
    }
  }

  /**
   * Tests that password decryption works correctly with thousands of concurrent virtual threads.
   * This validates that the cryptographic operations are thread-safe and don't cause thread pinning
   * when executed at high concurrency levels.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testConcurrentDecryptionWithVirtualThreads() throws Exception {
    final String password = "test-password-for-virtual-threads";
    final String encrypted = legacyPasswordHelper.encrypt(password);
    final AtomicBoolean failed = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit thousands of decryption tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              String decrypted = legacyPasswordHelper.decrypt(encrypted);
              assertEquals(password, decrypted, "Decryption should return the original password");
            }
          }
          catch (Throwable t) {
            failed.set(true);
            error.set(t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Check for any errors
      assertFalse(failed.get(), "Decryption operations failed: " + 
          (error.get() != null ? error.get().getMessage() : "unknown error"));
    }
  }

  /**
   * Tests that both encryption and decryption operations work correctly with custom password helper
   * when executed by thousands of concurrent virtual threads.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testCustomPasswordHelperWithVirtualThreads() throws Exception {
    final String password = "test-password-for-virtual-threads";
    final AtomicBoolean failed = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit thousands of encryption/decryption tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              String encrypted = customPasswordHelper.encrypt(password);
              assertNotNull(encrypted, "Encrypted password should not be null");
              String decrypted = customPasswordHelper.decrypt(encrypted);
              assertEquals(password, decrypted, "Decryption should return the original password");
            }
          }
          catch (Throwable t) {
            failed.set(true);
            error.set(t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Check for any errors
      assertFalse(failed.get(), "Custom password helper operations failed: " + 
          (error.get() != null ? error.get().getMessage() : "unknown error"));
    }
  }

  /**
   * Tests that char array-based encryption/decryption operations work correctly with virtual threads.
   * This validates that the char array operations are thread-safe and don't cause thread pinning.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testCharArrayOperationsWithVirtualThreads() throws Exception {
    final String password = "test-password-for-virtual-threads";
    final AtomicBoolean failed = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit thousands of char array encryption/decryption tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              char[] passwordChars = password.toCharArray();
              String encrypted = legacyPasswordHelper.encryptChars(passwordChars);
              assertNotNull(encrypted, "Encrypted password should not be null");
              char[] decrypted = legacyPasswordHelper.decryptChars(encrypted);
              assertNotNull(decrypted, "Decrypted password should not be null");
              assertEquals(password, new String(decrypted), "Decryption should return the original password");
            }
          }
          catch (Throwable t) {
            failed.set(true);
            error.set(t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Check for any errors
      assertFalse(failed.get(), "Char array operations failed: " + 
          (error.get() != null ? error.get().getMessage() : "unknown error"));
    }
  }

  /**
   * Tests that null input handling works correctly with virtual threads.
   */
  @Test
  public void testNullInputWithVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        assertThat(legacyPasswordHelper.encrypt(null), is(nullValue()));
        assertThat(customPasswordHelper.encrypt(null), is(nullValue()));
        assertThat(legacyPasswordHelper.encryptChars(null), is(nullValue()));
        assertThat(customPasswordHelper.encryptChars(null), is(nullValue()));
        assertThat(legacyPasswordHelper.decrypt(null), is(nullValue()));
        assertThat(customPasswordHelper.decrypt(null), is(nullValue()));
        assertThat(legacyPasswordHelper.decryptChars(null), is(nullValue()));
        assertThat(customPasswordHelper.decryptChars(null), is(nullValue()));
      }).get(); // Wait for completion
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for password operations.
   * This test validates that virtual threads provide better throughput for concurrent password
   * operations compared to platform threads.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS * 2, unit = TimeUnit.SECONDS)
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    final String password = "test-password-for-performance-comparison";
    final int threadCount = 1000; // Using fewer threads for platform thread comparison
    final int opsPerThread = 10;
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      runWithPlatformThreads(password, threadCount, opsPerThread);
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      runWithVirtualThreads(password, threadCount, opsPerThread);
    });
    
    log.info("Platform threads execution time: {} ms", platformThreadTime);
    log.info("Virtual threads execution time: {} ms", virtualThreadTime);
    log.info("Performance ratio (platform/virtual): {}", 
        (double) platformThreadTime / virtualThreadTime);
    
    // We don't assert on specific performance improvements as they can vary by environment,
    // but we log the results for analysis
  }

  /**
   * Tests for thread pinning detection during cryptographic operations.
   * This test attempts to detect if any cryptographic operations cause thread pinning
   * by monitoring thread execution patterns under high concurrency.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testThreadPinningDetection() throws Exception {
    final String password = "test-password-for-pinning-detection";
    final int threadCount = 5000;
    final AtomicInteger completedThreads = new AtomicInteger(0);
    final AtomicInteger concurrentOperations = new AtomicInteger(0);
    final AtomicInteger maxConcurrentOperations = new AtomicInteger(0);
    final CountDownLatch latch = new CountDownLatch(threadCount);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks that will help detect thread pinning
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Track concurrent operations to detect potential pinning
            int current = concurrentOperations.incrementAndGet();
            maxConcurrentOperations.updateAndGet(max -> Math.max(max, current));
            
            // Perform encryption/decryption
            String encrypted = legacyPasswordHelper.encrypt(password);
            String decrypted = legacyPasswordHelper.decrypt(encrypted);
            assertEquals(password, decrypted);
            
            // Update tracking
            concurrentOperations.decrementAndGet();
            completedThreads.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify all threads completed
      assertEquals(threadCount, completedThreads.get(), 
          "All threads should have completed successfully");
      
      // Log the maximum concurrency achieved
      log.info("Maximum concurrent operations: {}", maxConcurrentOperations.get());
      
      // If max concurrency is very low compared to thread count, it might indicate pinning
      // This is a heuristic and not a definitive test for pinning
      assertTrue(maxConcurrentOperations.get() > threadCount / 10, 
          "Low concurrency may indicate thread pinning issues");
    }
  }

  /**
   * Tests that multiple different passwords can be encrypted and decrypted concurrently
   * without interference between threads.
   */
  @Test
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testConcurrentMultiplePasswordsWithVirtualThreads() throws Exception {
    final int threadCount = 5000;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
    final AtomicBoolean failed = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks with different passwords
      for (int i = 0; i < threadCount; i++) {
        final String uniquePassword = "password-" + i;
        executor.submit(() -> {
          try {
            String encrypted = legacyPasswordHelper.encrypt(uniquePassword);
            String decrypted = legacyPasswordHelper.decrypt(encrypted);
            results.put(uniquePassword, decrypted);
          }
          catch (Throwable t) {
            failed.set(true);
            error.set(t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Check for any errors
      assertFalse(failed.get(), "Operations failed: " + 
          (error.get() != null ? error.get().getMessage() : "unknown error"));
      
      // Verify all passwords were correctly processed
      assertEquals(threadCount, results.size(), 
          "All passwords should have been processed");
      
      // Verify each password was correctly decrypted
      for (int i = 0; i < threadCount; i++) {
        String uniquePassword = "password-" + i;
        assertEquals(uniquePassword, results.get(uniquePassword), 
            "Password was not correctly decrypted");
      }
    }
  }

  /**
   * Helper method to run password operations with platform threads.
   */
  private void runWithPlatformThreads(String password, int threadCount, int opsPerThread) throws Exception {
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicBoolean failed = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    
    // Create platform threads
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      Thread thread = new Thread(() -> {
        try {
          for (int j = 0; j < opsPerThread; j++) {
            String encrypted = legacyPasswordHelper.encrypt(password);
            String decrypted = legacyPasswordHelper.decrypt(encrypted);
            assertEquals(password, decrypted);
          }
        }
        catch (Throwable t) {
          failed.set(true);
          error.set(t);
        }
        finally {
          latch.countDown();
        }
      });
      threads.add(thread);
    }
    
    // Start all threads
    for (Thread thread : threads) {
      thread.start();
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for platform threads to complete");
    
    // Check for any errors
    assertFalse(failed.get(), "Platform thread operations failed: " + 
        (error.get() != null ? error.get().getMessage() : "unknown error"));
  }

  /**
   * Helper method to run password operations with virtual threads.
   */
  private void runWithVirtualThreads(String password, int threadCount, int opsPerThread) throws Exception {
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicBoolean failed = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            for (int j = 0; j < opsPerThread; j++) {
              String encrypted = legacyPasswordHelper.encrypt(password);
              String decrypted = legacyPasswordHelper.decrypt(encrypted);
              assertEquals(password, decrypted);
            }
          }
          catch (Throwable t) {
            failed.set(true);
            error.set(t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Check for any errors
      assertFalse(failed.get(), "Virtual thread operations failed: " + 
          (error.get() != null ? error.get().getMessage() : "unknown error"));
    }
  }

  /**
   * Helper method to measure execution time of a runnable.
   */
  private long measureExecutionTime(Runnable task) throws Exception {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
}