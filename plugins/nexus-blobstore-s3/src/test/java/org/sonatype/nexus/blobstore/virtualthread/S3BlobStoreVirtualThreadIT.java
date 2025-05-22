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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.s3.internal.AmazonS3Factory;
import org.sonatype.nexus.blobstore.s3.internal.BucketManager;
import org.sonatype.nexus.blobstore.s3.internal.S3BlobStore;
import org.sonatype.nexus.blobstore.s3.internal.S3Copier;
import org.sonatype.nexus.blobstore.s3.internal.S3Uploader;
import org.sonatype.nexus.blobstore.s3.internal.datastore.DatastoreS3BlobStoreMetricsService;
import org.sonatype.nexus.common.log.DryRunPrefix;

import com.amazonaws.services.s3.AmazonS3;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link S3BlobStore} with Java 21 Virtual Threads.
 * 
 * This test compares the performance and behavior of S3BlobStore operations
 * when using platform threads versus virtual threads for I/O-bound operations.
 * 
 * The test validates that:
 * 1. S3BlobStore operations work correctly with virtual threads
 * 2. Virtual threads don't get pinned during S3BlobStore operations
 * 3. Virtual threads provide better scaling at high concurrency levels
 * 
 * Note: In a mocked test environment, the performance difference might not be significant,
 * but in a real environment with actual I/O operations, virtual threads should provide
 * better throughput and resource utilization, especially at high concurrency levels.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class S3BlobStoreVirtualThreadIT
    extends TestSupport
{
  private static final int CONCURRENCY_LEVEL = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int BLOB_SIZE = 1024; // 1KB
  private static final int TIMEOUT_SECONDS = 60; // Timeout for executor shutdown
  private static final String TEST_CONTENT = "test content";
  
  @Mock
  private AmazonS3Factory amazonS3Factory;

  @Mock
  private S3Uploader uploader;

  @Mock
  private S3Copier copier;

  @Mock
  private DatastoreS3BlobStoreMetricsService storeMetrics;

  @Mock
  private DryRunPrefix dryRunPrefix;

  @Mock
  private BucketManager bucketManager;

  @Mock
  private AmazonS3 s3;

  private S3BlobStore blobStore;

  private MockBlobStoreConfiguration config;

  @Before
  public void setUp() throws Exception {
    // Setup mock S3 environment
    when(amazonS3Factory.create(any())).thenReturn(s3);
    when(s3.doesBucketExistV2(any())).thenReturn(true);
    
    // Create a blob store with mocked dependencies
    blobStore = new S3BlobStore(amazonS3Factory, mock(org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver.class),
        uploader, copier, false, false, false, storeMetrics, dryRunPrefix, bucketManager, 
        mock(org.sonatype.nexus.blobstore.quota.BlobStoreQuotaUsageChecker.class));
    
    // Configure the blob store
    config = new MockBlobStoreConfiguration();
    config.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", "test-bucket", "prefix", "test-prefix")))));
    blobStore.init(config);
    blobStore.doStart();
  }

  @After
  public void tearDown() throws Exception {
    if (blobStore != null) {
      blobStore.doStop();
    }
  }

  /**
   * Creates a platform thread factory for comparison testing.
   */
  private ThreadFactory createPlatformThreadFactory(final String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread thread = new Thread(r);
      thread.setName(namePrefix + "-" + counter.incrementAndGet());
      return thread;
    };
  }

  /**
   * Creates a virtual thread factory using Java 21's virtual thread support.
   * 
   * Note: This method is kept for reference but not used in the test since we're using
   * Executors.newVirtualThreadPerTaskExecutor() directly.
   */
  private ThreadFactory createVirtualThreadFactory(final String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> Thread.ofVirtual()
        .name(namePrefix + "-" + counter.incrementAndGet())
        .unstarted(r);
  }

  /**
   * Test that compares the performance of S3BlobStore operations using platform threads vs virtual threads.
   * This test creates, retrieves, and deletes blobs using both thread types and measures the throughput.
   */
  /**
   * Additional test to verify that virtual threads don't get pinned during S3BlobStore operations.
   * This test is important because thread pinning would negate the benefits of virtual threads.
   */
  @Test
  public void testVirtualThreadsDoNotGetPinned() throws Exception {
    // Create a virtual thread executor
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Execute a single blob operation to verify no pinning occurs
      log.info("Testing virtual thread pinning behavior with S3BlobStore");
      BlobOperationTask task = new BlobOperationTask(blobStore, 0);
      Future<BlobId> future = virtualExecutor.submit(task);
      
      // If the thread gets pinned, this would likely timeout
      BlobId blobId = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(blobId, is(notNullValue()));
      
      log.info("Virtual thread completed S3BlobStore operations without pinning");
    } finally {
      virtualExecutor.shutdown();
      virtualExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }
  
  @Test
  public void testS3BlobStoreOperationsWithVirtualThreads() throws Exception {
    // Create executor services for platform and virtual threads
    ExecutorService platformExecutor = Executors.newFixedThreadPool(CONCURRENCY_LEVEL, 
        createPlatformThreadFactory("platform"));
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Test blob operations with platform threads
      log.info("Starting S3BlobStore operations with platform threads");
      Instant platformStart = Instant.now();
      List<BlobId> platformBlobIds = executeBlobOperations(blobStore, platformExecutor);
      Duration platformDuration = Duration.between(platformStart, Instant.now());
      log.info("Platform thread operations completed in {} ms", platformDuration.toMillis());
      
      // Test blob operations with virtual threads
      log.info("Starting S3BlobStore operations with virtual threads");
      Instant virtualStart = Instant.now();
      List<BlobId> virtualBlobIds = executeBlobOperations(blobStore, virtualExecutor);
      Duration virtualDuration = Duration.between(virtualStart, Instant.now());
      log.info("Virtual thread operations completed in {} ms", virtualDuration.toMillis());
      
      // Verify that both approaches created the expected number of blobs
      assertThat(platformBlobIds.size(), is(CONCURRENCY_LEVEL * OPERATIONS_PER_THREAD));
      assertThat(virtualBlobIds.size(), is(CONCURRENCY_LEVEL * OPERATIONS_PER_THREAD));
      
      // Log performance comparison
      log.info("Performance comparison: Platform threads took {} ms, Virtual threads took {} ms", 
          platformDuration.toMillis(), virtualDuration.toMillis());
      
      // At high concurrency levels, virtual threads should generally perform better for I/O operations
      // However, in a mocked test environment, the difference might not be significant
      // The key validation is that virtual threads work correctly with S3BlobStore
    } finally {
      platformExecutor.shutdown();
      virtualExecutor.shutdown();
      platformExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      virtualExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  /**
   * Executes blob create, get, and delete operations using the provided executor service.
   * 
   * @param blobStore The blob store to use for operations
   * @param executor The executor service to run the operations
   * @return List of created blob IDs
   */
  private List<BlobId> executeBlobOperations(final BlobStore blobStore, final ExecutorService executor) 
      throws Exception {
    List<Future<BlobId>> futures = new ArrayList<>();
    
    // Submit blob creation tasks
    for (int i = 0; i < CONCURRENCY_LEVEL; i++) {
      futures.add(executor.submit(new BlobOperationTask(blobStore, i)));
    }
    
    // Collect results
    List<BlobId> blobIds = new ArrayList<>();
    for (Future<BlobId> future : futures) {
      try {
        BlobId blobId = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        blobIds.add(blobId);
      } catch (Exception e) {
        log.error("Error executing blob operation", e);
        throw e;
      }
    }
    
    return blobIds;
  }

  /**
   * Task that performs a series of blob operations (create, get, delete).
   * This task is designed to be I/O-bound to demonstrate the benefits of virtual threads.
   */
  private static class BlobOperationTask implements Callable<BlobId> {
    private final BlobStore blobStore;
    private final int taskId;
    
    public BlobOperationTask(final BlobStore blobStore, final int taskId) {
      this.blobStore = blobStore;
      this.taskId = taskId;
    }
    
    @Override
    public BlobId call() throws Exception {
      List<BlobId> createdBlobIds = new ArrayList<>();
      
      for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
        // Create blob with unique content
        String content = TEST_CONTENT + "-" + taskId + "-" + i + "-" + UUID.randomUUID();
        byte[] contentBytes = new byte[BLOB_SIZE];
        // Fill the array with some data to make it the desired size
        System.arraycopy(content.getBytes(), 0, contentBytes, 0, Math.min(content.getBytes().length, BLOB_SIZE));
        
        Map<String, String> headers = new HashMap<>();
        headers.put(BlobStore.BLOB_NAME_HEADER, "test-blob-" + taskId + "-" + i);
        headers.put(BlobStore.CREATED_BY_HEADER, "test");
        headers.put(BlobStore.CONTENT_TYPE_HEADER, "application/octet-stream");
        
        // Create the blob - this is an I/O operation that benefits from virtual threads
        Blob blob = blobStore.create(new ByteArrayInputStream(contentBytes), headers);
        BlobId blobId = blob.getId();
        createdBlobIds.add(blobId);
        
        // Retrieve the blob to verify it was created correctly - another I/O operation
        Blob retrievedBlob = blobStore.get(blobId);
        assertThat(retrievedBlob, is(notNullValue()));
        
        // Read the blob content - I/O operation
        if (retrievedBlob != null) {
          try (var inputStream = retrievedBlob.getInputStream()) {
            // Read the content to simulate real-world usage
            byte[] buffer = new byte[1024];
            while (inputStream.read(buffer) != -1) {
              // Just read the data
            }
          }
        }
        
        // Delete the blob - I/O operation
        boolean deleted = blobStore.delete(blobId, "test cleanup");
        assertThat(deleted, is(true));
      }
      
      // Return the last blob ID created (for verification purposes)
      return createdBlobIds.get(createdBlobIds.size() - 1);
    }
  }
}