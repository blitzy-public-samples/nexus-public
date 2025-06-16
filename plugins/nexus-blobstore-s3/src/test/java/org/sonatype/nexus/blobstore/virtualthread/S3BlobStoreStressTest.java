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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaUsageChecker;
import org.sonatype.nexus.blobstore.s3.internal.AmazonS3Factory;
import org.sonatype.nexus.blobstore.s3.internal.BucketManager;
import org.sonatype.nexus.blobstore.s3.internal.S3BlobStore;
import org.sonatype.nexus.blobstore.s3.internal.S3Copier;
import org.sonatype.nexus.blobstore.s3.internal.S3Uploader;
import org.sonatype.nexus.blobstore.s3.internal.datastore.DatastoreS3BlobStoreMetricsService;
import org.sonatype.nexus.common.log.DryRunPrefix;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_IP_HEADER;

/**
 * Stress test for {@link S3BlobStore} using Java 21 Virtual Threads.
 * <p>
 * This test validates S3BlobStore behavior under extreme concurrency conditions using thousands of virtual threads.
 * It performs massive parallel operations—creating, retrieving, and deleting blobs simultaneously—to verify the
 * system maintains correctness and stability under load.
 * </p>
 * <p>
 * The test specifically verifies that S3BlobStore can handle the dramatically increased concurrency enabled by
 * Java 21 Virtual Threads while preventing resource exhaustion and maintaining data integrity.
 * </p>
 *
 * @since 3.60
 */
public class S3BlobStoreStressTest
    extends TestSupport
{
  /**
   * Number of virtual threads to use for extreme concurrency tests.
   */
  private static final int EXTREME_CONCURRENCY = 10_000;

  /**
   * Number of platform threads to use for comparison tests.
   */
  private static final int PLATFORM_THREAD_COUNT = 200;

  /**
   * Default content size for test blobs in bytes.
   */
  private static final int DEFAULT_CONTENT_SIZE = 1024; // 1KB

  /**
   * Default timeout for stress tests in seconds.
   */
  private static final int STRESS_TEST_TIMEOUT_SECONDS = 120;

  /**
   * Default number of operations for stress tests.
   */
  private static final int STRESS_TEST_OPERATIONS = 5_000;

  /**
   * Default duration for sustained load tests in seconds.
   */
  private static final int SUSTAINED_LOAD_DURATION_SECONDS = 30;

  @Mock
  private AmazonS3Factory amazonS3Factory;

  @Mock
  private S3Uploader uploader;

  @Mock
  private S3Copier copier;

  @Mock
  private DatastoreS3BlobStoreMetricsService storeMetrics;

  @Mock
  private BlobStoreQuotaUsageChecker blobStoreQuotaUsageChecker;

  @Mock
  private DryRunPrefix dryRunPrefix;

  @Mock
  private BucketManager bucketManager;

  @Mock
  private S3Client s3;

  private S3BlobStore blobStore;

  private MockBlobStoreConfiguration config;

  private final Map<String, byte[]> blobContentStore = new ConcurrentHashMap<>();

  private final AtomicLong memoryUsageBytes = new AtomicLong(0);

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);

    // Setup S3BlobStore with mocked dependencies
    blobStore = new S3BlobStore(amazonS3Factory, null, uploader, copier, false,
        false, false, storeMetrics, dryRunPrefix, bucketManager, blobStoreQuotaUsageChecker);

    // Configure mock S3 client
    when(amazonS3Factory.create(any())).thenReturn(s3);

    // Setup basic configuration
    config = new MockBlobStoreConfiguration();
    config.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "test-bucket", "prefix", "test-prefix")))));

    // Mock S3 operations
    mockS3Operations();

    // Initialize the blob store
    blobStore.init(config);
    blobStore.doStart();
  }

  @After
  public void tearDown() throws Exception {
    if (blobStore != null) {
      blobStore.doStop();
    }
    blobContentStore.clear();
    memoryUsageBytes.set(0);
  }

  /**
   * Sets up mocks for S3 operations to simulate S3 behavior without actual AWS calls.
   */
  private void mockS3Operations() {
    // Mock object existence check
    HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
            .bucket(anyString())
            .key(anyString())
            .build();

    when(s3.headObject(any(HeadObjectRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());

    // Mock object creation
    doAnswer(invocation -> {
      String bucket = invocation.getArgument(0);
      String key = invocation.getArgument(1);
      InputStream inputStream = invocation.getArgument(2);
      ObjectMetadata metadata = invocation.getArgument(3);

      // Store the content in our in-memory store
      byte[] content = inputStream.readAllBytes();
      blobContentStore.put(key, content);
      memoryUsageBytes.addAndGet(content.length);

      return null;
    }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

    // Mock uploader for larger objects
    doAnswer(invocation -> {
      InputStream inputStream = invocation.getArgument(0);
      String bucket = invocation.getArgument(1);
      String key = invocation.getArgument(2);
      ObjectMetadata metadata = invocation.getArgument(3);

      // Store the content in our in-memory store
      byte[] content = inputStream.readAllBytes();
      blobContentStore.put(key, content);
      memoryUsageBytes.addAndGet(content.length);

      return null;
    }).when(uploader).upload(any(AmazonS3.class), anyString(), anyString(), any(InputStream.class));

    // Mock object retrieval
    doAnswer(invocation -> {
      String bucket = invocation.getArgument(0);
      String key = invocation.getArgument(1);

      byte[] content = blobContentStore.get(key);
      if (content == null) {
        throw new BlobStoreException("Object not found: " + key,null);
      }

      S3Object s3Object = mock(S3Object.class);
      S3ObjectInputStream s3InputStream = new S3ObjectInputStream(
          new ByteArrayInputStream(content), null);
      when(s3Object.getObjectContent()).thenReturn(s3InputStream);

      return s3Object;
    }).when(s3.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)));


    // Mock object deletion
    doAnswer(invocation -> {
      DeleteObjectRequest request = invocation.getArgument(0);

      String bucket = request.bucket();
      String key = request.key();

      byte[] content = blobContentStore.remove(key);
      if (content != null) {
        memoryUsageBytes.addAndGet(-content.length);
      }

      // DeleteObjectResponse is the return type, you can return a mocked or default instance:
      return DeleteObjectResponse.builder().build();
    }).when(s3).deleteObject(any(DeleteObjectRequest.class));

  }

  /**
   * Tests the creation of a large number of blobs concurrently using virtual threads.
   * <p>
   * This test verifies that the S3BlobStore can handle extreme concurrency for blob creation
   * operations using virtual threads, maintaining correctness and stability under load.
   * </p>
   */
  @Test
  public void testMassiveConcurrentBlobCreation() throws Exception {
    // Skip test if virtual threads are not enabled
    if (!S3VirtualThreadTestSupport.isVirtualThreadTestingEnabled()) {
      System.out.println("Skipping testMassiveConcurrentBlobCreation - virtual threads not enabled");
      return;
    }

    int operationCount = STRESS_TEST_OPERATIONS;
    System.out.println("Starting massive concurrent blob creation test with " + operationCount + " operations");

    // Create executor with virtual threads
    ExecutorService executor = S3VirtualThreadTestSupport.createVirtualThreadExecutor("blob-creation-test");

    try {
      // Track created blob IDs for validation
      List<BlobId> createdBlobIds = new ArrayList<>();
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicReference<Throwable> firstError = new AtomicReference<>();

      // Start timer
      long startTime = System.currentTimeMillis();

      // Submit blob creation tasks
      for (int i = 0; i < operationCount; i++) {
        final String blobName = "stress-test-blob-" + UUID.randomUUID();
        executor.submit(() -> {
          try {
            // Create blob with random content
            Blob blob = createTestBlob(blobStore, blobName, DEFAULT_CONTENT_SIZE);
            synchronized (createdBlobIds) {
              createdBlobIds.add(blob.getId());
            }
          }
          catch (Throwable t) {
            errorCount.incrementAndGet();
            if (firstError.get() == null) {
              firstError.set(t);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete or timeout
      boolean completed = latch.await(STRESS_TEST_TIMEOUT_SECONDS, SECONDS);

      // Calculate metrics
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;
      double operationsPerSecond = (double) (operationCount - errorCount.get()) / (duration / 1000.0);

      // Print results
      System.out.println("Massive concurrent blob creation test results:");
      System.out.println("  Completed: " + completed);
      System.out.println("  Operations: " + operationCount);
      System.out.println("  Successful: " + (operationCount - errorCount.get()));
      System.out.println("  Errors: " + errorCount.get());
      System.out.println("  Duration: " + duration + "ms");
      System.out.println("  Operations/second: " + String.format("%.2f", operationsPerSecond));
      System.out.println("  Memory usage: " + formatBytes(memoryUsageBytes.get()));

      // Verify results
      assertTrue("Test should complete within timeout", completed);
      assertEquals("Should have no errors", 0, errorCount.get());
      assertEquals("Should create all blobs", operationCount, createdBlobIds.size());

      // Verify memory usage is reasonable (less than 2GB for 5000 1KB blobs)
      assertThat(memoryUsageBytes.get(), lessThan(2L * 1024 * 1024 * 1024));

      // Verify throughput meets minimum expectations (at least 100 ops/sec)
      assertThat(operationsPerSecond, greaterThan(100.0));
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(1, MINUTES);
    }
  }

  /**
   * Tests the retrieval of a large number of blobs concurrently using virtual threads.
   * <p>
   * This test verifies that the S3BlobStore can handle extreme concurrency for blob retrieval
   * operations using virtual threads, maintaining correctness and stability under load.
   * </p>
   */
  @Test
  public void testMassiveConcurrentBlobRetrieval() throws Exception {
    // Skip test if virtual threads are not enabled
    if (!S3VirtualThreadTestSupport.isVirtualThreadTestingEnabled()) {
      System.out.println("Skipping testMassiveConcurrentBlobRetrieval - virtual threads not enabled");
      return;
    }

    // Create a set of test blobs first
    int blobCount = 100; // Create fewer blobs but access them repeatedly
    List<BlobId> blobIds = new ArrayList<>(blobCount);
    Map<BlobId, byte[]> expectedContents = new HashMap<>();

    System.out.println("Creating " + blobCount + " test blobs for retrieval test");
    for (int i = 0; i < blobCount; i++) {
      String blobName = "retrieval-test-blob-" + i;
      Blob blob = createTestBlob(blobStore, blobName, DEFAULT_CONTENT_SIZE);
      blobIds.add(blob.getId());

      // Store expected content for validation
      try (InputStream is = blob.getInputStream()) {
        expectedContents.put(blob.getId(), is.readAllBytes());
      }
    }

    int operationCount = STRESS_TEST_OPERATIONS;
    System.out.println("Starting massive concurrent blob retrieval test with " + operationCount + " operations");

    // Create executor with virtual threads
    ExecutorService executor = S3VirtualThreadTestSupport.createVirtualThreadExecutor("blob-retrieval-test");

    try {
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicInteger validationErrorCount = new AtomicInteger(0);
      AtomicReference<Throwable> firstError = new AtomicReference<>();

      // Start timer
      long startTime = System.currentTimeMillis();

      // Submit blob retrieval tasks
      for (int i = 0; i < operationCount; i++) {
        final int index = i % blobIds.size(); // Cycle through available blobs
        final BlobId blobId = blobIds.get(index);
        final byte[] expectedContent = expectedContents.get(blobId);

        executor.submit(() -> {
          try {
            // Retrieve blob
            Blob blob = blobStore.get(blobId);
            assertThat("Blob should not be null", blob, notNullValue());

            // Validate content
            try (InputStream is = blob.getInputStream()) {
              byte[] actualContent = is.readAllBytes();
              if (!java.util.Arrays.equals(expectedContent, actualContent)) {
                validationErrorCount.incrementAndGet();
              }
            }
          }
          catch (Throwable t) {
            errorCount.incrementAndGet();
            if (firstError.get() == null) {
              firstError.set(t);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete or timeout
      boolean completed = latch.await(STRESS_TEST_TIMEOUT_SECONDS, SECONDS);

      // Calculate metrics
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;
      double operationsPerSecond = (double) (operationCount - errorCount.get()) / (duration / 1000.0);

      // Print results
      System.out.println("Massive concurrent blob retrieval test results:");
      System.out.println("  Completed: " + completed);
      System.out.println("  Operations: " + operationCount);
      System.out.println("  Successful: " + (operationCount - errorCount.get()));
      System.out.println("  Errors: " + errorCount.get());
      System.out.println("  Validation errors: " + validationErrorCount.get());
      System.out.println("  Duration: " + duration + "ms");
      System.out.println("  Operations/second: " + String.format("%.2f", operationsPerSecond));

      // Verify results
      assertTrue("Test should complete within timeout", completed);
      assertEquals("Should have no errors", 0, errorCount.get());
      assertEquals("Content validation should pass for all blobs", 0, validationErrorCount.get());

      // Verify throughput meets minimum expectations (at least 500 ops/sec for reads)
      assertThat(operationsPerSecond, greaterThan(500.0));
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(1, MINUTES);
    }
  }

  /**
   * Tests the deletion of a large number of blobs concurrently using virtual threads.
   * <p>
   * This test verifies that the S3BlobStore can handle extreme concurrency for blob deletion
   * operations using virtual threads, maintaining correctness and stability under load.
   * </p>
   */
  @Test
  public void testMassiveConcurrentBlobDeletion() throws Exception {
    // Skip test if virtual threads are not enabled
    if (!S3VirtualThreadTestSupport.isVirtualThreadTestingEnabled()) {
      System.out.println("Skipping testMassiveConcurrentBlobDeletion - virtual threads not enabled");
      return;
    }

    int operationCount = STRESS_TEST_OPERATIONS;
    System.out.println("Creating " + operationCount + " test blobs for deletion test");

    // Create blobs to delete
    List<BlobId> blobIds = new ArrayList<>(operationCount);
    for (int i = 0; i < operationCount; i++) {
      String blobName = "deletion-test-blob-" + i;
      Blob blob = createTestBlob(blobStore, blobName, DEFAULT_CONTENT_SIZE);
      blobIds.add(blob.getId());
    }

    System.out.println("Starting massive concurrent blob deletion test with " + operationCount + " operations");

    // Create executor with virtual threads
    ExecutorService executor = S3VirtualThreadTestSupport.createVirtualThreadExecutor("blob-deletion-test");

    try {
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicInteger deletionFailureCount = new AtomicInteger(0);
      AtomicReference<Throwable> firstError = new AtomicReference<>();

      // Record initial memory usage
      long initialMemoryUsage = memoryUsageBytes.get();

      // Start timer
      long startTime = System.currentTimeMillis();

      // Submit blob deletion tasks
      for (int i = 0; i < operationCount; i++) {
        final BlobId blobId = blobIds.get(i);
        executor.submit(() -> {
          try {
            // Delete blob
            boolean deleted = blobStore.delete(blobId, "Stress test deletion");
            if (!deleted) {
              deletionFailureCount.incrementAndGet();
            }
          }
          catch (Throwable t) {
            errorCount.incrementAndGet();
            if (firstError.get() == null) {
              firstError.set(t);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete or timeout
      boolean completed = latch.await(STRESS_TEST_TIMEOUT_SECONDS, SECONDS);

      // Calculate metrics
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;
      double operationsPerSecond = (double) (operationCount - errorCount.get()) / (duration / 1000.0);
      long memoryFreed = initialMemoryUsage - memoryUsageBytes.get();

      // Print results
      System.out.println("Massive concurrent blob deletion test results:");
      System.out.println("  Completed: " + completed);
      System.out.println("  Operations: " + operationCount);
      System.out.println("  Successful: " + (operationCount - errorCount.get() - deletionFailureCount.get()));
      System.out.println("  Errors: " + errorCount.get());
      System.out.println("  Deletion failures: " + deletionFailureCount.get());
      System.out.println("  Duration: " + duration + "ms");
      System.out.println("  Operations/second: " + String.format("%.2f", operationsPerSecond));
      System.out.println("  Memory freed: " + formatBytes(memoryFreed));

      // Verify results
      assertTrue("Test should complete within timeout", completed);
      assertEquals("Should have no errors", 0, errorCount.get());
      assertEquals("All deletions should succeed", 0, deletionFailureCount.get());

      // Verify memory was freed (should be close to the size of all blobs)
      long expectedMemoryFreed = (long) operationCount * DEFAULT_CONTENT_SIZE;
      assertThat(memoryFreed, greaterThan(expectedMemoryFreed * 9 / 10)); // Allow for 10% margin

      // Verify throughput meets minimum expectations (at least 200 ops/sec)
      assertThat(operationsPerSecond, greaterThan(200.0));
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(1, MINUTES);
    }
  }

  /**
   * Tests mixed operations (create, get, delete) under sustained high load using virtual threads.
   * <p>
   * This test verifies that the S3BlobStore can handle a mix of different operations concurrently
   * using virtual threads, maintaining correctness and stability under sustained load.
   * </p>
   */
  @Test
  public void testMixedOperationsUnderSustainedLoad() throws Exception {
    // Skip test if virtual threads are not enabled
    if (!S3VirtualThreadTestSupport.isVirtualThreadTestingEnabled()) {
      System.out.println("Skipping testMixedOperationsUnderSustainedLoad - virtual threads not enabled");
      return;
    }

    System.out.println("Starting mixed operations under sustained load test");
    System.out.println("Test will run for " + SUSTAINED_LOAD_DURATION_SECONDS + " seconds");

    // Create executor with virtual threads
    ExecutorService executor = S3VirtualThreadTestSupport.createVirtualThreadExecutor("mixed-operations-test");

    try {
      // Shared state for test
      Map<BlobId, byte[]> knownBlobs = new ConcurrentHashMap<>();
      AtomicInteger createCount = new AtomicInteger(0);
      AtomicInteger getCount = new AtomicInteger(0);
      AtomicInteger deleteCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicInteger validationErrorCount = new AtomicInteger(0);

      // Create some initial blobs
      int initialBlobCount = 100;
      for (int i = 0; i < initialBlobCount; i++) {
        String blobName = "mixed-test-initial-blob-" + i;
        Blob blob = createTestBlob(blobStore, blobName, DEFAULT_CONTENT_SIZE);
        try (InputStream is = blob.getInputStream()) {
          knownBlobs.put(blob.getId(), is.readAllBytes());
        }
      }

      // Start timer
      long startTime = System.currentTimeMillis();
      long endTime = startTime + (SUSTAINED_LOAD_DURATION_SECONDS * 1000L);

      // Number of threads to run concurrently
      int threadCount = EXTREME_CONCURRENCY;
      CountDownLatch completionLatch = new CountDownLatch(threadCount);

      // Submit worker threads
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Each thread runs until the test duration is reached
            while (System.currentTimeMillis() < endTime && !Thread.currentThread().isInterrupted()) {
              // Determine operation type based on thread ID to ensure a good mix
              // - 60% gets, 30% creates, 10% deletes
              int operationType = threadId % 10;
              if (operationType < 6) {
                // GET operation
                if (!knownBlobs.isEmpty()) {
                  try {
                    // Select a random known blob
                    BlobId blobId = knownBlobs.keySet().stream()
                        .skip((int) (Math.random() * knownBlobs.size()))
                        .findFirst()
                        .orElse(null);

                    if (blobId != null) {
                      byte[] expectedContent = knownBlobs.get(blobId);
                      if (expectedContent != null) {
                        Blob blob = blobStore.get(blobId);
                        if (blob != null) {
                          try (InputStream is = blob.getInputStream()) {
                            byte[] actualContent = is.readAllBytes();
                            if (!java.util.Arrays.equals(expectedContent, actualContent)) {
                              validationErrorCount.incrementAndGet();
                            }
                          }
                          getCount.incrementAndGet();
                        }
                      }
                    }
                  }
                  catch (Exception e) {
                    errorCount.incrementAndGet();
                  }
                }
              }
              else if (operationType < 9) {
                // CREATE operation
                try {
                  String blobName = "mixed-test-blob-" + UUID.randomUUID();
                  Blob blob = createTestBlob(blobStore, blobName, DEFAULT_CONTENT_SIZE);
                  try (InputStream is = blob.getInputStream()) {
                    knownBlobs.put(blob.getId(), is.readAllBytes());
                  }
                  createCount.incrementAndGet();
                }
                catch (Exception e) {
                  errorCount.incrementAndGet();
                }
              }
              else {
                // DELETE operation
                if (!knownBlobs.isEmpty()) {
                  try {
                    // Select a random known blob
                    BlobId blobId = knownBlobs.keySet().stream()
                        .skip((int) (Math.random() * knownBlobs.size()))
                        .findFirst()
                        .orElse(null);

                    if (blobId != null) {
                      boolean deleted = blobStore.delete(blobId, "Mixed operations test");
                      if (deleted) {
                        knownBlobs.remove(blobId);
                        deleteCount.incrementAndGet();
                      }
                    }
                  }
                  catch (Exception e) {
                    errorCount.incrementAndGet();
                  }
                }
              }

              // Small delay to prevent CPU spinning
              if (threadId % 10 == 0) {
                Thread.sleep(1);
              }
            }
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Wait for test duration plus a small grace period
      completionLatch.await(SUSTAINED_LOAD_DURATION_SECONDS + 5, SECONDS);

      // Calculate metrics
      long actualDuration = System.currentTimeMillis() - startTime;
      int totalOperations = createCount.get() + getCount.get() + deleteCount.get();
      double operationsPerSecond = (double) totalOperations / (actualDuration / 1000.0);

      // Print results
      System.out.println("Mixed operations under sustained load test results:");
      System.out.println("  Duration: " + actualDuration + "ms");
      System.out.println("  Total operations: " + totalOperations);
      System.out.println("    Creates: " + createCount.get());
      System.out.println("    Gets: " + getCount.get());
      System.out.println("    Deletes: " + deleteCount.get());
      System.out.println("  Errors: " + errorCount.get());
      System.out.println("  Validation errors: " + validationErrorCount.get());
      System.out.println("  Operations/second: " + String.format("%.2f", operationsPerSecond));
      System.out.println("  Final blob count: " + knownBlobs.size());
      System.out.println("  Memory usage: " + formatBytes(memoryUsageBytes.get()));

      // Verify results
      assertEquals("Should have no validation errors", 0, validationErrorCount.get());
      assertThat("Should have performed a significant number of operations", totalOperations, greaterThan(1000));
      assertThat("Should achieve reasonable throughput", operationsPerSecond, greaterThan(100.0));
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(1, MINUTES);
    }
  }

  /**
   * Compares performance between platform threads and virtual threads under high concurrency.
   * <p>
   * This test measures and compares the performance of S3BlobStore operations using both
   * platform threads and virtual threads under high concurrency conditions.
   * </p>
   */
  @Test
  public void testCompareThreadModelsUnderHighConcurrency() throws Exception {
    // Skip test if virtual threads are not enabled
    if (!S3VirtualThreadTestSupport.isVirtualThreadTestingEnabled()) {
      System.out.println("Skipping testCompareThreadModelsUnderHighConcurrency - virtual threads not enabled");
      return;
    }

    System.out.println("Starting thread model comparison test under high concurrency");

    // Define concurrency levels for testing
    int[] concurrencyLevels = {100, 500, 1000, 5000, 10000};

    // For each concurrency level, compare platform threads vs virtual threads
    for (int concurrency : concurrencyLevels) {
      // Skip higher concurrency levels for platform threads to avoid resource exhaustion
      if (concurrency > 1000 && !S3VirtualThreadTestSupport.isVirtualThreadTestingEnabled()) {
        System.out.println("Skipping concurrency level " + concurrency + " for platform threads");
        continue;
      }

      System.out.println("\nTesting with concurrency level: " + concurrency);

      // Test with platform threads (limited to a reasonable number)
      int platformThreadCount = Math.min(concurrency, PLATFORM_THREAD_COUNT);
      S3VirtualThreadTestSupport.PerformanceMetrics platformMetrics;

      try (ExecutorService platformExecutor = S3VirtualThreadTestSupport.createPlatformThreadExecutor(
          platformThreadCount, "platform-concurrency-test")) {
        System.out.println("Running platform thread test with " + platformThreadCount + " threads");
        platformMetrics = S3VirtualThreadTestSupport.executeConcurrently(
            platformExecutor,
            () -> {
              String blobName = "concurrency-test-blob-" + UUID.randomUUID();
              createTestBlob(blobStore, blobName, DEFAULT_CONTENT_SIZE);
              return null;
            },
            concurrency,
            STRESS_TEST_TIMEOUT_SECONDS);
      }

      // Test with virtual threads
      S3VirtualThreadTestSupport.PerformanceMetrics virtualMetrics;
      try (ExecutorService virtualExecutor = S3VirtualThreadTestSupport.createVirtualThreadExecutor(
          "virtual-concurrency-test")) {
        System.out.println("Running virtual thread test with " + concurrency + " threads");
        virtualMetrics = S3VirtualThreadTestSupport.executeConcurrently(
            virtualExecutor,
            () -> {
              String blobName = "concurrency-test-blob-" + UUID.randomUUID();
              createTestBlob(blobStore, blobName, DEFAULT_CONTENT_SIZE);
              return null;
            },
            concurrency,
            STRESS_TEST_TIMEOUT_SECONDS);
      }

      // Print comparison results
      System.out.println("Results for concurrency level " + concurrency + ":");
      System.out.println("  Platform Threads: " + platformMetrics);
      System.out.println("  Virtual Threads:  " + virtualMetrics);

      // Calculate improvement percentages
      if (platformMetrics.getTotalOperations() > 0 && virtualMetrics.getTotalOperations() > 0) {
        double throughputImprovement = ((virtualMetrics.getOperationsPerSecond() / 
            platformMetrics.getOperationsPerSecond()) - 1) * 100;
        double latencyImprovement = ((platformMetrics.getAverageDurationMs() / 
            virtualMetrics.getAverageDurationMs()) - 1) * 100;

        System.out.printf("  Throughput Improvement: %.2f%%\n", throughputImprovement);
        System.out.printf("  Latency Improvement:    %.2f%%\n", latencyImprovement);

        // For higher concurrency levels, virtual threads should show significant improvement
        if (concurrency >= 1000) {
          assertThat("Virtual threads should provide better throughput at high concurrency",
              throughputImprovement, greaterThan(20.0));
        }
      }
    }
  }

  /**
   * Tests memory consumption patterns with increasing numbers of virtual threads.
   * <p>
   * This test measures memory usage as the number of concurrent virtual threads increases,
   * verifying that memory consumption remains reasonable even with thousands of threads.
   * </p>
   */
  @Test
  public void testMemoryConsumptionWithIncreasingThreads() throws Exception {
    // Skip test if virtual threads are not enabled
    if (!S3VirtualThreadTestSupport.isVirtualThreadTestingEnabled()) {
      System.out.println("Skipping testMemoryConsumptionWithIncreasingThreads - virtual threads not enabled");
      return;
    }

    System.out.println("Starting memory consumption test with increasing thread counts");

    // Define thread count levels for testing
    int[] threadCounts = {100, 500, 1000, 5000, 10000};

    // Track memory usage at each level
    Map<Integer, Long> memoryUsageByThreadCount = new HashMap<>();

    // For each thread count level
    for (int threadCount : threadCounts) {
      System.out.println("\nTesting with " + threadCount + " virtual threads");

      // Reset memory tracking
      System.gc(); // Encourage garbage collection before measurement
      long baselineMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

      // Create and start threads
      ExecutorService executor = S3VirtualThreadTestSupport.createVirtualThreadExecutor("memory-test");
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(threadCount);

      try {
        // Create threads that wait on the start latch
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              startLatch.await(); // Wait for signal to start
              // Perform a small operation to ensure thread is fully initialized
              String blobName = "memory-test-blob-" + UUID.randomUUID();
              createTestBlob(blobStore, blobName, 100); // Smaller blob size for memory test
            }
            catch (Exception e) {
              // Ignore exceptions for this test
            }
            finally {
              completionLatch.countDown();
            }
          });
        }

        // Allow time for thread creation
        Thread.sleep(1000);

        // Measure memory after thread creation but before execution
        long memoryAfterCreation = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long threadCreationOverhead = memoryAfterCreation - baselineMemory;

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        completionLatch.await(STRESS_TEST_TIMEOUT_SECONDS, SECONDS);

        // Measure peak memory during execution
        long peakMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long executionOverhead = peakMemory - memoryAfterCreation;

        // Store results
        memoryUsageByThreadCount.put(threadCount, threadCreationOverhead + executionOverhead);

        // Print results
        System.out.println("Memory usage with " + threadCount + " virtual threads:");
        System.out.println("  Thread creation overhead: " + formatBytes(threadCreationOverhead));
        System.out.println("  Execution overhead:      " + formatBytes(executionOverhead));
        System.out.println("  Total overhead:          " + formatBytes(threadCreationOverhead + executionOverhead));
        System.out.println("  Per-thread overhead:     " + 
            formatBytes((threadCreationOverhead + executionOverhead) / threadCount));
      }
      finally {
        executor.shutdownNow();
        executor.awaitTermination(1, MINUTES);
      }
    }

    // Analyze results
    System.out.println("\nMemory consumption analysis:");
    for (int i = 1; i < threadCounts.length; i++) {
      int previousCount = threadCounts[i - 1];
      int currentCount = threadCounts[i];
      long previousMemory = memoryUsageByThreadCount.get(previousCount);
      long currentMemory = memoryUsageByThreadCount.get(currentCount);

      double threadCountRatio = (double) currentCount / previousCount;
      double memoryRatio = (double) currentMemory / previousMemory;

      System.out.printf("  Scaling from %d to %d threads (%.1fx):\n", previousCount, currentCount, threadCountRatio);
      System.out.printf("    Memory usage: %s to %s (%.2fx)\n", 
          formatBytes(previousMemory), formatBytes(currentMemory), memoryRatio);
      System.out.printf("    Per-thread overhead: %s to %s\n", 
          formatBytes(previousMemory / previousCount), formatBytes(currentMemory / currentCount));
    }

    // Verify that memory usage scales sub-linearly with thread count
    // (i.e., doubling threads should less than double memory usage)
    long memoryFor100 = memoryUsageByThreadCount.get(100);
    long memoryFor10000 = memoryUsageByThreadCount.get(10000);
    double threadRatio = 10000.0 / 100.0; // 100x
    double memoryRatio = (double) memoryFor10000 / memoryFor100;

    System.out.println("\nOverall scaling from 100 to 10000 threads (100x):");
    System.out.printf("  Memory usage: %s to %s (%.2fx)\n", 
        formatBytes(memoryFor100), formatBytes(memoryFor10000), memoryRatio);

    // Virtual threads should scale much better than platform threads
    assertThat("Memory usage should scale sub-linearly with thread count", 
        memoryRatio, lessThan(threadRatio * 0.2)); // Should use less than 20% of linear scaling
  }

  /**
   * Creates a test blob in the provided BlobStore.
   *
   * @param blobStore BlobStore to create the blob in
   * @param blobName Name of the blob
   * @param contentSize Size of the blob content in bytes
   * @return Created blob
   */
  private Blob createTestBlob(BlobStore blobStore, String blobName, int contentSize) {
    byte[] content = new byte[contentSize];
    // Fill with random data
    for (int i = 0; i < contentSize; i++) {
      content[i] = (byte) (Math.random() * 256);
    }

    Map<String, String> headers = new HashMap<>();
    headers.put(BLOB_NAME_HEADER, blobName);
    headers.put(CONTENT_TYPE_HEADER, "application/octet-stream");
    headers.put(CREATED_BY_HEADER, "S3BlobStoreStressTest");
    headers.put(CREATED_BY_IP_HEADER, "127.0.0.1");

    return blobStore.create(new ByteArrayInputStream(content), headers);
  }

  /**
   * Formats a byte count into a human-readable string.
   *
   * @param bytes Byte count to format
   * @return Formatted string (e.g., "1.23 MB")
   */
  private String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    else if (bytes < 1024 * 1024) {
      return String.format("%.2f KB", bytes / 1024.0);
    }
    else if (bytes < 1024 * 1024 * 1024) {
      return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
    else {
      return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
  }
}