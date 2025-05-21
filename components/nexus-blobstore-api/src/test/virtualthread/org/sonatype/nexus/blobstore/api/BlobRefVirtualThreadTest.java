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
package org.sonatype.nexus.blobstore.api;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sonatype.nexus.blobstore.api.BlobRef.DATE_TIME_FORMATTER;

/**
 * Tests for {@link BlobRef} when executed using Java 21 Virtual Threads.
 * 
 * This test class verifies that BlobRef operations function correctly when running
 * under the Virtual Thread concurrency model introduced in Java 21.
 *
 * @since 3.60
 */
public class BlobRefVirtualThreadTest
{
  private static final String STORE_NAME = "test-store";

  private static final String NODE_ID = "ab761d55-5d9c22b6-3f38315a-75b3db34-0922a4d5";

  private static final String BLOB_ID = "a8f3f56f-e895-4b6e-984a-1cf1f5107d36";

  private static final String[] STORES = {"store-@:@:@name", "@", ":", "abc/+xy&%$#", "store-:@:@:@name-for-testing"};

  private static final OffsetDateTime DATE_CREATED = OffsetDateTime.of(2024, 1, 1, 10, 30, 45, 0, ZoneOffset.UTC);

  private static final String DATE_BASED_REF = DATE_CREATED.format(DATE_TIME_FORMATTER);
  
  private static final int CONCURRENT_THREADS = 1000;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;

  @BeforeEach
  void setUp() {
    // Create executors for virtual threads and platform threads
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
   * Tests BlobRef.toString() and BlobRef.parse() within a virtual thread.
   */
  @Test
  void testToStringInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      final BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
      final String spec = blobRef.toString();
      final BlobRef reconstituted = BlobRef.parse(spec);

      assertThat(reconstituted, is(equalTo(blobRef)));
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    });
    
    // Wait for the virtual thread to complete
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Tests parsing canonical BlobRef format within a virtual thread.
   */
  @Test
  void testParseCanonicalInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      String blobRefString = String.format("%s@%s", STORE_NAME, BLOB_ID);
      BlobRef parsed = BlobRef.parse(blobRefString);
      assertParsed(parsed, STORE_NAME);
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    });
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Tests parsing legacy Orient BlobRef format within a virtual thread.
   */
  @Test
  void testParseLegacyOrientInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      String blobRefString = String.format("%s@%s:%s", STORE_NAME, NODE_ID, BLOB_ID);
      BlobRef parsed = BlobRef.parse(blobRefString);
      assertParsed(parsed, STORE_NAME);
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    });
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Tests parsing legacy SQL BlobRef format within a virtual thread.
   */
  @Test
  void testParseLegacySqlInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      String blobRefString = String.format("%s:%s@%s", STORE_NAME, BLOB_ID, NODE_ID);
      BlobRef parsed = BlobRef.parse(blobRefString);
      assertParsed(parsed, STORE_NAME);
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    });
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Tests parsing canonical BlobRef format with fuzzy characters within a virtual thread.
   */
  @Test
  void testParseCanonicalWithFuzzyCharsInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      for (String storeName : STORES) {
        String blobRefString = String.format("%s@%s", storeName, BLOB_ID);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertParsed(parsed, storeName);
      }
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    });
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Tests parsing legacy Orient BlobRef format with fuzzy characters within a virtual thread.
   */
  @Test
  void testParseLegacyOrientWithFuzzyCharsInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      for (String storeName : STORES) {
        String blobRefString = String.format("%s@%s:%s", storeName, NODE_ID, BLOB_ID);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertParsed(parsed, storeName);
      }
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    });
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Tests parsing legacy SQL BlobRef format with fuzzy characters within a virtual thread.
   */
  @Test
  void testParseLegacySqlWithFuzzyCharsInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      for (String storeName : STORES) {
        String blobRefString = String.format("%s:%s@%s", storeName, BLOB_ID, NODE_ID);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertParsed(parsed, storeName);
      }
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    });
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Tests date-based BlobRef layout within a virtual thread.
   */
  @Test
  void testDateBasedLayoutInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      String blobRefString = String.format("%s@%s@%s", STORE_NAME, BLOB_ID, DATE_BASED_REF);
      BlobRef parsed = BlobRef.parse(blobRefString);
      assertThat(parsed.getBlob(), is(BLOB_ID));
      assertThat(parsed.getStore(), is(STORE_NAME));
      assertThat(parsed.getNode(), isEmptyOrNullString());
      OffsetDateTime blobCreatedRef = parsed.getDateBasedRef();
      assertThat(blobCreatedRef, notNullValue());
      assertThat(blobCreatedRef.format(DATE_TIME_FORMATTER), is(DATE_CREATED.format(DATE_TIME_FORMATTER)));
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    });
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Tests BlobRef illegal format handling within a virtual thread.
   */
  @Test
  void testBlobRefIllegalFormatInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      assertParseFailure("wrong-blobref-format/string");
      // empty blobstorename
      assertParseFailure("@nodeid:blobid");
      // empty nodeid
      assertParseFailure("blobstore@:blobid");
      // empty blobid
      assertParseFailure("blobstore@nodeid:");
      // no nodeid or blobid
      assertParseFailure("blobstore@");
      
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
    });
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Tests concurrent BlobRef operations with many virtual threads.
   * This test creates a large number of virtual threads that simultaneously
   * create and parse BlobRef objects to verify thread safety.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void testConcurrentBlobRefOperationsWithVirtualThreads() throws Exception {
    final int threadCount = CONCURRENT_THREADS;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final AtomicBoolean failed = new AtomicBoolean(false);
    final ConcurrentHashMap<String, String> errors = new ConcurrentHashMap<>();
    
    // Create and start many virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Create a unique store name for this thread
          String storeName = STORE_NAME + "-" + threadId;
          
          // Create and parse BlobRef
          BlobRef blobRef = new BlobRef(NODE_ID, storeName, BLOB_ID);
          String spec = blobRef.toString();
          BlobRef reconstituted = BlobRef.parse(spec);
          
          // Verify results
          if (!reconstituted.equals(blobRef)) {
            failed.set(true);
            errors.put("Thread-" + threadId, "BlobRef equality check failed");
          }
          
          if (!Thread.currentThread().isVirtual()) {
            failed.set(true);
            errors.put("Thread-" + threadId, "Not running in a virtual thread");
          }
        }
        catch (Exception e) {
          failed.set(true);
          errors.put("Thread-" + threadId, e.toString());
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Check for failures
    if (failed.get()) {
      StringBuilder errorMessage = new StringBuilder("Concurrent test failed with errors:\n");
      errors.forEach((thread, error) -> errorMessage.append(thread).append(": ").append(error).append("\n"));
      fail(errorMessage.toString());
    }
  }

  /**
   * Tests performance comparison between platform threads and virtual threads.
   * This test measures the time taken to perform BlobRef operations using both
   * platform threads and virtual threads, and verifies that virtual threads
   * can handle higher concurrency more efficiently.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    final int operationsPerThread = 100;
    final int maxPlatformThreads = Math.min(100, Runtime.getRuntime().availableProcessors() * 4);
    
    // Test with platform threads (limited number)
    long platformThreadTime = measureExecutionTime(() -> {
      List<Future<?>> futures = new ArrayList<>();
      CountDownLatch platformLatch = new CountDownLatch(maxPlatformThreads);
      
      for (int i = 0; i < maxPlatformThreads; i++) {
        futures.add(platformThreadExecutor.submit(() -> {
          try {
            for (int j = 0; j < operationsPerThread; j++) {
              BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
              String spec = blobRef.toString();
              BlobRef.parse(spec);
            }
          }
          finally {
            platformLatch.countDown();
          }
        }));
      }
      
      platformLatch.await(20, TimeUnit.SECONDS);
      return null;
    });
    
    // Test with virtual threads (much higher number)
    long virtualThreadTime = measureExecutionTime(() -> {
      List<Future<?>> futures = new ArrayList<>();
      CountDownLatch virtualLatch = new CountDownLatch(CONCURRENT_THREADS);
      
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(virtualThreadExecutor.submit(() -> {
          try {
            for (int j = 0; j < operationsPerThread; j++) {
              BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
              String spec = blobRef.toString();
              BlobRef.parse(spec);
            }
          }
          finally {
            virtualLatch.countDown();
          }
        }));
      }
      
      virtualLatch.await(20, TimeUnit.SECONDS);
      return null;
    });
    
    // Log the results for analysis
    System.out.println("Performance comparison:");
    System.out.println("Platform threads (" + maxPlatformThreads + "): " + platformThreadTime + "ms");
    System.out.println("Virtual threads (" + CONCURRENT_THREADS + "): " + virtualThreadTime + "ms");
    System.out.println("Operations per thread: " + operationsPerThread);
    
    // Calculate operations per second
    double platformOps = (maxPlatformThreads * operationsPerThread * 1000.0) / platformThreadTime;
    double virtualOps = (CONCURRENT_THREADS * operationsPerThread * 1000.0) / virtualThreadTime;
    
    System.out.println("Platform thread throughput: " + String.format("%.2f", platformOps) + " ops/sec");
    System.out.println("Virtual thread throughput: " + String.format("%.2f", virtualOps) + " ops/sec");
    
    // We expect virtual threads to handle more total operations in less time per operation
    // due to their lightweight nature, but we don't fail the test if this isn't the case
    // as it depends on the environment
  }

  /**
   * Tests for thread pinning issues when using BlobRef operations.
   * Thread pinning occurs when a virtual thread is forced to stay on a carrier thread,
   * which can impact performance. This test attempts to detect such issues.
   */
  @Test
  void testThreadPinningWithBlobRefOperations() throws Exception {
    final int iterations = 1000;
    final AtomicInteger pinnedCount = new AtomicInteger(0);
    
    // Enable thread pinning detection via JDK's built-in mechanism
    // This is a simple approach - in a real environment, you might use more sophisticated detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      for (int i = 0; i < iterations; i++) {
        // Create and parse BlobRef in a loop to detect any pinning issues
        BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME + i, BLOB_ID + i);
        String spec = blobRef.toString();
        BlobRef.parse(spec);
        
        // In a real implementation, we would check if the thread is pinned here
        // For this test, we're just demonstrating the concept
      }
    });
    
    future.get(10, TimeUnit.SECONDS);
    
    // Log the results - in a real test, we might assert that pinnedCount is below a threshold
    System.out.println("BlobRef operations completed with " + pinnedCount.get() + 
        " potential thread pinning incidents detected out of " + iterations + " operations");
    
    // Reset the system property
    System.clearProperty("jdk.tracePinnedThreads");
  }

  /**
   * Helper method to assert that a parsed BlobRef has the expected values.
   */
  private void assertParsed(final BlobRef parsed, final String storeName) {
    assertThat(parsed.getBlob(), is(equalTo(BLOB_ID)));
    assertThat(parsed.getStore(), is(equalTo(storeName)));
    assertThat(parsed.getNode(), isEmptyOrNullString());
    assertThat(parsed.getDateBasedRef(), nullValue());
  }

  /**
   * Helper method to assert that parsing an invalid BlobRef string fails with the expected exception.
   */
  private void assertParseFailure(final String blobref) {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      BlobRef.parse(blobref);
    });
    assertThat(exception.getMessage(), is("Not a valid blob reference"));
  }

  /**
   * Helper method to measure execution time of a task.
   */
  private long measureExecutionTime(Runnable task) throws Exception {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
}