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

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.sonatype.nexus.blobstore.api.BlobRef.DATE_TIME_FORMATTER;

/**
 * Tests for {@link BlobRef} when executed from Virtual Threads.
 * Validates that BlobRef parsing, formatting, and operations behave correctly
 * when executed from Java 21 virtual threading contexts.
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

  private static final int VIRTUAL_THREAD_COUNT = 1000;

  private static final int OPERATIONS_PER_THREAD = 100;

  /**
   * Tests that BlobRef toString() and parse() round-trip works correctly when executed from a Virtual Thread.
   */
  @Test
  public void testToStringInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        final BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
        final String spec = blobRef.toString();
        final BlobRef reconstituted = BlobRef.parse(spec);

        assertThat(reconstituted, is(equalTo(blobRef)));
        assertThat(Thread.currentThread().isVirtual(), is(true));
      });
      future.get(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that BlobRef parsing of canonical format works correctly when executed from a Virtual Thread.
   */
  @Test
  public void testParseCanonicalInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        String blobRefString = String.format("%s@%s", STORE_NAME, BLOB_ID);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertParsed(parsed, STORE_NAME);
        assertThat(Thread.currentThread().isVirtual(), is(true));
      });
      future.get(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that BlobRef parsing of legacy OrientDB format works correctly when executed from a Virtual Thread.
   */
  @Test
  public void testParseLegacyOrientInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        String blobRefString = String.format("%s@%s:%s", STORE_NAME, NODE_ID, BLOB_ID);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertParsed(parsed, STORE_NAME);
        assertThat(Thread.currentThread().isVirtual(), is(true));
      });
      future.get(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that BlobRef parsing of legacy SQL format works correctly when executed from a Virtual Thread.
   */
  @Test
  public void testParseLegacySqlInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        String blobRefString = String.format("%s:%s@%s", STORE_NAME, BLOB_ID, NODE_ID);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertParsed(parsed, STORE_NAME);
        assertThat(Thread.currentThread().isVirtual(), is(true));
      });
      future.get(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that BlobRef parsing with special characters in store names works correctly when executed from a Virtual Thread.
   */
  @Test
  public void testParseWithFuzzyCharsInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        for (String storeName : STORES) {
          // Test canonical format
          String blobRefString = String.format("%s@%s", storeName, BLOB_ID);
          BlobRef parsed = BlobRef.parse(blobRefString);
          assertParsed(parsed, storeName);

          // Test legacy OrientDB format
          blobRefString = String.format("%s@%s:%s", storeName, NODE_ID, BLOB_ID);
          parsed = BlobRef.parse(blobRefString);
          assertParsed(parsed, storeName);

          // Test legacy SQL format
          blobRefString = String.format("%s:%s@%s", storeName, BLOB_ID, NODE_ID);
          parsed = BlobRef.parse(blobRefString);
          assertParsed(parsed, storeName);
        }
        assertThat(Thread.currentThread().isVirtual(), is(true));
      });
      future.get(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that BlobRef date-based layout parsing works correctly when executed from a Virtual Thread.
   */
  @Test
  public void testDateBasedLayoutInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        String blobRefString = String.format("%s@%s@%s", STORE_NAME, BLOB_ID, DATE_BASED_REF);
        BlobRef parsed = BlobRef.parse(blobRefString);
        assertThat(parsed.getBlob(), is(BLOB_ID));
        assertThat(parsed.getStore(), is(STORE_NAME));
        assertThat(parsed.getNode(), isEmptyOrNullString());
        OffsetDateTime blobCreatedRef = parsed.getDateBasedRef();
        assertThat(blobCreatedRef, notNullValue());
        assertThat(blobCreatedRef.format(DATE_TIME_FORMATTER), is(DATE_CREATED.format(DATE_TIME_FORMATTER)));
        assertThat(Thread.currentThread().isVirtual(), is(true));
      });
      future.get(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests that BlobRef error handling for invalid formats works correctly when executed from a Virtual Thread.
   */
  @Test
  public void testBlobRefIllegalFormatInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
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
      });
      future.get(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests concurrent BlobRef operations using multiple Virtual Threads.
   * This test validates that BlobRef operations are thread-safe when executed
   * from many Virtual Threads concurrently.
   */
  @Test
  public void testConcurrentBlobRefOperationsInVirtualThreads() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to many virtual threads
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadIndex = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Verify we're running in a virtual thread
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Perform multiple BlobRef operations per thread
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              // Create a unique store name for this thread and operation
              String storeName = STORE_NAME + "-" + threadIndex + "-" + j;
              
              // Test round-trip parsing and formatting
              BlobRef blobRef = new BlobRef(NODE_ID, storeName, BLOB_ID);
              String spec = blobRef.toString();
              BlobRef reconstituted = BlobRef.parse(spec);
              assertThat(reconstituted, is(equalTo(blobRef)));
              
              // Test different formats
              String canonicalFormat = String.format("%s@%s", storeName, BLOB_ID);
              BlobRef parsed = BlobRef.parse(canonicalFormat);
              assertParsed(parsed, storeName);
              
              String legacyOrientFormat = String.format("%s@%s:%s", storeName, NODE_ID, BLOB_ID);
              parsed = BlobRef.parse(legacyOrientFormat);
              assertParsed(parsed, storeName);
              
              String legacySqlFormat = String.format("%s:%s@%s", storeName, BLOB_ID, NODE_ID);
              parsed = BlobRef.parse(legacySqlFormat);
              assertParsed(parsed, storeName);
            }
            
            // Increment success counter
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            e.printStackTrace();
          }
          finally {
            // Signal completion
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual threads completed in time", completed, is(true));
      assertThat("All operations succeeded", successCount.get(), is(VIRTUAL_THREAD_COUNT));
    }
  }

  /**
   * Tests BlobRef date-based operations with concurrent Virtual Threads.
   */
  @Test
  public void testConcurrentDateBasedOperationsInVirtualThreads() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to many virtual threads
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadIndex = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Verify we're running in a virtual thread
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Perform multiple date-based BlobRef operations per thread
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              // Create a unique store name for this thread and operation
              String storeName = STORE_NAME + "-" + threadIndex + "-" + j;
              
              // Test date-based format
              String dateBasedFormat = String.format("%s@%s@%s", storeName, BLOB_ID, DATE_BASED_REF);
              BlobRef parsed = BlobRef.parse(dateBasedFormat);
              assertThat(parsed.getBlob(), is(BLOB_ID));
              assertThat(parsed.getStore(), is(storeName));
              assertThat(parsed.getNode(), isEmptyOrNullString());
              OffsetDateTime blobCreatedRef = parsed.getDateBasedRef();
              assertThat(blobCreatedRef, notNullValue());
              assertThat(blobCreatedRef.format(DATE_TIME_FORMATTER), is(DATE_CREATED.format(DATE_TIME_FORMATTER)));
            }
            
            // Increment success counter
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            e.printStackTrace();
          }
          finally {
            // Signal completion
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual threads completed in time", completed, is(true));
      assertThat("All operations succeeded", successCount.get(), is(VIRTUAL_THREAD_COUNT));
    }
  }

  private void assertParsed(final BlobRef parsed, final String storeName) {
    assertThat(parsed.getBlob(), is(equalTo(BLOB_ID)));
    assertThat(parsed.getStore(), is(equalTo(storeName)));
    assertThat(parsed.getNode(), isEmptyOrNullString());
    assertThat(parsed.getDateBasedRef(), nullValue());
  }

  private static void assertParseFailure(final String blobref) {
    try {
      BlobRef.parse(blobref);
      fail("Expected exception");
    }
    catch (IllegalArgumentException e) {
      assertThat(e.getMessage(), is("Not a valid blob reference"));
    }
  }
}