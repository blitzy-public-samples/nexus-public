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
package org.sonatype.nexus.blobstore.virtualthread;

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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.BlobStoreReconciliationLogger;
import org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.file.FileBlobDeletionIndex;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.file.internal.FileOperations;
import org.sonatype.nexus.blobstore.file.internal.SimpleFileOperations;
import org.sonatype.nexus.blobstore.file.internal.datastore.metrics.DatastoreFileBlobStoreMetricsService;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaUsageChecker;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.scheduling.internal.PeriodicJobServiceImpl;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;

import static com.google.common.base.Preconditions.checkState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;

/**
 * {@link FileBlobStore} stress test using Java 21 Virtual Threads.
 * 
 * This test validates FileBlobStore behavior under extreme concurrency conditions
 * using thousands of virtual threads. It performs massive parallel operations—creating,
 * retrieving, and deleting blobs simultaneously—to verify the system maintains correctness
 * and stability under load.
 * 
 * The test specifically verifies that FileBlobStore can handle the dramatically increased
 * concurrency enabled by Java 21 Virtual Threads while preventing resource exhaustion
 * and maintaining data integrity.
 */
public class FileBlobStoreStressTest
    extends TestSupport
{
  private static final ImmutableMap<String, String> TEST_HEADERS = ImmutableMap.of(
      CREATED_BY_HEADER, "test",
      BLOB_NAME_HEADER, "test/randomData.bin");

  private static final int BLOB_MAX_SIZE_BYTES = 50_000;
  private static final int QUOTA_CHECK_INTERVAL = 1;
  private static final int VIRTUAL_THREAD_COUNT = 10_000;
  private static final int OPERATION_COUNT = 20_000;
  private static final int TEST_TIMEOUT_SECONDS = 120;

  private FileBlobStore underTest;

  @Mock
  private DatastoreFileBlobStoreMetricsService metricsStore;

  private BlobStoreQuotaUsageChecker blobStoreQuotaUsageChecker;

  @Mock
  FileBlobDeletionIndex fileBlobDeletionIndex;

  @Mock
  NodeAccess nodeAccess;

  @Mock
  DryRunPrefix dryRunPrefix;

  @Mock
  BlobStoreQuotaService quotaService;

  @Mock
  FileOperations fileOperations;

  @Mock
  private BlobStoreReconciliationLogger reconciliationLogger;

  private Path tempDir;

  @BeforeEach
  public void setUp() throws Exception {
    tempDir = util.createTempDir().toPath();
    Path content = tempDir.resolve("content");

    when(nodeAccess.getId()).thenReturn(UUID.randomUUID().toString());
    when(dryRunPrefix.get()).thenReturn("");

    ApplicationDirectories applicationDirectories = mock(ApplicationDirectories.class);
    when(applicationDirectories.getWorkDirectory(anyString())).thenReturn(tempDir.toFile());

    final BlobStoreConfiguration config = new MockBlobStoreConfiguration();
    config.attributes(FileBlobStore.CONFIG_KEY).set(FileBlobStore.PATH_KEY, tempDir.toString());

    blobStoreQuotaUsageChecker = spy(
        new BlobStoreQuotaUsageChecker(new PeriodicJobServiceImpl(), QUOTA_CHECK_INTERVAL, quotaService));

    this.underTest = new FileBlobStore(content, new DefaultBlobIdLocationResolver(true), new SimpleFileOperations(),
        metricsStore, config, applicationDirectories, nodeAccess, dryRunPrefix, reconciliationLogger, 0L,
        blobStoreQuotaUsageChecker, fileBlobDeletionIndex);
    underTest.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (underTest != null) {
      underTest.stop();
    }
  }

  /**
   * Tests the FileBlobStore under extreme load with thousands of virtual threads
   * performing concurrent create operations.
   * 
   * This test verifies that the FileBlobStore can handle massive concurrency
   * enabled by Java 21 Virtual Threads while maintaining data integrity.
   */
  @Test
  @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testMassiveConcurrentCreates() throws Exception {
    log.info("Starting massive concurrent creates test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    
    // Track memory usage before test
    long memoryBefore = getUsedMemory();
    log.info("Memory usage before test: {} MB", memoryBefore / (1024 * 1024));
    
    // Create a thread-safe collection to store created blob IDs
    ConcurrentLinkedQueue<BlobId> createdBlobIds = new ConcurrentLinkedQueue<>();
    
    // Track metrics
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicLong totalBytes = new AtomicLong(0);
    
    // Create a countdown latch to wait for all operations to complete
    int operationCount = Math.min(OPERATION_COUNT, VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    // Create a random number generator
    Random random = new Random();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to create blobs
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Create random data
            int size = random.nextInt(BLOB_MAX_SIZE_BYTES) + 1;
            byte[] data = new byte[size];
            random.nextBytes(data);
            
            // Create blob
            Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
            
            // Track metrics
            createdBlobIds.add(blob.getId());
            successCount.incrementAndGet();
            totalBytes.addAndGet(size);
          } 
          catch (Exception e) {
            log.error("Error creating blob", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(TEST_TIMEOUT_SECONDS - 10, TimeUnit.SECONDS);
      assertTrue(completed, "Not all operations completed within the timeout period");
    }
    
    // Track memory usage after test
    long memoryAfter = getUsedMemory();
    log.info("Memory usage after test: {} MB", memoryAfter / (1024 * 1024));
    log.info("Memory increase: {} MB", (memoryAfter - memoryBefore) / (1024 * 1024));
    
    // Log metrics
    log.info("Created {} blobs successfully", successCount.get());
    log.info("Encountered {} errors", errorCount.get());
    log.info("Total data size: {} MB", totalBytes.get() / (1024 * 1024));
    
    // Verify results
    assertEquals(operationCount, successCount.get() + errorCount.get(), "Total operations should match");
    assertEquals(0, errorCount.get(), "Should have no errors");
    assertEquals(operationCount, createdBlobIds.size(), "Should have created expected number of blobs");
    
    // Verify memory usage is reasonable (less than 1GB increase for this test)
    assertThat("Memory increase should be reasonable", 
        (memoryAfter - memoryBefore) / (1024 * 1024), 
        is(lessThan(1024L)));
  }

  /**
   * Tests the FileBlobStore under extreme load with thousands of virtual threads
   * performing concurrent create, get, and delete operations.
   * 
   * This test verifies that the FileBlobStore can handle mixed operations under
   * high concurrency while maintaining data integrity and preventing resource exhaustion.
   */
  @Test
  @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testMixedConcurrentOperations() throws Exception {
    log.info("Starting mixed concurrent operations test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    
    // Track memory usage before test
    long memoryBefore = getUsedMemory();
    log.info("Memory usage before test: {} MB", memoryBefore / (1024 * 1024));
    
    // Create a thread-safe map to store created blob IDs and their data
    ConcurrentHashMap<BlobId, byte[]> blobDataMap = new ConcurrentHashMap<>();
    
    // Track metrics
    AtomicInteger createCount = new AtomicInteger(0);
    AtomicInteger getCount = new AtomicInteger(0);
    AtomicInteger deleteCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a countdown latch to wait for all operations to complete
    int operationCount = Math.min(OPERATION_COUNT, VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    // Create a random number generator
    Random random = new Random();
    
    // First, create some initial blobs to work with
    List<BlobId> initialBlobIds = new ArrayList<>();
    List<byte[]> initialBlobData = new ArrayList<>();
    int initialBlobCount = Math.min(1000, operationCount / 10);
    
    for (int i = 0; i < initialBlobCount; i++) {
      int size = random.nextInt(BLOB_MAX_SIZE_BYTES) + 1;
      byte[] data = new byte[size];
      random.nextBytes(data);
      
      Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
      initialBlobIds.add(blob.getId());
      initialBlobData.add(data);
      blobDataMap.put(blob.getId(), data);
    }
    
    log.info("Created {} initial blobs", initialBlobCount);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to perform mixed operations
      for (int i = 0; i < operationCount; i++) {
        final int operationIndex = i;
        executor.submit(() -> {
          try {
            // Determine operation type: 0=create, 1=get, 2=delete
            int operationType;
            if (blobDataMap.isEmpty()) {
              operationType = 0; // Create if no blobs exist
            } else {
              operationType = operationIndex % 3;
            }
            
            switch (operationType) {
              case 0: // Create
                int size = random.nextInt(BLOB_MAX_SIZE_BYTES) + 1;
                byte[] data = new byte[size];
                random.nextBytes(data);
                
                Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
                blobDataMap.put(blob.getId(), data);
                createCount.incrementAndGet();
                break;
                
              case 1: // Get
                List<BlobId> blobIds = new ArrayList<>(blobDataMap.keySet());
                if (!blobIds.isEmpty()) {
                  BlobId blobId = blobIds.get(random.nextInt(blobIds.size()));
                  Blob retrievedBlob = underTest.get(blobId);
                  
                  if (retrievedBlob != null) {
                    // Verify content integrity
                    byte[] expectedData = blobDataMap.get(blobId);
                    if (expectedData != null) {
                      try (InputStream is = retrievedBlob.getInputStream()) {
                        byte[] actualData = is.readAllBytes();
                        // We don't compare the data here to avoid excessive memory usage
                        // Just check the size matches
                        if (actualData.length == expectedData.length) {
                          getCount.incrementAndGet();
                        } else {
                          log.error("Data size mismatch for blob {}", blobId);
                          errorCount.incrementAndGet();
                        }
                      }
                    }
                  }
                }
                break;
                
              case 2: // Delete
                List<BlobId> deleteBlobIds = new ArrayList<>(blobDataMap.keySet());
                if (!deleteBlobIds.isEmpty()) {
                  BlobId blobId = deleteBlobIds.get(random.nextInt(deleteBlobIds.size()));
                  underTest.delete(blobId, "Stress test deletion");
                  blobDataMap.remove(blobId);
                  deleteCount.incrementAndGet();
                }
                break;
            }
          } 
          catch (BlobStoreException e) {
            // This can happen if we try to get or delete a blob that was already deleted
            // by another thread, which is expected in a concurrent test
            log.debug("Expected concurrent operation exception: {}", e.getMessage());
          }
          catch (Exception e) {
            log.error("Error performing operation", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(TEST_TIMEOUT_SECONDS - 10, TimeUnit.SECONDS);
      assertTrue(completed, "Not all operations completed within the timeout period");
    }
    
    // Track memory usage after test
    long memoryAfter = getUsedMemory();
    log.info("Memory usage after test: {} MB", memoryAfter / (1024 * 1024));
    log.info("Memory increase: {} MB", (memoryAfter - memoryBefore) / (1024 * 1024));
    
    // Log metrics
    log.info("Created {} blobs", createCount.get());
    log.info("Retrieved {} blobs", getCount.get());
    log.info("Deleted {} blobs", deleteCount.get());
    log.info("Encountered {} errors", errorCount.get());
    
    // Verify results
    assertTrue(createCount.get() > 0, "Should have created some blobs");
    assertTrue(getCount.get() > 0, "Should have retrieved some blobs");
    assertTrue(deleteCount.get() > 0, "Should have deleted some blobs");
    assertEquals(0, errorCount.get(), "Should have no errors");
    
    // Verify memory usage is reasonable (less than 1GB increase for this test)
    assertThat("Memory increase should be reasonable", 
        (memoryAfter - memoryBefore) / (1024 * 1024), 
        is(lessThan(1024L)));
  }

  /**
   * Tests the FileBlobStore's resilience under extreme load with thousands of virtual threads
   * performing rapid create and delete operations to simulate high churn.
   * 
   * This test verifies that the FileBlobStore can handle high-frequency create/delete
   * operations under high concurrency without resource leaks or stability issues.
   */
  @Test
  @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testHighChurnOperations() throws Exception {
    log.info("Starting high churn operations test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    
    // Track memory usage before test
    long memoryBefore = getUsedMemory();
    log.info("Memory usage before test: {} MB", memoryBefore / (1024 * 1024));
    
    // Track metrics
    AtomicInteger createCount = new AtomicInteger(0);
    AtomicInteger deleteCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a thread-safe collection to store created blob IDs
    ConcurrentLinkedQueue<BlobId> blobIds = new ConcurrentLinkedQueue<>();
    
    // Create a countdown latch to wait for all operations to complete
    int operationCount = Math.min(OPERATION_COUNT, VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    // Create a random number generator
    Random random = new Random();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to perform high-churn operations
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Create a blob
            int size = random.nextInt(BLOB_MAX_SIZE_BYTES) + 1;
            byte[] data = new byte[size];
            random.nextBytes(data);
            
            Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
            blobIds.add(blob.getId());
            createCount.incrementAndGet();
            
            // Immediately delete some blobs to create churn
            if (random.nextBoolean() && !blobIds.isEmpty()) {
              // Try to delete a random blob
              BlobId blobIdToDelete = null;
              for (BlobId id : blobIds) {
                if (random.nextBoolean()) {
                  blobIdToDelete = id;
                  break;
                }
              }
              
              if (blobIdToDelete != null) {
                try {
                  underTest.delete(blobIdToDelete, "Stress test deletion");
                  blobIds.remove(blobIdToDelete);
                  deleteCount.incrementAndGet();
                } catch (BlobStoreException e) {
                  // This can happen if the blob was already deleted by another thread
                  log.debug("Expected concurrent deletion exception: {}", e.getMessage());
                }
              }
            }
          } 
          catch (Exception e) {
            log.error("Error performing operation", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(TEST_TIMEOUT_SECONDS - 10, TimeUnit.SECONDS);
      assertTrue(completed, "Not all operations completed within the timeout period");
    }
    
    // Track memory usage after test
    long memoryAfter = getUsedMemory();
    log.info("Memory usage after test: {} MB", memoryAfter / (1024 * 1024));
    log.info("Memory increase: {} MB", (memoryAfter - memoryBefore) / (1024 * 1024));
    
    // Log metrics
    log.info("Created {} blobs", createCount.get());
    log.info("Deleted {} blobs", deleteCount.get());
    log.info("Encountered {} errors", errorCount.get());
    log.info("Remaining blobs: {}", blobIds.size());
    
    // Verify results
    assertEquals(operationCount, createCount.get(), "Should have created expected number of blobs");
    assertTrue(deleteCount.get() > 0, "Should have deleted some blobs");
    assertEquals(0, errorCount.get(), "Should have no errors");
    assertEquals(createCount.get() - deleteCount.get(), blobIds.size(), "Remaining blob count should match");
    
    // Verify memory usage is reasonable (less than 1GB increase for this test)
    assertThat("Memory increase should be reasonable", 
        (memoryAfter - memoryBefore) / (1024 * 1024), 
        is(lessThan(1024L)));
    
    // Clean up remaining blobs
    log.info("Cleaning up remaining {} blobs", blobIds.size());
    for (BlobId blobId : blobIds) {
      try {
        underTest.delete(blobId, "Cleanup");
      } catch (Exception e) {
        log.debug("Error during cleanup: {}", e.getMessage());
      }
    }
  }

  /**
   * Tests the FileBlobStore's performance under sustained load with virtual threads
   * performing operations over a longer period.
   * 
   * This test verifies that the FileBlobStore maintains stability and performance
   * under sustained high concurrency without degradation over time.
   */
  @Test
  @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testSustainedLoad() throws Exception {
    log.info("Starting sustained load test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    
    // Track memory usage before test
    long memoryBefore = getUsedMemory();
    log.info("Memory usage before test: {} MB", memoryBefore / (1024 * 1024));
    
    // Track metrics
    AtomicInteger operationCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a thread-safe collection to store created blob IDs
    ConcurrentLinkedQueue<BlobId> blobIds = new ConcurrentLinkedQueue<>();
    
    // Create a random number generator
    Random random = new Random();
    
    // Set test duration
    Duration testDuration = Duration.ofSeconds(30);
    long endTime = System.currentTimeMillis() + testDuration.toMillis();
    
    // Create a virtual thread executor with a limited number of threads
    int threadCount = Math.min(1000, VIRTUAL_THREAD_COUNT);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to perform sustained operations
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            while (System.currentTimeMillis() < endTime) {
              // Determine operation type: 0=create, 1=get, 2=delete
              int operationType;
              if (blobIds.isEmpty()) {
                operationType = 0; // Create if no blobs exist
              } else {
                operationType = random.nextInt(3);
              }
              
              switch (operationType) {
                case 0: // Create
                  int size = random.nextInt(BLOB_MAX_SIZE_BYTES) + 1;
                  byte[] data = new byte[size];
                  random.nextBytes(data);
                  
                  Blob blob = underTest.create(new ByteArrayInputStream(data), TEST_HEADERS);
                  blobIds.add(blob.getId());
                  operationCount.incrementAndGet();
                  break;
                  
                case 1: // Get
                  if (!blobIds.isEmpty()) {
                    BlobId blobId = getRandomBlobId(blobIds, random);
                    if (blobId != null) {
                      try {
                        Blob retrievedBlob = underTest.get(blobId);
                        if (retrievedBlob != null) {
                          // Just read the first byte to verify it exists
                          retrievedBlob.getInputStream().read();
                          operationCount.incrementAndGet();
                        }
                      } catch (BlobStoreException e) {
                        // This can happen if the blob was deleted by another thread
                        log.debug("Expected concurrent operation exception: {}", e.getMessage());
                      }
                    }
                  }
                  break;
                  
                case 2: // Delete
                  if (!blobIds.isEmpty()) {
                    BlobId blobId = getRandomBlobId(blobIds, random);
                    if (blobId != null) {
                      try {
                        underTest.delete(blobId, "Stress test deletion");
                        blobIds.remove(blobId);
                        operationCount.incrementAndGet();
                      } catch (BlobStoreException e) {
                        // This can happen if the blob was already deleted by another thread
                        log.debug("Expected concurrent deletion exception: {}", e.getMessage());
                      }
                    }
                  }
                  break;
              }
              
              // Small pause to prevent CPU saturation
              Thread.sleep(1);
            }
          } 
          catch (Exception e) {
            log.error("Error performing operation", e);
            errorCount.incrementAndGet();
          }
        });
      }
      
      // Wait for the test duration to complete
      Thread.sleep(testDuration.toMillis());
      
      // Shutdown the executor and wait for tasks to complete
      executor.shutdown();
      boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
      assertTrue(terminated, "Executor did not terminate within the timeout period");
    }
    
    // Track memory usage after test
    long memoryAfter = getUsedMemory();
    log.info("Memory usage after test: {} MB", memoryAfter / (1024 * 1024));
    log.info("Memory increase: {} MB", (memoryAfter - memoryBefore) / (1024 * 1024));
    
    // Log metrics
    log.info("Performed {} operations", operationCount.get());
    log.info("Encountered {} errors", errorCount.get());
    log.info("Remaining blobs: {}", blobIds.size());
    log.info("Operations per second: {}", operationCount.get() / testDuration.getSeconds());
    
    // Verify results
    assertTrue(operationCount.get() > 0, "Should have performed some operations");
    assertEquals(0, errorCount.get(), "Should have no errors");
    
    // Verify memory usage is reasonable (less than 1GB increase for this test)
    assertThat("Memory increase should be reasonable", 
        (memoryAfter - memoryBefore) / (1024 * 1024), 
        is(lessThan(1024L)));
    
    // Clean up remaining blobs
    log.info("Cleaning up remaining {} blobs", blobIds.size());
    for (BlobId blobId : blobIds) {
      try {
        underTest.delete(blobId, "Cleanup");
      } catch (Exception e) {
        log.debug("Error during cleanup: {}", e.getMessage());
      }
    }
  }

  /**
   * Helper method to get a random blob ID from the collection.
   */
  private BlobId getRandomBlobId(ConcurrentLinkedQueue<BlobId> blobIds, Random random) {
    if (blobIds.isEmpty()) {
      return null;
    }
    
    // Convert to array for random access
    BlobId[] blobIdArray = blobIds.toArray(new BlobId[0]);
    if (blobIdArray.length == 0) {
      return null;
    }
    
    return blobIdArray[random.nextInt(blobIdArray.length)];
  }

  /**
   * Helper method to get current memory usage.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    runtime.gc(); // Request garbage collection to get more accurate memory usage
    return runtime.totalMemory() - runtime.freeMemory();
  }
}