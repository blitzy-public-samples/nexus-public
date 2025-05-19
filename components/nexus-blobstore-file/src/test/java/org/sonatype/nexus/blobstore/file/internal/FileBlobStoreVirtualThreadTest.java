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
package org.sonatype.nexus.blobstore.file.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.BlobStoreReconciliationLogger;
import org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver;
import org.sonatype.nexus.blobstore.MetricsInputStream;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.file.internal.datastore.metrics.DatastoreFileBlobStoreMetricsService;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaUsageChecker;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.scheduling.internal.PeriodicJobServiceImpl;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.io.ByteStreams.nullOutputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;

/**
 * Tests {@link FileBlobStore} operations using Java 21 Virtual Threads.
 * 
 * This test validates that file I/O operations performed by the FileBlobStore implementation
 * maintain correct behavior and thread safety when executed with virtual threads.
 */
@ExtendWith(MockitoExtension.class)
class FileBlobStoreVirtualThreadTest
    extends TestSupport
{
  private static final ImmutableMap<String, String> TEST_HEADERS = ImmutableMap.of(
      CREATED_BY_HEADER, "test",
      BLOB_NAME_HEADER, "test/randomData.bin");

  private static final int BLOB_MAX_SIZE_BYTES = 5_000;
  private static final int QUOTA_CHECK_INTERVAL = 1;
  private static final int HIGH_THREAD_COUNT = 1000;
  private static final int MEDIUM_THREAD_COUNT = 100;
  private static final int LOW_THREAD_COUNT = 10;
  private static final int TIMEOUT_SECONDS = 30;

  private FileBlobStore underTest;

  @Mock
  private DatastoreFileBlobStoreMetricsService metricsStore;

  private BlobStoreQuotaUsageChecker blobStoreQuotaUsageChecker;

  @Mock
  private FileBlobDeletionIndex fileBlobDeletionIndex;

  @Mock
  NodeAccess nodeAccess;

  @Mock
  DryRunPrefix dryRunPrefix;

  @Mock
  BlobStoreQuotaService quotaService;

  @Mock
  private BlobStoreReconciliationLogger reconciliationLogger;

  @BeforeEach
  void setUp() throws Exception {
    Path root = util.createTempDir().toPath();
    Path content = root.resolve("content");

    when(nodeAccess.getId()).thenReturn(UUID.randomUUID().toString());
    when(dryRunPrefix.get()).thenReturn("");

    ApplicationDirectories applicationDirectories = mock(ApplicationDirectories.class);
    when(applicationDirectories.getWorkDirectory(anyString())).thenReturn(root.toFile());

    final BlobStoreConfiguration config = new MockBlobStoreConfiguration();
    config.attributes(FileBlobStore.CONFIG_KEY).set(FileBlobStore.PATH_KEY, root.toString());

    blobStoreQuotaUsageChecker = new BlobStoreQuotaUsageChecker(
        new PeriodicJobServiceImpl(), QUOTA_CHECK_INTERVAL, quotaService);

    this.underTest = new FileBlobStore(content, new DefaultBlobIdLocationResolver(true), new SimpleFileOperations(),
        metricsStore, config, applicationDirectories, nodeAccess, dryRunPrefix, reconciliationLogger, 0L,
        blobStoreQuotaUsageChecker, fileBlobDeletionIndex);
    underTest.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (underTest != null) {
      underTest.stop();
    }
  }

  /**
   * Tests concurrent blob creation using virtual threads.
   * 
   * This test creates a large number of blobs concurrently using virtual threads to validate
   * that the FileBlobStore implementation handles high concurrency correctly with the new
   * Java 21 threading model.
   */
  @Test
  void testConcurrentBlobCreationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int threadCount = HIGH_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<BlobId, byte[]> blobDataMap = new ConcurrentHashMap<>();
    Random random = new Random();
    
    try {
      // Create blobs concurrently using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Create random blob data
            byte[] data = new byte[random.nextInt(BLOB_MAX_SIZE_BYTES) + 1];
            random.nextBytes(data);
            
            // Create blob
            Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
            assertNotNull(blob, "Blob should not be null");
            assertNotNull(blob.getId(), "Blob ID should not be null");
            
            // Store blob data for verification
            blobDataMap.put(blob.getId(), data);
          } catch (Exception e) {
            log.error("Error creating blob", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "All threads should complete within timeout");
      assertEquals(0, errorCount.get(), "No errors should occur during blob creation");
      
      // Verify blob count
      assertEquals(threadCount, blobDataMap.size(), "All blobs should be created successfully");
      
      // Verify metrics service was initialized
      verify(metricsStore).init(underTest);
      verify(quotaService, atLeastOnce()).checkQuota(underTest);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent blob retrieval using virtual threads.
   * 
   * This test creates a set of blobs and then retrieves them concurrently using virtual threads
   * to validate that the FileBlobStore implementation handles concurrent reads correctly with
   * the new Java 21 threading model.
   */
  @Test
  void testConcurrentBlobRetrievalWithVirtualThreads() throws Exception {
    // Create a set of blobs first
    List<BlobId> blobIds = new ArrayList<>();
    ConcurrentHashMap<BlobId, byte[]> blobDataMap = new ConcurrentHashMap<>();
    Random random = new Random();
    
    for (int i = 0; i < MEDIUM_THREAD_COUNT; i++) {
      byte[] data = new byte[random.nextInt(BLOB_MAX_SIZE_BYTES) + 1];
      random.nextBytes(data);
      Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
      blobIds.add(blob.getId());
      blobDataMap.put(blob.getId(), data);
    }
    
    // Now retrieve blobs concurrently using virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int retrievalThreadCount = HIGH_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(retrievalThreadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      for (int i = 0; i < retrievalThreadCount; i++) {
        final int index = i % blobIds.size(); // Cycle through available blobs
        executor.submit(() -> {
          try {
            BlobId blobId = blobIds.get(index);
            Blob blob = underTest.get(blobId);
            assertNotNull(blob, "Retrieved blob should not be null");
            
            // Verify blob content
            try (InputStream inputStream = blob.getInputStream()) {
              readContentAndValidateMetrics(blobId, inputStream, blob.getMetrics(), blobDataMap.get(blobId));
            }
          } catch (Exception e) {
            log.error("Error retrieving blob", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "All threads should complete within timeout");
      assertEquals(0, errorCount.get(), "No errors should occur during blob retrieval");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent blob deletion using virtual threads.
   * 
   * This test creates a set of blobs and then deletes them concurrently using virtual threads
   * to validate that the FileBlobStore implementation handles concurrent deletions correctly with
   * the new Java 21 threading model.
   */
  @Test
  void testConcurrentBlobDeletionWithVirtualThreads() throws Exception {
    // Create a set of blobs first
    List<BlobId> blobIds = new ArrayList<>();
    Random random = new Random();
    
    for (int i = 0; i < MEDIUM_THREAD_COUNT; i++) {
      byte[] data = new byte[random.nextInt(BLOB_MAX_SIZE_BYTES) + 1];
      random.nextBytes(data);
      Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
      blobIds.add(blob.getId());
    }
    
    // Now delete blobs concurrently using virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(blobIds.size());
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      for (BlobId blobId : blobIds) {
        executor.submit(() -> {
          try {
            underTest.delete(blobId, "Testing virtual thread deletion");
          } catch (Exception e) {
            log.error("Error deleting blob", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "All threads should complete within timeout");
      assertEquals(0, errorCount.get(), "No errors should occur during blob deletion");
      
      // Verify blobs are deleted
      for (BlobId blobId : blobIds) {
        Blob blob = underTest.get(blobId);
        assertTrue(blob == null || blob.getMetrics().isDeleted(), 
            "Blob should be deleted or marked as deleted");
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests mixed blob operations (create, get, delete) using virtual threads.
   * 
   * This test performs a mix of blob operations concurrently using virtual threads to validate
   * that the FileBlobStore implementation handles mixed workloads correctly with the new
   * Java 21 threading model.
   */
  @Test
  void testMixedBlobOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int creatorThreads = LOW_THREAD_COUNT;
    int readerThreads = MEDIUM_THREAD_COUNT;
    int deleterThreads = LOW_THREAD_COUNT;
    int totalThreads = creatorThreads + readerThreads + deleterThreads;
    
    CountDownLatch latch = new CountDownLatch(totalThreads);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<BlobId, byte[]> blobDataMap = new ConcurrentHashMap<>();
    List<BlobId> blobIds = new ArrayList<>();
    Random random = new Random();
    
    try {
      // Create some initial blobs
      for (int i = 0; i < LOW_THREAD_COUNT; i++) {
        byte[] data = new byte[random.nextInt(BLOB_MAX_SIZE_BYTES) + 1];
        random.nextBytes(data);
        Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
        blobIds.add(blob.getId());
        blobDataMap.put(blob.getId(), data);
      }
      
      // Creator threads
      for (int i = 0; i < creatorThreads; i++) {
        executor.submit(() -> {
          try {
            for (int j = 0; j < 5; j++) { // Each creator creates multiple blobs
              byte[] data = new byte[random.nextInt(BLOB_MAX_SIZE_BYTES) + 1];
              random.nextBytes(data);
              Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
              synchronized (blobIds) {
                blobIds.add(blob.getId());
              }
              blobDataMap.put(blob.getId(), data);
            }
          } catch (Exception e) {
            log.error("Error in creator thread", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Reader threads
      for (int i = 0; i < readerThreads; i++) {
        executor.submit(() -> {
          try {
            for (int j = 0; j < 3; j++) { // Each reader reads multiple blobs
              BlobId blobId = null;
              synchronized (blobIds) {
                if (!blobIds.isEmpty()) {
                  blobId = blobIds.get(random.nextInt(blobIds.size()));
                }
              }
              
              if (blobId != null) {
                Blob blob = underTest.get(blobId);
                if (blob != null) {
                  try (InputStream inputStream = blob.getInputStream()) {
                    byte[] expectedData = blobDataMap.get(blobId);
                    if (expectedData != null) {
                      readContentAndValidateMetrics(blobId, inputStream, blob.getMetrics(), expectedData);
                    }
                  } catch (BlobStoreException e) {
                    // This is expected if the blob was concurrently deleted
                    log.debug("Blob was concurrently deleted: {}", blobId);
                  }
                }
              }
            }
          } catch (Exception e) {
            log.error("Error in reader thread", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Deleter threads
      for (int i = 0; i < deleterThreads; i++) {
        executor.submit(() -> {
          try {
            for (int j = 0; j < 2; j++) { // Each deleter deletes multiple blobs
              BlobId blobId = null;
              synchronized (blobIds) {
                if (!blobIds.isEmpty()) {
                  int index = random.nextInt(blobIds.size());
                  blobId = blobIds.remove(index); // Remove from list to avoid duplicate deletions
                }
              }
              
              if (blobId != null) {
                underTest.delete(blobId, "Testing mixed operations");
                blobDataMap.remove(blobId);
              }
            }
          } catch (Exception e) {
            log.error("Error in deleter thread", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "All threads should complete within timeout");
      assertEquals(0, errorCount.get(), "No errors should occur during mixed operations");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests performance comparison between virtual threads and platform threads.
   * 
   * This test compares the performance of blob operations using both virtual threads and
   * platform threads to validate the performance benefits of virtual threads for I/O-bound
   * operations in the FileBlobStore implementation.
   */
  @Test
  void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Create thread factories for both thread types
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    int operationCount = MEDIUM_THREAD_COUNT;
    Random random = new Random();
    
    // Prepare test data
    byte[][] testData = new byte[operationCount][];
    for (int i = 0; i < operationCount; i++) {
      testData[i] = new byte[random.nextInt(BLOB_MAX_SIZE_BYTES) + 1];
      random.nextBytes(testData[i]);
    }
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      ExecutorService executor = Executors.newThreadPerTaskExecutor(platformThreadFactory);
      try {
        CountDownLatch latch = new CountDownLatch(operationCount);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<BlobId> blobIds = new ArrayList<>();
        
        // Create blobs
        for (int i = 0; i < operationCount; i++) {
          final int index = i;
          executor.submit(() -> {
            try {
              Blob blob = underTest.create(new ByteArrayInputStream(testData[index]), TEST_HEADERS);
              synchronized (blobIds) {
                blobIds.add(blob.getId());
              }
            } catch (Exception e) {
              errorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          });
        }
        
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, errorCount.get(), "No errors should occur with platform threads");
        
        // Clean up created blobs
        for (BlobId blobId : blobIds) {
          underTest.delete(blobId, "Cleanup after platform thread test");
        }
      } finally {
        executor.shutdown();
      }
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
      try {
        CountDownLatch latch = new CountDownLatch(operationCount);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<BlobId> blobIds = new ArrayList<>();
        
        // Create blobs
        for (int i = 0; i < operationCount; i++) {
          final int index = i;
          executor.submit(() -> {
            try {
              Blob blob = underTest.create(new ByteArrayInputStream(testData[index]), TEST_HEADERS);
              synchronized (blobIds) {
                blobIds.add(blob.getId());
              }
            } catch (Exception e) {
              errorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          });
        }
        
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, errorCount.get(), "No errors should occur with virtual threads");
        
        // Clean up created blobs
        for (BlobId blobId : blobIds) {
          underTest.delete(blobId, "Cleanup after virtual thread test");
        }
      } finally {
        executor.shutdown();
      }
    });
    
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // We don't assert on specific performance improvements as they can vary by environment,
    // but we log the results for analysis
  }

  /**
   * Tests that the FileBlobStore compact operation works correctly with virtual threads.
   */
  @Test
  void testCompactOperationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        // Create and delete some blobs to ensure there's something to compact
        List<BlobId> blobIds = new ArrayList<>();
        Random random = new Random();
        
        for (int i = 0; i < LOW_THREAD_COUNT; i++) {
          byte[] data = new byte[random.nextInt(BLOB_MAX_SIZE_BYTES) + 1];
          random.nextBytes(data);
          Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
          blobIds.add(blob.getId());
        }
        
        // Delete half the blobs
        for (int i = 0; i < blobIds.size() / 2; i++) {
          underTest.delete(blobIds.get(i), "Preparing for compact test");
        }
        
        // Run compact operation
        underTest.compact(null);
      } catch (Exception e) {
        log.error("Error in compact test", e);
        throw new RuntimeException(e);
      }
    });
    
    virtualThread.start();
    virtualThread.join(TIMEOUT_SECONDS * 1000);
    
    // If the thread is still alive after timeout, it's likely stuck
    assertTrue(!virtualThread.isAlive(), "Compact operation should complete within timeout");
  }

  /**
   * Measures the execution time of a runnable operation.
   *
   * @param operation The operation to measure
   * @return The execution time in milliseconds
   */
  private long measureExecutionTime(Runnable operation) throws Exception {
    long startTime = System.nanoTime();
    operation.run();
    long endTime = System.nanoTime();
    return Duration.ofNanos(endTime - startTime).toMillis();
  }

  /**
   * Read all the content from a blob, and compare it with the metrics on file in the blob store.
   *
   * @throws RuntimeException if there is any deviation
   */
  private void readContentAndValidateMetrics(
      final BlobId blobId,
      final InputStream inputStream,
      final BlobMetrics metadataMetrics,
      final byte[] expectedData) throws NoSuchAlgorithmException, IOException
  {
    final MetricsInputStream measured = new MetricsInputStream(inputStream);
    ByteStreams.copy(measured, nullOutputStream());

    checkEqual("stream length", metadataMetrics.getContentSize(), measured.getSize(), blobId);
    checkEqual("SHA1 hash", metadataMetrics.getSha1Hash(), measured.getMessageDigest(), blobId);
    
    // If expected data is provided, verify content size matches
    if (expectedData != null) {
      checkEqual("content size", (long) expectedData.length, measured.getSize(), blobId);
    }
  }

  private void checkEqual(
      final String propertyName,
      final Object expected,
      final Object measured,
      final BlobId blobId)
  {
    if (!Objects.equal(measured, expected)) {
      throw new RuntimeException(
          "Blob " + blobId + "'s measured " + propertyName + " differed from its metadata. Expected " + expected +
              " but was " + measured + ".");
    }
  }
}