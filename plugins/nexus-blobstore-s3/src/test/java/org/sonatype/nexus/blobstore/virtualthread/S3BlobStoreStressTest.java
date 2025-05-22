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
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaUsageChecker;
import org.sonatype.nexus.blobstore.s3.internal.AmazonS3Factory;
import org.sonatype.nexus.blobstore.s3.internal.BucketManager;
import org.sonatype.nexus.blobstore.s3.internal.S3BlobStore;
import org.sonatype.nexus.blobstore.s3.internal.S3Copier;
import org.sonatype.nexus.blobstore.s3.internal.S3Uploader;
import org.sonatype.nexus.blobstore.s3.internal.datastore.DatastoreS3BlobStoreMetricsService;
import org.sonatype.nexus.common.log.DryRunPrefix;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.DeleteObjectsResult.DeletedObject;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_IP_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Stress test for {@link S3BlobStore} using Java 21 Virtual Threads to validate behavior under extreme concurrency.
 * 
 * This test creates thousands of virtual threads to perform massive parallel operations (create, retrieve, delete)
 * on the S3BlobStore to verify that it maintains correctness and stability under high load conditions.
 */
public class S3BlobStoreStressTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 5_000;
  private static final int OPERATION_TIMEOUT_SECONDS = 30;
  private static final String TEST_BUCKET_NAME = "test-bucket";
  private static final String TEST_CONTENT = "test content for virtual thread stress test";
  
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
  private AmazonS3 s3;

  private MockedStatic<Regions> regionsMockedStatic;

  private S3BlobStore blobStore;

  private MockBlobStoreConfiguration config;
  
  private final Map<String, byte[]> blobContentStore = new ConcurrentHashMap<>();

  @Before
  public void setUp() {
    regionsMockedStatic = mockStatic(Regions.class);
    Region region = mock(Region.class);
    when(region.getName()).thenReturn("us-east-1");
    regionsMockedStatic.when(Regions::getCurrentRegion).thenReturn(region);
    
    // Create the S3BlobStore with mocked dependencies
    blobStore = new S3BlobStore(amazonS3Factory, new DefaultBlobIdLocationResolver(true), uploader, copier, false,
        false, false, storeMetrics, dryRunPrefix, bucketManager, blobStoreQuotaUsageChecker);
    
    // Configure the blob store
    config = new MockBlobStoreConfiguration();
    config.setAttributes(new HashMap<>(Map.of("s3", new HashMap<>(Map.of("bucket", TEST_BUCKET_NAME, "prefix", "")))));
    
    // Mock the S3 client
    when(amazonS3Factory.create(any())).thenReturn(s3);
    when(s3.doesObjectExist(anyString(), anyString())).thenReturn(true);
    
    // Mock the uploader to store content in our local map
    doAnswer(invocation -> {
      ByteArrayInputStream inputStream = invocation.getArgument(0);
      String key = invocation.getArgument(2);
      byte[] content = inputStream.readAllBytes();
      blobContentStore.put(key, content);
      return null;
    }).when(uploader).upload(any(), eq(TEST_BUCKET_NAME), anyString(), any());
    
    // Mock S3 object retrieval to return content from our local map
    doAnswer(invocation -> {
      String key = invocation.getArgument(1);
      if (key.endsWith(".properties")) {
        return mockS3Object("#Properties\n@BlobStore.blob-name=test\nsize=" + TEST_CONTENT.length());
      } else if (key.endsWith(".bytes") && blobContentStore.containsKey(key)) {
        return mockS3Object(new String(blobContentStore.get(key)));
      } else {
        return mockS3Object(TEST_CONTENT);
      }
    }).when(s3).getObject(eq(TEST_BUCKET_NAME), anyString());
    
    // Mock delete operations
    DeleteObjectsResult deleteResult = mock(DeleteObjectsResult.class);
    when(deleteResult.getDeletedObjects()).thenReturn(List.of(new DeletedObject(), new DeletedObject()));
    when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(deleteResult);
    
    // Initialize the blob store
    blobStore.init(config);
    blobStore.doStart();
  }

  @After
  public void teardown() {
    regionsMockedStatic.close();
    blobContentStore.clear();
  }

  /**
   * Tests the creation of thousands of blobs concurrently using virtual threads.
   * Verifies that all operations complete successfully and the system remains stable.
   */
  @Test
  public void testMassiveConcurrentBlobCreation() throws Exception {
    log.info("Starting massive concurrent blob creation test with {} operations", CONCURRENT_OPERATIONS);
    
    // Track memory usage before the test
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    long initialMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    
    // Create a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Track success and failure counts
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Store created blob IDs
    List<BlobId> createdBlobIds = new ArrayList<>(CONCURRENT_OPERATIONS);
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to create blobs
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create blob with unique content
            String uniqueContent = TEST_CONTENT + "-" + index;
            ByteArrayInputStream inputStream = new ByteArrayInputStream(uniqueContent.getBytes());
            
            // Create headers
            Map<String, String> headers = new HashMap<>();
            headers.put(CREATED_BY_HEADER, "virtual-thread-test");
            headers.put(CREATED_BY_IP_HEADER, "127.0.0.1");
            headers.put(BLOB_NAME_HEADER, "test-blob-" + index + ".txt");
            headers.put(CONTENT_TYPE_HEADER, "text/plain");
            headers.put(REPO_NAME_HEADER, "test-repo");
            
            // Create the blob
            Blob blob = blobStore.create(inputStream, headers);
            
            // Verify the blob was created successfully
            assertThat(blob, notNullValue());
            assertThat(blob.getId(), notNullValue());
            
            // Store the blob ID for later verification
            synchronized (createdBlobIds) {
              createdBlobIds.add(blob.getId());
            }
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error creating blob {}: {}", index, e.getMessage(), e);
            failureCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
      assertTrue("Timed out waiting for blob creation operations to complete", completed);
    }
    
    // Verify results
    log.info("Blob creation test completed: {} successful, {} failed", successCount.get(), failureCount.get());
    assertThat(successCount.get(), is(CONCURRENT_OPERATIONS));
    assertThat(failureCount.get(), is(0));
    
    // Check memory usage after the test
    long finalMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    long memoryDelta = finalMemoryUsage - initialMemoryUsage;
    
    // Log memory usage
    log.info("Memory usage: initial={} bytes, final={} bytes, delta={} bytes", 
        initialMemoryUsage, finalMemoryUsage, memoryDelta);
    
    // Verify memory usage is reasonable (less than 100MB per 1000 operations)
    long expectedMaxMemoryPerOp = 100 * 1024; // 100KB per operation
    long maxExpectedMemory = expectedMaxMemoryPerOp * CONCURRENT_OPERATIONS;
    assertThat("Memory usage should be reasonable for virtual threads", 
        memoryDelta, lessThan(maxExpectedMemory));
    
    // Return created blob IDs for use in other tests
    return createdBlobIds;
  }

  /**
   * Tests the retrieval of thousands of blobs concurrently using virtual threads.
   * Verifies that all operations complete successfully and the system remains stable.
   */
  @Test
  public void testMassiveConcurrentBlobRetrieval() throws Exception {
    log.info("Starting massive concurrent blob retrieval test with {} operations", CONCURRENT_OPERATIONS);
    
    // First create blobs to retrieve
    List<BlobId> blobIds = new ArrayList<>(CONCURRENT_OPERATIONS);
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      String uniqueContent = TEST_CONTENT + "-" + i;
      ByteArrayInputStream inputStream = new ByteArrayInputStream(uniqueContent.getBytes());
      
      Map<String, String> headers = new HashMap<>();
      headers.put(CREATED_BY_HEADER, "virtual-thread-test");
      headers.put(CREATED_BY_IP_HEADER, "127.0.0.1");
      headers.put(BLOB_NAME_HEADER, "test-blob-" + i + ".txt");
      headers.put(CONTENT_TYPE_HEADER, "text/plain");
      headers.put(REPO_NAME_HEADER, "test-repo");
      
      Blob blob = blobStore.create(inputStream, headers);
      blobIds.add(blob.getId());
    }
    
    // Track memory usage before the test
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    long initialMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    
    // Create a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Track success and failure counts
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to retrieve blobs
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Get the blob ID to retrieve
            BlobId blobId = blobIds.get(index % blobIds.size());
            
            // Retrieve the blob
            Blob blob = blobStore.get(blobId);
            
            // Verify the blob was retrieved successfully
            assertThat(blob, notNullValue());
            assertThat(blob.getId(), is(blobId));
            
            // Read the content to verify it's accessible
            byte[] content = blob.getInputStream().readAllBytes();
            assertThat(content.length, greaterThanOrEqualTo(TEST_CONTENT.length()));
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error retrieving blob {}: {}", index, e.getMessage(), e);
            failureCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
      assertTrue("Timed out waiting for blob retrieval operations to complete", completed);
    }
    
    // Verify results
    log.info("Blob retrieval test completed: {} successful, {} failed", successCount.get(), failureCount.get());
    assertThat(successCount.get(), is(CONCURRENT_OPERATIONS));
    assertThat(failureCount.get(), is(0));
    
    // Check memory usage after the test
    long finalMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    long memoryDelta = finalMemoryUsage - initialMemoryUsage;
    
    // Log memory usage
    log.info("Memory usage: initial={} bytes, final={} bytes, delta={} bytes", 
        initialMemoryUsage, finalMemoryUsage, memoryDelta);
    
    // Verify memory usage is reasonable (less than 50MB per 1000 operations)
    long expectedMaxMemoryPerOp = 50 * 1024; // 50KB per operation
    long maxExpectedMemory = expectedMaxMemoryPerOp * CONCURRENT_OPERATIONS;
    assertThat("Memory usage should be reasonable for virtual threads", 
        memoryDelta, lessThan(maxExpectedMemory));
  }

  /**
   * Tests the deletion of thousands of blobs concurrently using virtual threads.
   * Verifies that all operations complete successfully and the system remains stable.
   */
  @Test
  public void testMassiveConcurrentBlobDeletion() throws Exception {
    log.info("Starting massive concurrent blob deletion test with {} operations", CONCURRENT_OPERATIONS);
    
    // First create blobs to delete
    List<BlobId> blobIds = new ArrayList<>(CONCURRENT_OPERATIONS);
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      String uniqueContent = TEST_CONTENT + "-" + i;
      ByteArrayInputStream inputStream = new ByteArrayInputStream(uniqueContent.getBytes());
      
      Map<String, String> headers = new HashMap<>();
      headers.put(CREATED_BY_HEADER, "virtual-thread-test");
      headers.put(CREATED_BY_IP_HEADER, "127.0.0.1");
      headers.put(BLOB_NAME_HEADER, "test-blob-" + i + ".txt");
      headers.put(CONTENT_TYPE_HEADER, "text/plain");
      headers.put(REPO_NAME_HEADER, "test-repo");
      
      Blob blob = blobStore.create(inputStream, headers);
      blobIds.add(blob.getId());
    }
    
    // Track memory usage before the test
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    long initialMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    
    // Create a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Track success and failure counts
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to delete blobs
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Get the blob ID to delete
            BlobId blobId = blobIds.get(index % blobIds.size());
            
            // Delete the blob
            boolean deleted = blobStore.delete(blobId, "virtual-thread-stress-test");
            
            // Verify the blob was deleted successfully
            assertThat(deleted, is(true));
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error deleting blob {}: {}", index, e.getMessage(), e);
            failureCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
      assertTrue("Timed out waiting for blob deletion operations to complete", completed);
    }
    
    // Verify results
    log.info("Blob deletion test completed: {} successful, {} failed", successCount.get(), failureCount.get());
    assertThat(successCount.get(), is(CONCURRENT_OPERATIONS));
    assertThat(failureCount.get(), is(0));
    
    // Check memory usage after the test
    long finalMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    long memoryDelta = finalMemoryUsage - initialMemoryUsage;
    
    // Log memory usage
    log.info("Memory usage: initial={} bytes, final={} bytes, delta={} bytes", 
        initialMemoryUsage, finalMemoryUsage, memoryDelta);
    
    // Verify memory usage is reasonable (less than 20MB per 1000 operations)
    long expectedMaxMemoryPerOp = 20 * 1024; // 20KB per operation
    long maxExpectedMemory = expectedMaxMemoryPerOp * CONCURRENT_OPERATIONS;
    assertThat("Memory usage should be reasonable for virtual threads", 
        memoryDelta, lessThan(maxExpectedMemory));
  }

  /**
   * Tests a mix of create, retrieve, and delete operations performed concurrently using virtual threads.
   * Verifies that all operations complete successfully and the system remains stable.
   */
  @Test
  public void testMixedOperations() throws Exception {
    log.info("Starting mixed operations test with {} total operations", CONCURRENT_OPERATIONS);
    
    // Track memory usage before the test
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    long initialMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    
    // Create a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Track success and failure counts for each operation type
    AtomicInteger createSuccessCount = new AtomicInteger(0);
    AtomicInteger retrieveSuccessCount = new AtomicInteger(0);
    AtomicInteger deleteSuccessCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Store created blob IDs for retrieval and deletion
    List<BlobId> blobIds = new ArrayList<>();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // First create some initial blobs
      int initialBlobs = CONCURRENT_OPERATIONS / 10;
      CountDownLatch initialLatch = new CountDownLatch(initialBlobs);
      
      for (int i = 0; i < initialBlobs; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String uniqueContent = TEST_CONTENT + "-initial-" + index;
            ByteArrayInputStream inputStream = new ByteArrayInputStream(uniqueContent.getBytes());
            
            Map<String, String> headers = new HashMap<>();
            headers.put(CREATED_BY_HEADER, "virtual-thread-test");
            headers.put(CREATED_BY_IP_HEADER, "127.0.0.1");
            headers.put(BLOB_NAME_HEADER, "test-blob-initial-" + index + ".txt");
            headers.put(CONTENT_TYPE_HEADER, "text/plain");
            headers.put(REPO_NAME_HEADER, "test-repo");
            
            Blob blob = blobStore.create(inputStream, headers);
            synchronized (blobIds) {
              blobIds.add(blob.getId());
            }
          } catch (Exception e) {
            log.error("Error creating initial blob {}: {}", index, e.getMessage(), e);
          } finally {
            initialLatch.countDown();
          }
        });
      }
      
      // Wait for initial blobs to be created
      initialLatch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
      
      // Submit mixed operation tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Determine operation type based on index
            int operationType = index % 3; // 0 = create, 1 = retrieve, 2 = delete
            
            switch (operationType) {
              case 0: // Create
                String uniqueContent = TEST_CONTENT + "-mixed-" + index;
                ByteArrayInputStream inputStream = new ByteArrayInputStream(uniqueContent.getBytes());
                
                Map<String, String> headers = new HashMap<>();
                headers.put(CREATED_BY_HEADER, "virtual-thread-test");
                headers.put(CREATED_BY_IP_HEADER, "127.0.0.1");
                headers.put(BLOB_NAME_HEADER, "test-blob-mixed-" + index + ".txt");
                headers.put(CONTENT_TYPE_HEADER, "text/plain");
                headers.put(REPO_NAME_HEADER, "test-repo");
                
                Blob blob = blobStore.create(inputStream, headers);
                assertThat(blob, notNullValue());
                
                synchronized (blobIds) {
                  blobIds.add(blob.getId());
                }
                
                createSuccessCount.incrementAndGet();
                break;
                
              case 1: // Retrieve
                synchronized (blobIds) {
                  if (!blobIds.isEmpty()) {
                    BlobId blobId = blobIds.get(index % blobIds.size());
                    Blob retrievedBlob = blobStore.get(blobId);
                    
                    if (retrievedBlob != null) {
                      byte[] content = retrievedBlob.getInputStream().readAllBytes();
                      assertThat(content.length, greaterThanOrEqualTo(TEST_CONTENT.length()));
                      retrieveSuccessCount.incrementAndGet();
                    }
                  } else {
                    // If no blobs exist yet, create one instead
                    String fallbackContent = TEST_CONTENT + "-fallback-" + index;
                    ByteArrayInputStream fallbackStream = new ByteArrayInputStream(fallbackContent.getBytes());
                    
                    Map<String, String> fallbackHeaders = new HashMap<>();
                    fallbackHeaders.put(CREATED_BY_HEADER, "virtual-thread-test");
                    fallbackHeaders.put(CREATED_BY_IP_HEADER, "127.0.0.1");
                    fallbackHeaders.put(BLOB_NAME_HEADER, "test-blob-fallback-" + index + ".txt");
                    fallbackHeaders.put(CONTENT_TYPE_HEADER, "text/plain");
                    fallbackHeaders.put(REPO_NAME_HEADER, "test-repo");
                    
                    Blob fallbackBlob = blobStore.create(fallbackStream, fallbackHeaders);
                    blobIds.add(fallbackBlob.getId());
                    createSuccessCount.incrementAndGet();
                  }
                }
                break;
                
              case 2: // Delete
                synchronized (blobIds) {
                  if (!blobIds.isEmpty()) {
                    int blobIndex = index % blobIds.size();
                    BlobId blobId = blobIds.get(blobIndex);
                    boolean deleted = blobStore.delete(blobId, "virtual-thread-stress-test");
                    
                    if (deleted) {
                      blobIds.remove(blobIndex);
                      deleteSuccessCount.incrementAndGet();
                    }
                  } else {
                    // If no blobs exist yet, create one instead
                    String fallbackContent = TEST_CONTENT + "-fallback-" + index;
                    ByteArrayInputStream fallbackStream = new ByteArrayInputStream(fallbackContent.getBytes());
                    
                    Map<String, String> fallbackHeaders = new HashMap<>();
                    fallbackHeaders.put(CREATED_BY_HEADER, "virtual-thread-test");
                    fallbackHeaders.put(CREATED_BY_IP_HEADER, "127.0.0.1");
                    fallbackHeaders.put(BLOB_NAME_HEADER, "test-blob-fallback-" + index + ".txt");
                    fallbackHeaders.put(CONTENT_TYPE_HEADER, "text/plain");
                    fallbackHeaders.put(REPO_NAME_HEADER, "test-repo");
                    
                    Blob fallbackBlob = blobStore.create(fallbackStream, fallbackHeaders);
                    blobIds.add(fallbackBlob.getId());
                    createSuccessCount.incrementAndGet();
                  }
                }
                break;
            }
          } catch (Exception e) {
            log.error("Error in mixed operation {}: {}", index, e.getMessage(), e);
            failureCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(OPERATION_TIMEOUT_SECONDS * 2, SECONDS);
      assertTrue("Timed out waiting for mixed operations to complete", completed);
    }
    
    // Verify results
    int totalSuccessCount = createSuccessCount.get() + retrieveSuccessCount.get() + deleteSuccessCount.get();
    log.info("Mixed operations test completed: {} total successful ({} creates, {} retrieves, {} deletes), {} failed",
        totalSuccessCount, createSuccessCount.get(), retrieveSuccessCount.get(), deleteSuccessCount.get(), failureCount.get());
    
    assertThat(totalSuccessCount, is(CONCURRENT_OPERATIONS));
    assertThat(failureCount.get(), is(0));
    
    // Check memory usage after the test
    long finalMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    long memoryDelta = finalMemoryUsage - initialMemoryUsage;
    
    // Log memory usage
    log.info("Memory usage: initial={} bytes, final={} bytes, delta={} bytes", 
        initialMemoryUsage, finalMemoryUsage, memoryDelta);
    
    // Verify memory usage is reasonable (less than 100MB per 1000 operations)
    long expectedMaxMemoryPerOp = 100 * 1024; // 100KB per operation
    long maxExpectedMemory = expectedMaxMemoryPerOp * CONCURRENT_OPERATIONS;
    assertThat("Memory usage should be reasonable for virtual threads", 
        memoryDelta, lessThan(maxExpectedMemory));
  }

  /**
   * Tests the system's ability to handle a sustained high load of operations over time.
   * Verifies that the system remains stable and responsive throughout the test.
   */
  @Test
  public void testSustainedHighLoad() throws Exception {
    log.info("Starting sustained high load test");
    
    // Number of waves of operations to perform
    final int waves = 5;
    final int operationsPerWave = CONCURRENT_OPERATIONS / 5;
    
    // Track overall success and failure counts
    AtomicInteger totalSuccessCount = new AtomicInteger(0);
    AtomicInteger totalFailureCount = new AtomicInteger(0);
    
    // Track memory usage before the test
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    long initialMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    
    // Store created blob IDs
    List<BlobId> blobIds = new ArrayList<>();
    
    // Run multiple waves of operations
    for (int wave = 0; wave < waves; wave++) {
      log.info("Starting wave {} of {}", wave + 1, waves);
      
      // Create a countdown latch for this wave
      CountDownLatch waveLatch = new CountDownLatch(operationsPerWave);
      
      // Track success and failure counts for this wave
      AtomicInteger waveSuccessCount = new AtomicInteger(0);
      AtomicInteger waveFailureCount = new AtomicInteger(0);
      
      // Create a virtual thread per task executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit tasks for this wave
        for (int i = 0; i < operationsPerWave; i++) {
          final int index = i;
          executor.submit(() -> {
            try {
              // Perform a random operation based on the index
              int operationType = (index + wave) % 3; // 0 = create, 1 = retrieve, 2 = delete
              
              switch (operationType) {
                case 0: // Create
                  String uniqueContent = TEST_CONTENT + "-wave-" + wave + "-" + index;
                  ByteArrayInputStream inputStream = new ByteArrayInputStream(uniqueContent.getBytes());
                  
                  Map<String, String> headers = new HashMap<>();
                  headers.put(CREATED_BY_HEADER, "virtual-thread-test");
                  headers.put(CREATED_BY_IP_HEADER, "127.0.0.1");
                  headers.put(BLOB_NAME_HEADER, "test-blob-wave-" + wave + "-" + index + ".txt");
                  headers.put(CONTENT_TYPE_HEADER, "text/plain");
                  headers.put(REPO_NAME_HEADER, "test-repo");
                  
                  Blob blob = blobStore.create(inputStream, headers);
                  assertThat(blob, notNullValue());
                  
                  synchronized (blobIds) {
                    blobIds.add(blob.getId());
                  }
                  break;
                  
                case 1: // Retrieve
                  synchronized (blobIds) {
                    if (!blobIds.isEmpty()) {
                      BlobId blobId = blobIds.get(Math.abs((index + wave) % blobIds.size()));
                      Blob retrievedBlob = blobStore.get(blobId);
                      
                      if (retrievedBlob != null) {
                        byte[] content = retrievedBlob.getInputStream().readAllBytes();
                        assertThat(content.length, greaterThanOrEqualTo(TEST_CONTENT.length()));
                      }
                    }
                  }
                  break;
                  
                case 2: // Delete
                  synchronized (blobIds) {
                    if (!blobIds.isEmpty()) {
                      int blobIndex = Math.abs((index + wave) % blobIds.size());
                      BlobId blobId = blobIds.get(blobIndex);
                      boolean deleted = blobStore.delete(blobId, "virtual-thread-stress-test");
                      
                      if (deleted) {
                        blobIds.remove(blobIndex);
                      }
                    }
                  }
                  break;
              }
              
              waveSuccessCount.incrementAndGet();
            } catch (Exception e) {
              log.error("Error in wave {} operation {}: {}", wave, index, e.getMessage(), e);
              waveFailureCount.incrementAndGet();
            } finally {
              waveLatch.countDown();
            }
          });
        }
        
        // Wait for all operations in this wave to complete or timeout
        boolean completed = waveLatch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
        assertTrue("Timed out waiting for wave " + (wave + 1) + " operations to complete", completed);
      }
      
      // Update total counts
      totalSuccessCount.addAndGet(waveSuccessCount.get());
      totalFailureCount.addAndGet(waveFailureCount.get());
      
      // Log results for this wave
      log.info("Wave {} completed: {} successful, {} failed", 
          wave + 1, waveSuccessCount.get(), waveFailureCount.get());
      
      // Brief pause between waves to allow for garbage collection
      if (wave < waves - 1) {
        Thread.sleep(1000);
      }
    }
    
    // Verify overall results
    log.info("Sustained high load test completed: {} total successful, {} failed",
        totalSuccessCount.get(), totalFailureCount.get());
    
    assertThat(totalSuccessCount.get(), is(waves * operationsPerWave));
    assertThat(totalFailureCount.get(), is(0));
    
    // Check memory usage after the test
    long finalMemoryUsage = memoryBean.getHeapMemoryUsage().getUsed();
    long memoryDelta = finalMemoryUsage - initialMemoryUsage;
    
    // Log memory usage
    log.info("Memory usage: initial={} bytes, final={} bytes, delta={} bytes", 
        initialMemoryUsage, finalMemoryUsage, memoryDelta);
    
    // Verify memory usage is reasonable (less than 100MB per 1000 operations)
    long expectedMaxMemoryPerOp = 100 * 1024; // 100KB per operation
    long maxExpectedMemory = expectedMaxMemoryPerOp * waves * operationsPerWave;
    assertThat("Memory usage should be reasonable for virtual threads", 
        memoryDelta, lessThan(maxExpectedMemory));
  }

  /**
   * Tests the system's ability to handle error conditions under high concurrency.
   * Verifies that errors are properly handled and don't cause system instability.
   */
  @Test
  public void testErrorHandlingUnderLoad() throws Exception {
    log.info("Starting error handling under load test");
    
    // Configure the test to inject errors
    final int totalOperations = CONCURRENT_OPERATIONS;
    final int errorFrequency = 10; // Inject an error every 10 operations
    
    // Create a countdown latch to track completion
    CountDownLatch latch = new CountDownLatch(totalOperations);
    
    // Track success, expected error, and unexpected error counts
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger expectedErrorCount = new AtomicInteger(0);
    AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks
      for (int i = 0; i < totalOperations; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Determine if this operation should generate an error
            boolean shouldError = index % errorFrequency == 0;
            
            if (shouldError) {
              // Simulate an error by trying to access a non-existent blob
              BlobId nonExistentBlobId = new BlobId(UUID.randomUUID().toString());
              
              // Force an exception by mocking a failure
              when(s3.doesObjectExist(anyString(), anyString())).thenThrow(
                  new BlobStoreException("Simulated error for testing", new RuntimeException("Cause")));
              
              try {
                blobStore.get(nonExistentBlobId);
                // Should not reach here
                unexpectedErrorCount.incrementAndGet();
              } catch (BlobStoreException e) {
                // Expected exception
                expectedErrorCount.incrementAndGet();
              } finally {
                // Reset the mock for other operations
                when(s3.doesObjectExist(anyString(), anyString())).thenReturn(true);
              }
            } else {
              // Perform a normal operation
              String uniqueContent = TEST_CONTENT + "-error-test-" + index;
              ByteArrayInputStream inputStream = new ByteArrayInputStream(uniqueContent.getBytes());
              
              Map<String, String> headers = new HashMap<>();
              headers.put(CREATED_BY_HEADER, "virtual-thread-test");
              headers.put(CREATED_BY_IP_HEADER, "127.0.0.1");
              headers.put(BLOB_NAME_HEADER, "test-blob-error-" + index + ".txt");
              headers.put(CONTENT_TYPE_HEADER, "text/plain");
              headers.put(REPO_NAME_HEADER, "test-repo");
              
              Blob blob = blobStore.create(inputStream, headers);
              assertThat(blob, notNullValue());
              
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Unexpected error in operation {}: {}", index, e.getMessage(), e);
            unexpectedErrorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
      assertTrue("Timed out waiting for error handling test operations to complete", completed);
    }
    
    // Verify results
    log.info("Error handling test completed: {} successful, {} expected errors, {} unexpected errors",
        successCount.get(), expectedErrorCount.get(), unexpectedErrorCount.get());
    
    int expectedSuccessCount = totalOperations - (totalOperations / errorFrequency);
    int expectedErrorsCount = totalOperations / errorFrequency;
    
    assertThat(successCount.get(), is(expectedSuccessCount));
    assertThat(expectedErrorCount.get(), is(expectedErrorsCount));
    assertThat(unexpectedErrorCount.get(), is(0));
  }

  /**
   * Creates a mock S3Object with the given content.
   */
  private S3Object mockS3Object(String content) {
    S3Object s3Object = mock(S3Object.class);
    S3ObjectInputStream inputStream = new S3ObjectInputStream(new ByteArrayInputStream(content.getBytes()), null);
    when(s3Object.getObjectContent()).thenReturn(inputStream);
    return s3Object;
  }
}