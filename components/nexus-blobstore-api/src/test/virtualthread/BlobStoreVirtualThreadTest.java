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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.google.common.hash.HashCode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the {@link BlobStore} interface methods under Virtual Threads to verify that
 * asynchronous blob operations (create, read, delete) function correctly when executed
 * via Java 21's lightweight thread implementation.
 * 
 * @since 3.60
 */
@Category(Java21TestGroup.class)
public class BlobStoreVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int BLOB_SIZE = 1024; // 1KB
  private static final String TEST_CONTENT = "Test content for virtual thread blob operations";
  
  private ExecutorService virtualThreadExecutor;
  private TestBlobStore blobStore;
  
  @Before
  public void setUp() {
    // Create a virtual thread executor using Java 21's Thread.ofVirtual().factory()
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Initialize the test blob store
    blobStore = new TestBlobStore();
    
    log.info("Test setup complete with Java 21 Virtual Thread executor");
  }
  
  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      boolean terminated = virtualThreadExecutor.awaitTermination(10, TimeUnit.SECONDS);
      log.info("Virtual thread executor shutdown complete, terminated: {}", terminated);
    }
  }
  
  /**
   * Tests concurrent creation of blobs using Virtual Threads.
   * Verifies that all blobs are created successfully and can be retrieved.
   */
  @Test
  public void testConcurrentBlobCreationWithVirtualThreads() throws Exception {
    List<Future<BlobId>> futures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Submit concurrent blob creation tasks
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        // Wait for all threads to start simultaneously
        startLatch.await();
        
        // Create blob with unique content
        String content = TEST_CONTENT + "-" + index;
        Map<String, String> headers = createTestHeaders("blob-" + index);
        
        try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
          Blob blob = blobStore.create(inputStream, headers);
          return blob.getId();
        }
      }));
    }
    
    // Log the thread type to verify we're using virtual threads
    Thread currentThread = Thread.currentThread();
    log.info("Current thread is virtual: {}", currentThread.isVirtual());
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Collect results and verify
    List<BlobId> blobIds = new ArrayList<>();
    for (Future<BlobId> future : futures) {
      BlobId blobId = future.get();
      assertNotNull("Blob ID should not be null", blobId);
      blobIds.add(blobId);
    }
    
    // Verify all blobs can be retrieved
    for (int i = 0; i < blobIds.size(); i++) {
      BlobId blobId = blobIds.get(i);
      Blob blob = blobStore.get(blobId);
      assertNotNull("Blob should exist", blob);
      
      // Verify content
      String expectedContent = TEST_CONTENT + "-" + i;
      String actualContent = readContent(blob);
      assertEquals("Blob content should match", expectedContent, actualContent);
      
      // Verify headers
      Map<String, String> headers = blob.getHeaders();
      assertEquals("Blob name header should match", "blob-" + i, headers.get(BlobStore.BLOB_NAME_HEADER));
      assertEquals("Created by header should match", "virtual-thread-test", headers.get(BlobStore.CREATED_BY_HEADER));
    }
  }
  
  /**
   * Tests concurrent retrieval of blobs using Virtual Threads.
   * Verifies that all blobs can be retrieved concurrently without errors.
   */
  @Test
  public void testConcurrentBlobRetrievalWithVirtualThreads() throws Exception {
    // First create a set of blobs
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      String content = TEST_CONTENT + "-" + i;
      Map<String, String> headers = createTestHeaders("blob-" + i);
      
      try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
        Blob blob = blobStore.create(inputStream, headers);
        blobIds.add(blob.getId());
      }
    }
    
    log.info("Created {} test blobs for concurrent retrieval test", CONCURRENT_OPERATIONS);
    
    // Now retrieve them concurrently using virtual threads
    List<Future<String>> futures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    
    for (int i = 0; i < blobIds.size(); i++) {
      final BlobId blobId = blobIds.get(i);
      final int index = i;
      
      futures.add(virtualThreadExecutor.submit(() -> {
        startLatch.await();
        
        Blob blob = blobStore.get(blobId);
        if (blob == null) {
          return "Blob not found: " + blobId;
        }
        
        String content = readContent(blob);
        String expectedContent = TEST_CONTENT + "-" + index;
        
        if (!expectedContent.equals(content)) {
          return "Content mismatch for blob " + blobId + ": expected '" + expectedContent + "' but got '" + content + "'";
        }
        
        return "success";
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Verify all retrievals were successful
    for (Future<String> future : futures) {
      String result = future.get();
      assertEquals("Blob retrieval should succeed", "success", result);
    }
  }
  
  /**
   * Tests concurrent deletion of blobs using Virtual Threads.
   * Verifies that all blobs are deleted successfully.
   */
  @Test
  public void testConcurrentBlobDeletionWithVirtualThreads() throws Exception {
    // First create a set of blobs
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      String content = TEST_CONTENT + "-" + i;
      Map<String, String> headers = createTestHeaders("blob-" + i);
      
      try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
        Blob blob = blobStore.create(inputStream, headers);
        blobIds.add(blob.getId());
      }
    }
    
    log.info("Created {} test blobs for concurrent deletion test", CONCURRENT_OPERATIONS);
    
    // Now delete them concurrently using virtual threads
    List<Future<Boolean>> futures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    
    for (BlobId blobId : blobIds) {
      futures.add(virtualThreadExecutor.submit(() -> {
        startLatch.await();
        return blobStore.delete(blobId, "Test deletion");
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Verify all deletions were successful
    for (Future<Boolean> future : futures) {
      Boolean result = future.get();
      assertTrue("Blob deletion should succeed", result);
    }
    
    // Verify all blobs are gone
    for (BlobId blobId : blobIds) {
      Blob blob = blobStore.get(blobId);
      assertThat(blob, is(nullValue()));
    }
  }
  
  /**
   * Tests error handling during blob operations with Virtual Threads.
   * Verifies that exceptions are properly propagated.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    // Create a blob that will trigger an error on retrieval
    Map<String, String> headers = createTestHeaders("error-blob");
    headers.put("trigger-error", "true");
    
    BlobId errorBlobId;
    try (InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8))) {
      Blob blob = blobStore.create(inputStream, headers);
      errorBlobId = blob.getId();
    }
    
    log.info("Created test blob with error trigger for exception handling test");
    
    // Try to retrieve the error blob using a virtual thread
    Future<Blob> future = virtualThreadExecutor.submit(() -> blobStore.get(errorBlobId));
    
    try {
      future.get();
      fail("Should have thrown an exception");
    } catch (ExecutionException e) {
      // Verify the exception is of the expected type
      assertTrue("Exception should be BlobStoreException", e.getCause() instanceof BlobStoreException);
      assertEquals("Exception message should match", "Error retrieving blob", e.getCause().getMessage());
      log.info("Successfully caught expected exception: {}", e.getCause().getMessage());
    }
  }
  
  /**
   * Tests mixed operations (create, get, delete) running concurrently with Virtual Threads.
   * Verifies that all operations complete successfully without interference.
   */
  @Test
  public void testMixedOperationsWithVirtualThreads() throws Exception {
    // Create initial blobs
    List<BlobId> initialBlobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS / 2; i++) {
      String content = TEST_CONTENT + "-initial-" + i;
      Map<String, String> headers = createTestHeaders("initial-blob-" + i);
      
      try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
        Blob blob = blobStore.create(inputStream, headers);
        initialBlobIds.add(blob.getId());
      }
    }
    
    log.info("Created {} initial test blobs for mixed operations test", CONCURRENT_OPERATIONS / 2);
    
    // Submit mixed operations
    List<Future<?>> futures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger createCounter = new AtomicInteger();
    AtomicInteger getCounter = new AtomicInteger();
    AtomicInteger deleteCounter = new AtomicInteger();
    
    for (int i = 0; i < CONCURRENT_OPERATIONS * 2; i++) {
      final int index = i;
      
      // Mix of operations: 40% create, 40% get, 20% delete
      if (i % 10 < 4) { // 40% create
        futures.add(virtualThreadExecutor.submit(() -> {
          startLatch.await();
          int opIndex = createCounter.getAndIncrement();
          String content = TEST_CONTENT + "-new-" + opIndex;
          Map<String, String> headers = createTestHeaders("new-blob-" + opIndex);
          
          try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            Blob blob = blobStore.create(inputStream, headers);
            return blob.getId();
          }
        }));
      } else if (i % 10 < 8) { // 40% get
        futures.add(virtualThreadExecutor.submit(() -> {
          startLatch.await();
          int opIndex = getCounter.getAndIncrement() % initialBlobIds.size();
          BlobId blobId = initialBlobIds.get(opIndex);
          Blob blob = blobStore.get(blobId);
          if (blob != null) {
            // Read content to verify it's accessible
            readContent(blob);
          }
          return null;
        }));
      } else { // 20% delete
        futures.add(virtualThreadExecutor.submit(() -> {
          startLatch.await();
          int opIndex = deleteCounter.getAndIncrement() % initialBlobIds.size();
          BlobId blobId = initialBlobIds.get(opIndex);
          return blobStore.delete(blobId, "Mixed operation test");
        }));
      }
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete
    for (Future<?> future : futures) {
      future.get(); // Just ensure no exceptions are thrown
    }
    
    log.info("Completed {} mixed operations with virtual threads", futures.size());
  }
  
  /**
   * Tests that blob headers and attributes are preserved correctly when accessed via Virtual Threads.
   */
  @Test
  public void testBlobHeadersAndAttributesWithVirtualThreads() throws Exception {
    // Create a blob with specific headers
    Map<String, String> headers = new HashMap<>();
    headers.put(BlobStore.BLOB_NAME_HEADER, "attributes-test-blob");
    headers.put(BlobStore.CREATED_BY_HEADER, "virtual-thread-test");
    headers.put(BlobStore.CONTENT_TYPE_HEADER, "text/plain");
    headers.put(BlobStore.CREATED_BY_IP_HEADER, "127.0.0.1");
    headers.put("custom-header-1", "custom-value-1");
    headers.put("custom-header-2", "custom-value-2");
    
    // Create the blob
    BlobId blobId;
    try (InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8))) {
      Blob blob = blobStore.create(inputStream, headers);
      blobId = blob.getId();
    }
    
    log.info("Created test blob with custom headers for attributes test");
    
    // Retrieve and verify headers using virtual threads
    Future<Map<String, String>> headersFuture = virtualThreadExecutor.submit(() -> {
      Blob blob = blobStore.get(blobId);
      return blob.getHeaders();
    });
    
    Map<String, String> retrievedHeaders = headersFuture.get();
    assertEquals("Blob name header should match", "attributes-test-blob", retrievedHeaders.get(BlobStore.BLOB_NAME_HEADER));
    assertEquals("Created by header should match", "virtual-thread-test", retrievedHeaders.get(BlobStore.CREATED_BY_HEADER));
    assertEquals("Content type header should match", "text/plain", retrievedHeaders.get(BlobStore.CONTENT_TYPE_HEADER));
    assertEquals("Created by IP header should match", "127.0.0.1", retrievedHeaders.get(BlobStore.CREATED_BY_IP_HEADER));
    assertEquals("Custom header 1 should match", "custom-value-1", retrievedHeaders.get("custom-header-1"));
    assertEquals("Custom header 2 should match", "custom-value-2", retrievedHeaders.get("custom-header-2"));
    
    // Retrieve and verify attributes using virtual threads
    Future<BlobAttributes> attributesFuture = virtualThreadExecutor.submit(() -> {
      return blobStore.getBlobAttributes(blobId);
    });
    
    BlobAttributes attributes = attributesFuture.get();
    assertNotNull("Attributes should not be null", attributes);
    assertEquals("Blob name in attributes should match", "attributes-test-blob", attributes.getHeaders().get(BlobStore.BLOB_NAME_HEADER));
    
    log.info("Successfully verified blob headers and attributes with virtual threads");
  }
  
  /**
   * Helper method to create test headers for blob creation.
   */
  private Map<String, String> createTestHeaders(String blobName) {
    Map<String, String> headers = new HashMap<>();
    headers.put(BlobStore.BLOB_NAME_HEADER, blobName);
    headers.put(BlobStore.CREATED_BY_HEADER, "virtual-thread-test");
    headers.put(BlobStore.CREATED_BY_IP_HEADER, "127.0.0.1");
    headers.put(BlobStore.CONTENT_TYPE_HEADER, "text/plain");
    return headers;
  }
  
  /**
   * Helper method to read content from a blob.
   */
  private String readContent(Blob blob) throws IOException {
    try (InputStream inputStream = blob.getInputStream()) {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
  
  /**
   * Test implementation of BlobStore for virtual thread testing.
   */
  private static class TestBlobStore implements BlobStore {
    private final Map<String, TestBlob> blobs = new ConcurrentHashMap<>();
    private final Map<String, TestBlobAttributes> attributes = new ConcurrentHashMap<>();
    private final BlobStoreConfiguration configuration = new TestBlobStoreConfiguration();
    
    @Override
    @VirtualThreadFriendly
    public Blob create(InputStream blobData, Map<String, String> headers) {
      try {
        String id = UUID.randomUUID().toString();
        byte[] data = blobData.readAllBytes();
        TestBlob blob = new TestBlob(id, new HashMap<>(headers), data);
        blobs.put(id, blob);
        
        // Create attributes
        TestBlobAttributes blobAttributes = new TestBlobAttributes(headers, data.length);
        attributes.put(id, blobAttributes);
        
        return blob;
      } catch (IOException e) {
        throw new BlobStoreException("Error creating blob", e);
      }
    }
    
    @Override
    @VirtualThreadFriendly
    public Blob create(InputStream blobData, Map<String, String> headers, BlobId blobId) {
      try {
        String id = blobId != null ? blobId.asUniqueString() : UUID.randomUUID().toString();
        byte[] data = blobData.readAllBytes();
        TestBlob blob = new TestBlob(id, new HashMap<>(headers), data);
        blobs.put(id, blob);
        
        // Create attributes
        TestBlobAttributes blobAttributes = new TestBlobAttributes(headers, data.length);
        attributes.put(id, blobAttributes);
        
        return blob;
      } catch (IOException e) {
        throw new BlobStoreException("Error creating blob", e);
      }
    }
    
    @Override
    @VirtualThreadFriendly
    public Blob get(BlobId blobId) {
      return get(blobId, false);
    }
    
    @Override
    @VirtualThreadFriendly
    public Blob get(BlobId blobId, boolean includeDeleted) {
      String id = blobId.asUniqueString();
      TestBlob blob = blobs.get(id);
      
      if (blob != null) {
        // Check if this is an error-triggering blob
        if ("true".equals(blob.getHeaders().get("trigger-error"))) {
          throw new BlobStoreException("Error retrieving blob");
        }
      }
      
      return blob;
    }
    
    @Override
    @VirtualThreadFriendly
    public boolean delete(BlobId blobId, String reason) {
      String id = blobId.asUniqueString();
      TestBlob removed = blobs.remove(id);
      if (removed != null) {
        attributes.remove(id);
        return true;
      }
      return false;
    }
    
    @Override
    @VirtualThreadFriendly
    public boolean deleteHard(BlobId blobId) {
      return delete(blobId, "Hard delete");
    }
    
    @Override
    @VirtualThreadFriendly
    public BlobAttributes getBlobAttributes(BlobId blobId) {
      return attributes.get(blobId.asUniqueString());
    }
    
    @Override
    @VirtualThreadFriendly
    public void setBlobAttributes(BlobId blobId, BlobAttributes blobAttributes) {
      attributes.put(blobId.asUniqueString(), (TestBlobAttributes) blobAttributes);
    }
    
    @Override
    @VirtualThreadFriendly
    public boolean exists(BlobId blobId) {
      return blobs.containsKey(blobId.asUniqueString());
    }
    
    @Override
    public BlobStoreConfiguration getBlobStoreConfiguration() {
      return configuration;
    }
    
    @Override
    public void init(BlobStoreConfiguration configuration) {
      // No-op for test implementation
    }
    
    @Override
    public void start() {
      // No-op for test implementation
    }
    
    @Override
    public void stop() {
      // No-op for test implementation
    }
    
    @Override
    public BlobStoreMetrics getMetrics() {
      return null; // Not needed for this test
    }
    
    // Implement only the methods needed for the tests
    // Other methods from BlobStore interface are not implemented for simplicity
    
    @Override
    public <B extends BlobStore> BlobStoreMetricsService<B> getMetricsService() {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public Map<OperationType, OperationMetrics> getOperationMetricsByType() {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public Map<OperationType, OperationMetrics> getOperationMetricsDelta() {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public void clearOperationMetrics() {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public void compact(BlobStoreUsageChecker inUseChecker) {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public void deleteTempFiles(Integer daysOlderThan) {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public Stream<BlobId> getBlobIdStream() {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public Stream<BlobId> getBlobIdUpdatedSinceStream(Duration duration) {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public PaginatedResult<BlobId> getBlobIdUpdatedSinceStream(String prefix, OffsetDateTime fromDateTime,
                                                              OffsetDateTime toDateTime, String continuationToken,
                                                              int pageSize) {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public Stream<BlobId> getDirectPathBlobIdStream(String prefix) {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public boolean undelete(BlobStoreUsageChecker inUseChecker, BlobId blobId, BlobAttributes attributes,
                           boolean isDryRun) {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public boolean isStorageAvailable() {
      return true;
    }
    
    @Override
    public boolean isStarted() {
      return true;
    }
    
    @Override
    public boolean isEmpty() {
      return blobs.isEmpty();
    }
    
    @Override
    public void shutdown() {
      // No-op for test implementation
    }
    
    @Override
    public boolean bytesExists(BlobId blobId) {
      return exists(blobId);
    }
    
    @Override
    public boolean isBlobEmpty(BlobId blobId) {
      TestBlob blob = blobs.get(blobId.asUniqueString());
      return blob != null && blob.data.length == 0;
    }
    
    @Override
    public BlobAttributes createBlobAttributesInstance(BlobId blobId, Map<String, String> headers, BlobMetrics metrics) {
      return new TestBlobAttributes(headers, ((TestBlobMetrics) metrics).getContentSize());
    }
    
    @Override
    public Blob copy(BlobId blobId, Map<String, String> headers) {
      TestBlob original = blobs.get(blobId.asUniqueString());
      if (original == null) {
        return null;
      }
      
      String newId = UUID.randomUUID().toString();
      TestBlob copy = new TestBlob(newId, new HashMap<>(headers), original.data);
      blobs.put(newId, copy);
      
      // Create attributes
      TestBlobAttributes blobAttributes = new TestBlobAttributes(headers, original.data.length);
      attributes.put(newId, blobAttributes);
      
      return copy;
    }
    
    @Override
    public RawObjectAccess getRawObjectAccess() {
      throw new UnsupportedOperationException();
    }
  }
  
  /**
   * Test implementation of Blob for virtual thread testing.
   */
  private static class TestBlob implements Blob {
    private final String id;
    private final Map<String, String> headers;
    private final byte[] data;
    
    public TestBlob(String id, Map<String, String> headers, byte[] data) {
      this.id = id;
      this.headers = headers;
      this.data = data;
    }
    
    @Override
    public BlobId getId() {
      return new BlobId(id);
    }
    
    @Override
    public Map<String, String> getHeaders() {
      return new HashMap<>(headers);
    }
    
    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(data);
    }
    
    @Override
    public BlobMetrics getMetrics() {
      return new TestBlobMetrics(data.length);
    }
  }
  
  /**
   * Test implementation of BlobMetrics for virtual thread testing.
   */
  private static class TestBlobMetrics implements BlobMetrics {
    private final long contentSize;
    
    public TestBlobMetrics(long contentSize) {
      this.contentSize = contentSize;
    }
    
    @Override
    public long getContentSize() {
      return contentSize;
    }
    
    @Override
    public String getSHA1Hash() {
      return "test-sha1";
    }
    
    @Override
    public String getContentType() {
      return "text/plain";
    }
    
    @Override
    public OffsetDateTime getCreationTime() {
      return OffsetDateTime.now();
    }
    
    @Override
    public String getCreatedBy() {
      return "virtual-thread-test";
    }
    
    @Override
    public String getCreatedByIp() {
      return "127.0.0.1";
    }
  }
  
  /**
   * Test implementation of BlobAttributes for virtual thread testing.
   */
  private static class TestBlobAttributes implements BlobAttributes {
    private final Map<String, String> headers;
    private final TestBlobMetrics metrics;
    
    public TestBlobAttributes(Map<String, String> headers, long contentSize) {
      this.headers = new HashMap<>(headers);
      this.metrics = new TestBlobMetrics(contentSize);
    }
    
    @Override
    public Map<String, String> getHeaders() {
      return new HashMap<>(headers);
    }
    
    @Override
    public void updateFrom(BlobAttributes blobAttributes) {
      headers.clear();
      headers.putAll(blobAttributes.getHeaders());
    }
    
    @Override
    public BlobMetrics getMetrics() {
      return metrics;
    }
    
    @Override
    public boolean isDeleted() {
      return false;
    }
    
    @Override
    public void setDeleted(boolean deleted) {
      // No-op for test implementation
    }
    
    @Override
    public String getDeletedReason() {
      return null;
    }
    
    @Override
    public void setDeletedReason(String deletedReason) {
      // No-op for test implementation
    }
    
    @Override
    public void store() {
      // No-op for test implementation
    }
  }
  
  /**
   * Test implementation of BlobStoreConfiguration for virtual thread testing.
   */
  private static class TestBlobStoreConfiguration implements BlobStoreConfiguration {
    @Override
    public String getName() {
      return "test-blob-store";
    }
    
    @Override
    public String getType() {
      return "test";
    }
    
    @Override
    public Map<String, Map<String, Object>> getAttributes() {
      return new HashMap<>();
    }
    
    @Override
    public boolean isWritable() {
      return true;
    }
    
    @Override
    public void setWritable(boolean writable) {
      // No-op for test implementation
    }
  }
}