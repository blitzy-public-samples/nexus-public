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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.sonatype.nexus.blobstore.api.BlobRef;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.sonatype.nexus.blobstore.api.BlobRef.DATE_TIME_FORMATTER;

/**
 * Tests that validate BlobRef operations behave correctly when executed from Virtual Threads.
 * <p>
 * This test ensures that the BlobRef class, which provides canonical, legacy OrientDB, legacy SQL,
 * and date-based reference formats, can be safely used in Java 21 virtual threading contexts
 * without thread interference or synchronization issues.
 *
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21)
public class BlobRefVirtualThreadTest
{
  private static final String STORE_NAME = "test-store";

  private static final String NODE_ID = "ab761d55-5d9c22b6-3f38315a-75b3db34-0922a4d5";

  private static final String BLOB_ID = "a8f3f56f-e895-4b6e-984a-1cf1f5107d36";

  private static final String[] STORES = {"store-@:@:@name", "@", ":", "abc/+xy&%$#", "store-:@:@:@name-for-testing"};

  private static final OffsetDateTime DATE_CREATED = OffsetDateTime.of(2024, 1, 1, 10, 30, 45, 0, ZoneOffset.UTC);

  private static final String DATE_BASED_REF = DATE_CREATED.format(DATE_TIME_FORMATTER);

  private static final int CONCURRENT_OPERATIONS = 1000;

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /**
   * Tests basic BlobRef operations in a Virtual Thread context.
   * <p>
   * This test verifies that BlobRef creation, toString, and parsing work correctly
   * when executed in a Virtual Thread.
   */
  @Test
  public void testBasicBlobRefOperationsInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicBoolean success = new AtomicBoolean(false);

      executor.submit(() -> {
        try {
          // Verify we're running in a virtual thread
          assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");

          // Create a BlobRef
          BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
          
          // Convert to string and parse back
          String spec = blobRef.toString();
          BlobRef reconstituted = BlobRef.parse(spec);

          // Verify equality
          assertEquals(blobRef, reconstituted, "BlobRef should be equal after round-trip conversion");
          assertEquals(STORE_NAME, reconstituted.getStore(), "Store name should be preserved");
          assertEquals(BLOB_ID, reconstituted.getBlob(), "Blob ID should be preserved");
          
          success.set(true);
        }
        catch (Exception e) {
          e.printStackTrace();
        }
        finally {
          latch.countDown();
        }
      });

      assertTrue(latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Operation timed out");
      assertTrue(success.get(), "BlobRef operations should succeed in virtual thread");
    }
  }

  /**
   * Tests parsing of different BlobRef formats in a Virtual Thread context.
   * <p>
   * This test verifies that all supported BlobRef formats (canonical, legacy OrientDB,
   * legacy SQL, and date-based) can be correctly parsed when executed in a Virtual Thread.
   */
  @Test
  public void testParseFormatsInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(4); // One for each format
      AtomicInteger successCount = new AtomicInteger(0);

      // Test canonical format
      executor.submit(() -> {
        try {
          String blobRefString = String.format("%s@%s", STORE_NAME, BLOB_ID);
          BlobRef parsed = BlobRef.parse(blobRefString);
          assertParsedInVirtualThread(parsed, STORE_NAME);
          successCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });

      // Test legacy OrientDB format
      executor.submit(() -> {
        try {
          String blobRefString = String.format("%s@%s:%s", STORE_NAME, NODE_ID, BLOB_ID);
          BlobRef parsed = BlobRef.parse(blobRefString);
          assertParsedInVirtualThread(parsed, STORE_NAME);
          successCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });

      // Test legacy SQL format
      executor.submit(() -> {
        try {
          String blobRefString = String.format("%s:%s@%s", STORE_NAME, BLOB_ID, NODE_ID);
          BlobRef parsed = BlobRef.parse(blobRefString);
          assertParsedInVirtualThread(parsed, STORE_NAME);
          successCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });

      // Test date-based format
      executor.submit(() -> {
        try {
          String blobRefString = String.format("%s@%s@%s", STORE_NAME, BLOB_ID, DATE_BASED_REF);
          BlobRef parsed = BlobRef.parse(blobRefString);
          assertEquals(BLOB_ID, parsed.getBlob(), "Blob ID should match");
          assertEquals(STORE_NAME, parsed.getStore(), "Store name should match");
          assertTrue(parsed.getNode() == null || parsed.getNode().isEmpty(), "Node should be empty");
          assertNotNull(parsed.getDateBasedRef(), "Date-based reference should not be null");
          assertEquals(DATE_BASED_REF, parsed.getDateBasedRef().format(DATE_TIME_FORMATTER), 
              "Date-based reference should match");
          successCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });

      assertTrue(latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Operations timed out");
      assertEquals(4, successCount.get(), "All format parsing operations should succeed");
    }
  }

  /**
   * Tests parsing of BlobRefs with special characters in store names in a Virtual Thread context.
   * <p>
   * This test verifies that BlobRefs with store names containing special characters can be
   * correctly parsed when executed in a Virtual Thread.
   */
  @Test
  public void testParseWithSpecialCharsInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(STORES.length * 3); // For each store name and format
      AtomicInteger successCount = new AtomicInteger(0);

      for (String storeName : STORES) {
        // Test canonical format
        executor.submit(() -> {
          try {
            String blobRefString = String.format("%s@%s", storeName, BLOB_ID);
            BlobRef parsed = BlobRef.parse(blobRefString);
            assertParsedInVirtualThread(parsed, storeName);
            successCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });

        // Test legacy OrientDB format
        executor.submit(() -> {
          try {
            String blobRefString = String.format("%s@%s:%s", storeName, NODE_ID, BLOB_ID);
            BlobRef parsed = BlobRef.parse(blobRefString);
            assertParsedInVirtualThread(parsed, storeName);
            successCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });

        // Test legacy SQL format
        executor.submit(() -> {
          try {
            String blobRefString = String.format("%s:%s@%s", storeName, BLOB_ID, NODE_ID);
            BlobRef parsed = BlobRef.parse(blobRefString);
            assertParsedInVirtualThread(parsed, storeName);
            successCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }

      assertTrue(latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Operations timed out");
      assertEquals(STORES.length * 3, successCount.get(), "All special character parsing operations should succeed");
    }
  }

  /**
   * Tests handling of invalid BlobRef formats in a Virtual Thread context.
   * <p>
   * This test verifies that invalid BlobRef formats are correctly rejected with appropriate
   * exceptions when parsed in a Virtual Thread.
   */
  @Test
  public void testInvalidFormatsInVirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(5); // One for each invalid format
      AtomicInteger successCount = new AtomicInteger(0);

      String[] invalidFormats = {
          "wrong-blobref-format/string",
          "@nodeid:blobid", // empty blobstore name
          "blobstore@:blobid", // empty nodeid
          "blobstore@nodeid:", // empty blobid
          "blobstore@" // no nodeid or blobid
      };

      for (String invalidFormat : invalidFormats) {
        final String format = invalidFormat;
        executor.submit(() -> {
          try {
            try {
              BlobRef.parse(format);
              fail("Should throw IllegalArgumentException for invalid format: " + format);
            }
            catch (IllegalArgumentException e) {
              assertEquals("Not a valid blob reference", e.getMessage(), 
                  "Exception message should match for invalid format");
              successCount.incrementAndGet();
            }
          }
          finally {
            latch.countDown();
          }
        });
      }

      assertTrue(latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Operations timed out");
      assertEquals(invalidFormats.length, successCount.get(), "All invalid format tests should succeed");
    }
  }

  /**
   * Tests concurrent BlobRef operations under high load with Virtual Threads.
   * <p>
   * This test creates a large number of Virtual Threads that concurrently create, format,
   * and parse BlobRefs to verify thread safety and correctness under high concurrency.
   */
  @Test
  public void testConcurrentBlobRefOperations() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger successCount = new AtomicInteger(0);
      List<String> blobRefStrings = new ArrayList<>(CONCURRENT_OPERATIONS);

      // First phase: create BlobRefs and convert to strings concurrently
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique BlobRef
            String uniqueBlobId = UUID.randomUUID().toString();
            BlobRef blobRef = new BlobRef(NODE_ID, STORE_NAME + "-" + index, uniqueBlobId);
            
            // Convert to string
            String blobRefString = blobRef.toString();
            synchronized (blobRefStrings) {
              blobRefStrings.add(blobRefString);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }

      assertTrue(latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "BlobRef creation timed out");
      assertEquals(CONCURRENT_OPERATIONS, blobRefStrings.size(), "All BlobRefs should be created");

      // Second phase: parse the BlobRef strings concurrently
      CountDownLatch parseLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String blobRefString;
            synchronized (blobRefStrings) {
              blobRefString = blobRefStrings.get(index);
            }
            
            // Parse the BlobRef string
            BlobRef parsedRef = BlobRef.parse(blobRefString);
            
            // Verify the parsed BlobRef
            assertNotNull(parsedRef, "Parsed BlobRef should not be null");
            assertEquals(STORE_NAME + "-" + index, parsedRef.getStore(), "Store name should match");
            assertNotNull(parsedRef.getBlob(), "Blob ID should not be null");
            
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            e.printStackTrace();
          }
          finally {
            parseLatch.countDown();
          }
        });
      }

      assertTrue(parseLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "BlobRef parsing timed out");
      assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "All BlobRefs should be parsed successfully");
    }
  }

  /**
   * Tests round-trip serialization and deserialization of BlobRefs with different formats.
   * <p>
   * This test verifies that BlobRefs can be correctly serialized to strings and deserialized
   * back to BlobRef objects when executed in Virtual Threads, maintaining all properties.
   */
  @Test
  public void testRoundTripSerializationInVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(3); // One for each format type
      AtomicInteger successCount = new AtomicInteger(0);

      // Test canonical format round-trip
      executor.submit(() -> {
        try {
          BlobRef original = new BlobRef("", STORE_NAME, BLOB_ID); // Canonical format (no node)
          String serialized = original.toString();
          BlobRef deserialized = BlobRef.parse(serialized);
          
          assertEquals(original, deserialized, "BlobRef should be equal after round-trip");
          assertEquals(STORE_NAME, deserialized.getStore(), "Store name should be preserved");
          assertEquals(BLOB_ID, deserialized.getBlob(), "Blob ID should be preserved");
          assertTrue(deserialized.getNode() == null || deserialized.getNode().isEmpty(), 
              "Node should be empty for canonical format");
          assertNull(deserialized.getDateBasedRef(), "Date-based reference should be null");
          
          successCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });

      // Test legacy format round-trip
      executor.submit(() -> {
        try {
          BlobRef original = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID); // Legacy format (with node)
          String serialized = original.toString();
          BlobRef deserialized = BlobRef.parse(serialized);
          
          assertEquals(original, deserialized, "BlobRef should be equal after round-trip");
          assertEquals(STORE_NAME, deserialized.getStore(), "Store name should be preserved");
          assertEquals(BLOB_ID, deserialized.getBlob(), "Blob ID should be preserved");
          assertTrue(deserialized.getNode() == null || deserialized.getNode().isEmpty(), 
              "Node should be empty after serialization");
          assertNull(deserialized.getDateBasedRef(), "Date-based reference should be null");
          
          successCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });

      // Test date-based format round-trip
      executor.submit(() -> {
        try {
          // Create a date-based BlobRef by parsing a date-based format string
          String dateBasedString = String.format("%s@%s@%s", STORE_NAME, BLOB_ID, DATE_BASED_REF);
          BlobRef original = BlobRef.parse(dateBasedString);
          
          String serialized = original.toString();
          BlobRef deserialized = BlobRef.parse(serialized);
          
          assertEquals(original, deserialized, "BlobRef should be equal after round-trip");
          assertEquals(STORE_NAME, deserialized.getStore(), "Store name should be preserved");
          assertEquals(BLOB_ID, deserialized.getBlob(), "Blob ID should be preserved");
          assertTrue(deserialized.getNode() == null || deserialized.getNode().isEmpty(), 
              "Node should be empty");
          assertNotNull(deserialized.getDateBasedRef(), "Date-based reference should not be null");
          assertEquals(DATE_BASED_REF, deserialized.getDateBasedRef().format(DATE_TIME_FORMATTER), 
              "Date-based reference should be preserved");
          
          successCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });

      assertTrue(latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Operations timed out");
      assertEquals(3, successCount.get(), "All round-trip serialization tests should succeed");
    }
  }

  /**
   * Helper method to assert that a parsed BlobRef has the expected properties.
   * <p>
   * This method verifies that a BlobRef parsed in a Virtual Thread has the correct
   * store name, blob ID, and other properties.
   *
   * @param parsed the parsed BlobRef to check
   * @param expectedStoreName the expected store name
   */
  private void assertParsedInVirtualThread(final BlobRef parsed, final String expectedStoreName) {
    assertTrue(Thread.currentThread().isVirtual(), "Should be running in a virtual thread");
    assertEquals(BLOB_ID, parsed.getBlob(), "Blob ID should match");
    assertEquals(expectedStoreName, parsed.getStore(), "Store name should match");
    assertTrue(parsed.getNode() == null || parsed.getNode().isEmpty(), "Node should be empty");
    assertNull(parsed.getDateBasedRef(), "Date-based reference should be null");
  }
}