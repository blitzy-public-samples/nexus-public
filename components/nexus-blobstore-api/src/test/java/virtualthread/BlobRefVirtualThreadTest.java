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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.nexus.blobstore.api.BlobRef;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.blobstore.api.BlobRef.DATE_TIME_FORMATTER;

/**
 * Tests for {@link BlobRef} in a virtual thread environment.
 * 
 * This test validates that BlobRef operations are thread-safe and perform correctly
 * when running with Java 21 virtual threads.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class BlobRefVirtualThreadTest
{
  private static final String STORE_NAME = "test-store";

  private static final String NODE_ID = "ab761d55-5d9c22b6-3f38315a-75b3db34-0922a4d5";

  private static final String BLOB_ID = "a8f3f56f-e895-4b6e-984a-1cf1f5107d36";

  private static final String[] STORES = {"store-@:@:@name", "@", ":", "abc/+xy&%$#", "store-:@:@:@name-for-testing"};

  private static final OffsetDateTime DATE_CREATED = OffsetDateTime.of(2024, 1, 1, 10, 30, 45, 0, ZoneOffset.UTC);

  private static final String DATE_BASED_REF = DATE_CREATED.format(DATE_TIME_FORMATTER);

  private static final int CONCURRENT_THREADS = 1000;

  private static final int STRESS_TEST_ITERATIONS = 10000;

  /**
   * Tests that BlobRef.parse() and BlobRef.toString() operate correctly under high concurrency
   * with virtual threads.
   */
  @Test
  public void testConcurrentParsingAndFormatting() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      List<Future<?>> futures = new ArrayList<>();
      AtomicBoolean failed = new AtomicBoolean(false);
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Create canonical format BlobRef
      BlobRef canonicalRef = new BlobRef(STORE_NAME, BLOB_ID);
      String canonicalString = canonicalRef.toString();
      
      // Create legacy OrientDB format BlobRef
      BlobRef legacyOrientRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
      String legacyOrientString = String.format("%s@%s:%s", STORE_NAME, NODE_ID, BLOB_ID);
      
      // Create legacy SQL format BlobRef
      String legacySqlString = String.format("%s:%s@%s", STORE_NAME, BLOB_ID, NODE_ID);
      
      // Create date-based format BlobRef
      String dateBasedString = String.format("%s@%s@%s", STORE_NAME, BLOB_ID, DATE_BASED_REF);
      
      // Submit concurrent parsing tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int index = i % 4; // Cycle through the 4 formats
        
        futures.add(executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            BlobRef parsed;
            switch (index) {
              case 0: // Canonical format
                parsed = BlobRef.parse(canonicalString);
                assertEquals(STORE_NAME, parsed.getStore());
                assertEquals(BLOB_ID, parsed.getBlob());
                break;
              case 1: // Legacy OrientDB format
                parsed = BlobRef.parse(legacyOrientString);
                assertEquals(STORE_NAME, parsed.getStore());
                assertEquals(BLOB_ID, parsed.getBlob());
                break;
              case 2: // Legacy SQL format
                parsed = BlobRef.parse(legacySqlString);
                assertEquals(STORE_NAME, parsed.getStore());
                assertEquals(BLOB_ID, parsed.getBlob());
                break;
              case 3: // Date-based format
                parsed = BlobRef.parse(dateBasedString);
                assertEquals(STORE_NAME, parsed.getStore());
                assertEquals(BLOB_ID, parsed.getBlob());
                assertNotNull(parsed.getDateBasedRef());
                assertEquals(DATE_BASED_REF, parsed.getDateBasedRef().format(DATE_TIME_FORMATTER));
                break;
            }
          }
          catch (Exception e) {
            failed.set(true);
            throw new RuntimeException("Failed in thread " + Thread.currentThread().getName(), e);
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
      
      assertFalse(failed.get(), "Some threads failed during concurrent parsing");
    }
    finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
    }
  }

  /**
   * Tests that all BlobRef formats (canonical, legacy OrientDB, legacy SQL, and date-based)
   * are thread-safe when used with virtual threads.
   */
  @Test
  public void testAllFormatsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      List<Future<?>> futures = new ArrayList<>();
      ConcurrentHashMap<String, BlobRef> results = new ConcurrentHashMap<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Test with all store names to ensure format handling is thread-safe
      for (String storeName : STORES) {
        // Submit tasks for each format
        for (int i = 0; i < 250; i++) { // 250 threads per format = 1000 total
          final String store = storeName;
          final int formatIndex = i % 4;
          
          futures.add(executor.submit(() -> {
            try {
              startLatch.await();
              
              String blobRefString;
              switch (formatIndex) {
                case 0: // Canonical format
                  blobRefString = String.format("%s@%s", store, BLOB_ID);
                  break;
                case 1: // Legacy OrientDB format
                  blobRefString = String.format("%s@%s:%s", store, NODE_ID, BLOB_ID);
                  break;
                case 2: // Legacy SQL format
                  blobRefString = String.format("%s:%s@%s", store, BLOB_ID, NODE_ID);
                  break;
                case 3: // Date-based format
                  blobRefString = String.format("%s@%s@%s", store, BLOB_ID, DATE_BASED_REF);
                  break;
                default:
                  throw new IllegalStateException("Unexpected format index");
              }
              
              BlobRef parsed = BlobRef.parse(blobRefString);
              results.put(Thread.currentThread().getName(), parsed);
              
              // Verify the parsed BlobRef
              assertEquals(store, parsed.getStore());
              assertEquals(BLOB_ID, parsed.getBlob());
              
              if (formatIndex == 3) { // Date-based format
                assertNotNull(parsed.getDateBasedRef());
                assertEquals(DATE_BASED_REF, parsed.getDateBasedRef().format(DATE_TIME_FORMATTER));
              }
            }
            catch (Exception e) {
              throw new RuntimeException("Failed in thread " + Thread.currentThread().getName(), e);
            }
          }));
        }
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
      
      // Verify we have results from all threads
      assertEquals(STORES.length * 250, results.size());
    }
    finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
    }
  }

  /**
   * Tests that BlobRef operations don't cause thread pinning when used with virtual threads.
   * Thread pinning occurs when a virtual thread blocks the carrier thread, which defeats
   * the purpose of virtual threads.
   */
  @Test
  public void testNoPinningWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a small number of carrier threads to make pinning more detectable
      System.setProperty("jdk.virtualThreadScheduler.parallelism", "4");
      
      List<Future<?>> futures = new ArrayList<>();
      AtomicInteger completedTasks = new AtomicInteger(0);
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Submit a large number of tasks that would cause issues if pinning occurred
      for (int i = 0; i < CONCURRENT_THREADS * 2; i++) {
        futures.add(executor.submit(() -> {
          try {
            startLatch.await();
            
            // Create and parse BlobRefs in a tight loop
            for (int j = 0; j < 100; j++) {
              BlobRef ref = new BlobRef(STORE_NAME, BLOB_ID + j);
              String refString = ref.toString();
              BlobRef parsed = BlobRef.parse(refString);
              assertEquals(ref.getStore(), parsed.getStore());
              assertEquals(ref.getBlob(), parsed.getBlob());
            }
            
            completedTasks.incrementAndGet();
          }
          catch (Exception e) {
            throw new RuntimeException("Failed in thread " + Thread.currentThread().getName(), e);
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete with a timeout
      // If pinning occurs, this will likely time out
      long startTime = System.currentTimeMillis();
      for (Future<?> future : futures) {
        future.get(5, TimeUnit.SECONDS);
      }
      long endTime = System.currentTimeMillis();
      
      // Verify all tasks completed
      assertEquals(CONCURRENT_THREADS * 2, completedTasks.get());
      
      // If execution took too long, it might indicate pinning
      long executionTime = endTime - startTime;
      System.out.println("Execution time for pinning test: " + executionTime + "ms");
      assertTrue(executionTime < 5000, "Execution took too long, possible thread pinning detected");
    }
    finally {
      // Reset the parallelism property
      System.clearProperty("jdk.virtualThreadScheduler.parallelism");
      executor.shutdown();
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
    }
  }

  /**
   * Stress test with high concurrency to verify BlobRef operations scale well with virtual threads.
   */
  @Test
  public void stressTestWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      List<Future<?>> futures = new ArrayList<>();
      AtomicInteger successCount = new AtomicInteger(0);
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Create a large number of virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            startLatch.await();
            
            // Each thread performs multiple operations
            for (int j = 0; j < STRESS_TEST_ITERATIONS / CONCURRENT_THREADS; j++) {
              // Alternate between different formats
              int formatIndex = j % 4;
              String blobRefString;
              
              switch (formatIndex) {
                case 0: // Canonical format
                  blobRefString = String.format("%s@%s", STORE_NAME, BLOB_ID);
                  break;
                case 1: // Legacy OrientDB format
                  blobRefString = String.format("%s@%s:%s", STORE_NAME, NODE_ID, BLOB_ID);
                  break;
                case 2: // Legacy SQL format
                  blobRefString = String.format("%s:%s@%s", STORE_NAME, BLOB_ID, NODE_ID);
                  break;
                case 3: // Date-based format
                  blobRefString = String.format("%s@%s@%s", STORE_NAME, BLOB_ID, DATE_BASED_REF);
                  break;
                default:
                  throw new IllegalStateException("Unexpected format index");
              }
              
              // Parse and verify
              BlobRef parsed = BlobRef.parse(blobRefString);
              if (STORE_NAME.equals(parsed.getStore()) && BLOB_ID.equals(parsed.getBlob())) {
                successCount.incrementAndGet();
              }
            }
          }
          catch (Exception e) {
            throw new RuntimeException("Failed in thread " + Thread.currentThread().getName(), e);
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get(30, TimeUnit.SECONDS);
        }
        catch (ExecutionException e) {
          e.printStackTrace();
          throw e;
        }
      }
      
      // Verify all operations succeeded
      assertEquals(STRESS_TEST_ITERATIONS, successCount.get());
    }
    finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));
    }
  }

  /**
   * Tests that BlobRef.toString() and BlobRef.parse() are consistent when used with virtual threads.
   */
  @Test
  public void testToStringAndParseConsistency() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      List<Future<?>> futures = new ArrayList<>();
      ConcurrentHashMap<String, Boolean> results = new ConcurrentHashMap<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Create BlobRefs to test
      final BlobRef canonicalRef = new BlobRef(STORE_NAME, BLOB_ID);
      final BlobRef legacyOrientRef = new BlobRef(NODE_ID, STORE_NAME, BLOB_ID);
      final BlobRef dateBasedRef = new BlobRef(STORE_NAME, BLOB_ID, DATE_CREATED);
      
      // Submit concurrent tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int index = i % 3; // Cycle through the 3 BlobRef instances
        
        futures.add(executor.submit(() -> {
          try {
            startLatch.await();
            
            BlobRef ref;
            switch (index) {
              case 0:
                ref = canonicalRef;
                break;
              case 1:
                ref = legacyOrientRef;
                break;
              case 2:
                ref = dateBasedRef;
                break;
              default:
                throw new IllegalStateException("Unexpected index");
            }
            
            // Convert to string and back to BlobRef
            String refString = ref.toString();
            BlobRef parsed = BlobRef.parse(refString);
            
            // Verify consistency
            boolean consistent = ref.getStore().equals(parsed.getStore()) && 
                                ref.getBlob().equals(parsed.getBlob());
            
            // For date-based refs, also check the date
            if (index == 2) {
              consistent = consistent && parsed.getDateBasedRef() != null &&
                          ref.getDateBasedRef().format(DATE_TIME_FORMATTER).equals(
                              parsed.getDateBasedRef().format(DATE_TIME_FORMATTER));
            }
            
            results.put(Thread.currentThread().getName(), consistent);
          }
          catch (Exception e) {
            throw new RuntimeException("Failed in thread " + Thread.currentThread().getName(), e);
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
      
      // Verify all operations were consistent
      assertEquals(CONCURRENT_THREADS, results.size());
      assertFalse(results.containsValue(Boolean.FALSE), "Some toString/parse operations were inconsistent");
    }
    finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
    }
  }
}