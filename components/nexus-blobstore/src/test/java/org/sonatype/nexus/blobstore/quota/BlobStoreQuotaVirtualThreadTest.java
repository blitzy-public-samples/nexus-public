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
package org.sonatype.nexus.blobstore.quota;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;

import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlobStoreQuotaSupport} using Java 21 Virtual Threads.
 * 
 * This test class validates that BlobStore quota operations remain thread-safe and perform correctly
 * when executed concurrently through thousands of virtual threads.
 */
@ExtendWith(MockitoExtension.class)
public class BlobStoreQuotaVirtualThreadTest
    extends TestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 5000;
  private static final int TIMEOUT_SECONDS = 10;

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreQuotaService quotaService;

  @Mock
  private Logger logger;

  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  void setUp() {
    // Configure mock BlobStore with a name for logging
    BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(config.getName()).thenReturn("test-blobstore");
    
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
  }

  /**
   * Tests that quota check jobs can be executed concurrently by thousands of virtual threads
   * without any thread safety issues.
   */
  @Test
  void testConcurrentQuotaCheckWithVirtualThreads() {
    // Configure quota service to return a passing result (not exceeded)
    BlobStoreQuotaResult result = new BlobStoreQuotaResult(false, "test-blobstore", "Quota not exceeded");
    when(quotaService.checkQuota(blobStore)).thenReturn(result);

    // Use a CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger completedThreads = new AtomicInteger(0);

    // Execute the quota check in multiple virtual threads
    assertTimeout(Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit tasks to the virtual thread executor
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        virtualThreadExecutor.submit(() -> {
          try {
            BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);
            completedThreads.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    });

    // Verify all threads completed successfully
    assertTrue(completedThreads.get() == VIRTUAL_THREAD_COUNT, 
        "Expected all virtual threads to complete, but only " + completedThreads.get() + " completed");
    
    // Verify quota service was called the expected number of times
    verify(quotaService, times(VIRTUAL_THREAD_COUNT)).checkQuota(blobStore);
    
    // Verify logger was never called for warnings (since quota not exceeded)
    verify(logger, never()).warn("Quota not exceeded");
  }

  /**
   * Tests that quota exceeded warnings are properly logged when executed by virtual threads.
   */
  @Test
  void testQuotaExceededWarningWithVirtualThreads() {
    // Configure quota service to return a quota exceeded result
    BlobStoreQuotaResult result = new BlobStoreQuotaResult(true, "test-blobstore", "Quota exceeded warning");
    when(quotaService.checkQuota(blobStore)).thenReturn(result);

    // Use a CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger completedThreads = new AtomicInteger(0);

    // Execute the quota check in multiple virtual threads
    assertTimeout(Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit tasks to the virtual thread executor
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        virtualThreadExecutor.submit(() -> {
          try {
            BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);
            completedThreads.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    });

    // Verify all threads completed successfully
    assertTrue(completedThreads.get() == VIRTUAL_THREAD_COUNT, 
        "Expected all virtual threads to complete, but only " + completedThreads.get() + " completed");
    
    // Verify quota service was called the expected number of times
    verify(quotaService, times(VIRTUAL_THREAD_COUNT)).checkQuota(blobStore);
    
    // Verify logger was called for warnings the expected number of times
    verify(logger, times(VIRTUAL_THREAD_COUNT)).warn("Quota exceeded warning");
  }

  /**
   * Tests that exceptions in quota check are properly handled when executed by virtual threads.
   */
  @Test
  void testQuotaCheckExceptionHandlingWithVirtualThreads() {
    // Configure quota service to throw an exception
    RuntimeException testException = new RuntimeException("Test exception");
    when(quotaService.checkQuota(blobStore)).thenThrow(testException);

    // Use a CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger completedThreads = new AtomicInteger(0);

    // Execute the quota check in multiple virtual threads
    assertTimeout(Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit tasks to the virtual thread executor
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        virtualThreadExecutor.submit(() -> {
          try {
            BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);
            completedThreads.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    });

    // Verify all threads completed successfully
    assertTrue(completedThreads.get() == VIRTUAL_THREAD_COUNT, 
        "Expected all virtual threads to complete, but only " + completedThreads.get() + " completed");
    
    // Verify quota service was called the expected number of times
    verify(quotaService, times(VIRTUAL_THREAD_COUNT)).checkQuota(blobStore);
    
    // Verify logger was called for errors the expected number of times
    verify(logger, times(VIRTUAL_THREAD_COUNT)).error("Failed to check quota for blob store {}", "test-blobstore", testException);
  }

  /**
   * Tests creating and starting individual virtual threads directly using Thread.ofVirtual().start().
   */
  @Test
  void testIndividualVirtualThreadsForQuotaCheck() {
    // Configure quota service to return a passing result
    BlobStoreQuotaResult result = new BlobStoreQuotaResult(false, "test-blobstore", "Quota not exceeded");
    when(quotaService.checkQuota(blobStore)).thenReturn(result);

    // Use a CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(100);
    AtomicInteger completedThreads = new AtomicInteger(0);

    // Create and start individual virtual threads
    assertTimeout(Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      for (int i = 0; i < 100; i++) {
        Thread.ofVirtual().start(() -> {
          try {
            BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);
            completedThreads.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    });

    // Verify all threads completed successfully
    assertTrue(completedThreads.get() == 100, 
        "Expected all virtual threads to complete, but only " + completedThreads.get() + " completed");
    
    // Verify quota service was called the expected number of times
    verify(quotaService, times(100)).checkQuota(blobStore);
  }

  /**
   * Tests that virtual threads can handle high concurrency without thread pinning issues
   * when performing quota checks.
   */
  @Test
  void testHighConcurrencyWithoutThreadPinning() {
    // Configure quota service to return a passing result with a small delay to simulate I/O
    BlobStoreQuotaResult result = new BlobStoreQuotaResult(false, "test-blobstore", "Quota not exceeded");
    doReturn(result).when(quotaService).checkQuota(blobStore);

    // Use a CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger completedThreads = new AtomicInteger(0);

    // Execute the quota check in multiple virtual threads
    assertTimeout(Duration.ofSeconds(TIMEOUT_SECONDS), () -> {
      // Submit tasks to the virtual thread executor
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadNum = i;
        virtualThreadExecutor.submit(() -> {
          try {
            // Simulate different timing patterns to test thread scheduling
            if (threadNum % 3 == 0) {
              Thread.sleep(1); // Small sleep to simulate I/O operations
            }
            BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);
            completedThreads.incrementAndGet();
          } 
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    });

    // Verify all threads completed successfully
    assertTrue(completedThreads.get() == VIRTUAL_THREAD_COUNT, 
        "Expected all virtual threads to complete, but only " + completedThreads.get() + " completed");
    
    // Verify quota service was called the expected number of times
    verify(quotaService, times(VIRTUAL_THREAD_COUNT)).checkQuota(blobStore);
  }
}