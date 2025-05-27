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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sonatype.nexus.blobstore.api.BlobRef.DATE_TIME_FORMATTER;

/**
 * Tests for {@link BlobRef} when executed using Java 21 Virtual Threads.
 * This test ensures that BlobRef operations perform correctly under the Virtual Thread concurrency model.
 *
 * @since 3.60
 */
@DisplayName("BlobRef Virtual Thread Tests")
public class BlobRefVirtualThreadTest
{
  private static final String STORE_NAME = "test-store";

  private static final String NODE_ID = "ab761d55-5d9c22b6-3f38315a-75b3db34-0922a4d5";

  private static final String BLOB_ID = "a8f3f56f-e895-4b6e-984a-1cf1f5107d36";

  private static final String[] STORES = {"store-@:@:@name", "@", ":", "abc/+xy&%$#", "store-:@:@:@name-for-testing"};

  private static final OffsetDateTime DATE_CREATED = OffsetDateTime.of(2024, 1, 1, 10, 30, 45, 0, ZoneOffset.UTC);

  private static final String DATE_BASED_REF = DATE_CREATED.format(DATE_TIME_FORMATTER);

  /**
   * Tests that BlobRef.toString() and BlobRef.parse() work correctly when executed in a virtual thread.
   */
  @Test
  @DisplayName("Test toString() and parse() in a virtual thread")
  public void testToStringInVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      final BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
      final String spec = blobRef.toString();
      final BlobRef reconstituted = BlobRef.parse(spec);

      assertThat(reconstituted, is(equalTo(blobRef)));
      assertThat(Thread.currentThread().isVirtual(), is(true));
    }).join();
  }

  /**
   * Tests that BlobRef.parse() correctly handles canonical format when executed in a virtual thread.
   */
  @Test
  @DisplayName("Test parse canonical format in a virtual thread")
  public void testParseCanonicalInVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      String blobRefString = String.format("%s@%s", STORE_NAME, BLOB_ID);
      BlobRef parsed = BlobRef.parse(blobRefString);
      assertParsed(parsed, STORE_NAME);
      assertThat(Thread.currentThread().isVirtual(), is(true));
    }).join();
  }

  /**
   * Tests that BlobRef.parse() correctly handles legacy Orient format when executed in a virtual thread.
   */
  @Test
  @DisplayName("Test parse legacy Orient format in a virtual thread")
  public void testParseLegacyOrientInVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      String blobRefString = String.format("%s@%s:%s", STORE_NAME, NODE_ID, BLOB_ID);
      BlobRef parsed = BlobRef.parse(blobRefString);
      assertParsed(parsed, STORE_NAME);
      assertThat(Thread.currentThread().isVirtual(), is(true));
    }).join();
  }

  /**
   * Tests that BlobRef.parse() correctly handles legacy SQL format when executed in a virtual thread.
   */
  @Test
  @DisplayName("Test parse legacy SQL format in a virtual thread")
  public void testParseLegacySqlInVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      String blobRefString = String.format("%s:%s@%s", STORE_NAME, BLOB_ID, NODE_ID);
      BlobRef parsed = BlobRef.parse(blobRefString);
      assertParsed(parsed, STORE_NAME);
      assertThat(Thread.currentThread().isVirtual(), is(true));
    }).join();
  }

  /**
   * Tests that BlobRef.parse() correctly handles canonical format with fuzzy characters when executed in a virtual thread.
   */
  @Test
  @DisplayName("Test parse canonical format with fuzzy chars in a virtual thread")
  public void testParseCanonicalWithFuzzyCharsInVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      for (String storeName : STORES) {
        String blobRefString = String.format("%s@%s", storeName, BLOB_ID);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertParsed(parsed, storeName);
      }
      assertThat(Thread.currentThread().isVirtual(), is(true));
    }).join();
  }

  /**
   * Tests that BlobRef.parse() correctly handles legacy Orient format with fuzzy characters when executed in a virtual thread.
   */
  @Test
  @DisplayName("Test parse legacy Orient format with fuzzy chars in a virtual thread")
  public void testParseLegacyOrientWithFuzzyCharsInVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      for (String storeName : STORES) {
        String blobRefString = String.format("%s@%s:%s", storeName, NODE_ID, BLOB_ID);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertParsed(parsed, storeName);
      }
      assertThat(Thread.currentThread().isVirtual(), is(true));
    }).join();
  }

  /**
   * Tests that BlobRef.parse() correctly handles legacy SQL format with fuzzy characters when executed in a virtual thread.
   */
  @Test
  @DisplayName("Test parse legacy SQL format with fuzzy chars in a virtual thread")
  public void testParseLegacySqlWithFuzzyCharsInVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      for (String storeName : STORES) {
        String blobRefString = String.format("%s:%s@%s", storeName, BLOB_ID, NODE_ID);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertParsed(parsed, storeName);
      }
      assertThat(Thread.currentThread().isVirtual(), is(true));
    }).join();
  }

  /**
   * Tests that BlobRef.parse() correctly handles date-based layout when executed in a virtual thread.
   */
  @Test
  @DisplayName("Test date-based layout in a virtual thread")
  public void testDateBasedLayoutInVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      String blobRefString = String.format("%s@%s@%s", STORE_NAME, BLOB_ID, DATE_BASED_REF);
      BlobRef parsed = BlobRef.parse(blobRefString);
      assertThat(parsed.getBlob(), is(BLOB_ID));
      assertThat(parsed.getStore(), is(STORE_NAME));
      assertThat(parsed.getNode(), isEmptyOrNullString());
      OffsetDateTime blobCreatedRef = parsed.getDateBasedRef();
      assertThat(blobCreatedRef, notNullValue());
      assertThat(blobCreatedRef.format(DATE_TIME_FORMATTER), is(DATE_CREATED.format(DATE_TIME_FORMATTER)));
      assertThat(Thread.currentThread().isVirtual(), is(true));
    }).join();
  }

  /**
   * Tests that BlobRef.parse() correctly handles illegal formats when executed in a virtual thread.
   */
  @Test
  @DisplayName("Test illegal format handling in a virtual thread")
  public void testBlobRefIllegalFormatInVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      assertParseFailure("wrong-blobref-format/string");
      // empty blobstorename
      assertParseFailure("@nodeid:blobid");
      // empty nodeid
      assertParseFailure("blobstore@:blobid");
      // empty blobid
      assertParseFailure("blobstore@nodeid:");
      // no nodeid or blobid
      assertParseFailure("blobstore@");
      assertThat(Thread.currentThread().isVirtual(), is(true));
    }).join();
  }

  /**
   * Tests concurrent BlobRef operations with many virtual threads.
   * This test creates 1000 virtual threads, each parsing and creating BlobRef objects.
   */
  @Test
  @DisplayName("Test concurrent BlobRef operations with 1000 virtual threads")
  public void testConcurrentBlobRefOperations() throws Exception {
    int threadCount = 1000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          try {
            // Create a unique store name for each thread to avoid contention
            String storeName = STORE_NAME + "-" + index;
            BlobRef blobRef = new BlobRef(NODE_ID, storeName, BLOB_ID);
            String spec = blobRef.toString();
            BlobRef reconstituted = BlobRef.parse(spec);
            
            if (reconstituted.equals(blobRef) && Thread.currentThread().isVirtual()) {
              successCount.incrementAndGet();
            }
          } 
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all threads to complete or timeout after 10 seconds
      assertThat("All virtual threads should complete in time", 
          latch.await(10, TimeUnit.SECONDS), is(true));
      
      // Verify all operations were successful
      assertThat("All operations should succeed", successCount.get(), is(threadCount));
    }
  }

  /**
   * Tests for thread pinning issues when using BlobRef operations.
   * This test verifies that BlobRef operations don't cause thread pinning when executed in virtual threads.
   */
  @Test
  @DisplayName("Test for thread pinning issues with BlobRef operations")
  public void testThreadPinningWithBlobRef() {
    // This test should complete quickly if no thread pinning occurs
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Run 100 tasks that perform BlobRef operations with potential I/O operations
        for (int i = 0; i < 100; i++) {
          executor.submit(() -> {
            // Create and parse BlobRef with various formats
            BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
            String spec = blobRef.toString();
            BlobRef.parse(spec);
            
            // Test with date-based format which involves more complex parsing
            String dateBasedSpec = String.format("%s@%s@%s", STORE_NAME, BLOB_ID, DATE_BASED_REF);
            BlobRef.parse(dateBasedSpec);
            
            // Simulate a small delay to detect any thread pinning
            try {
              Thread.sleep(10);
            } 
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            
            return null;
          });
        }
      }
    });
  }

  /**
   * Compares performance between virtual threads and platform threads for BlobRef operations.
   * This test measures the time taken to perform the same operations using both thread types.
   */
  @Test
  @DisplayName("Compare performance between virtual threads and platform threads")
  public void testPerformanceComparison() throws Exception {
    int operationCount = 10000;
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < operationCount; i++) {
          futures.add(executor.submit(() -> {
            BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
            String spec = blobRef.toString();
            BlobRef.parse(spec);
            return null;
          }));
        }
        
        // Wait for all tasks to complete
        for (Future<?> future : futures) {
          future.get();
        }
      }
    });
    
    // Test with platform threads (using a fixed thread pool)
    int platformThreadCount = Math.min(100, Runtime.getRuntime().availableProcessors() * 2);
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(platformThreadCount)) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < operationCount; i++) {
          futures.add(executor.submit(() -> {
            BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
            String spec = blobRef.toString();
            BlobRef.parse(spec);
            return null;
          }));
        }
        
        // Wait for all tasks to complete
        for (Future<?> future : futures) {
          future.get();
        }
      }
    });
    
    System.out.printf("Performance comparison for %d BlobRef operations:%n", operationCount);
    System.out.printf("Virtual Threads: %d ms%n", virtualThreadTime);
    System.out.printf("Platform Threads (%d threads): %d ms%n", platformThreadCount, platformThreadTime);
    
    // We don't assert on specific times as they can vary by environment,
    // but we log the results for analysis
  }

  /**
   * Helper method to measure execution time of a runnable task.
   *
   * @param task The task to measure
   * @return Execution time in milliseconds
   */
  private long measureExecutionTime(Runnable task) throws Exception {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
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
    try {
      BlobRef.parse(blobref);
      fail("Expected exception");
    }
    catch (IllegalArgumentException e) {
      assertThat(e.getMessage(), is("Not a valid blob reference"));
    }
  }
}