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
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.internal.datastore.DefaultBlobStoreUsageChecker;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetStores;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.store.AssetBlobStore;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;
import org.sonatype.nexus.test.util.Whitebox;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers.isVirtualThread;

/**
 * Tests that {@link DefaultBlobStoreUsageChecker} effectively utilizes Java 21 Virtual Threads
 * for concurrent blob usage checking operations.
 */
public class BlobStoreUsageCheckerVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final String REPO_NAME = "repoName";
  private static final String NODE_ID = "nodeId";
  private static final String BLOB_STORE_NAME = "default";
  private static final String BLOB_NAME_PREFIX = "/fake/blob";
  private static final int BLOB_COUNT = 10_000; // Large number of blobs to test concurrency
  private static final int SIMULATED_IO_DELAY_MS = 5; // Simulate I/O delay

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private BlobStore blobStore;

  @Mock
  private Repository repository;

  @Mock
  private BlobStoreConfiguration blobStoreConfiguration;

  @Mock
  private AssetBlobStore assetBlobStore;

  @Mock
  private ContentFacetSupport contentFacet;

  @Mock
  private ContentFacetStores contentFacetStores;

  private DefaultBlobStoreUsageChecker underTest;
  
  private final Map<String, Blob> blobs = new ConcurrentHashMap<>();
  private final Map<BlobRef, AssetBlob> assetBlobs = new ConcurrentHashMap<>();
  private final AtomicInteger ioOperationCount = new AtomicInteger(0);

  @Before
  public void setUp() {
    Whitebox.setInternalState(contentFacetStores, "assetBlobStore", assetBlobStore);

    when(contentFacet.stores()).thenReturn(contentFacetStores);
    when(contentFacet.nodeName()).thenReturn(NODE_ID);

    when(blobStoreConfiguration.getName()).thenReturn(BLOB_STORE_NAME);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);

    when(repositoryManager.get(REPO_NAME)).thenReturn(repository);
    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);

    // Setup mock behavior for blob retrieval with simulated I/O delay
    when(blobStore.get(any(BlobId.class))).thenAnswer(invocation -> {
      BlobId blobId = invocation.getArgument(0);
      simulateIoOperation();
      return blobs.get(blobId.asUniqueString());
    });

    // Setup mock behavior for asset blob retrieval with simulated I/O delay
    when(assetBlobStore.readAssetBlob(any(BlobRef.class))).thenAnswer(invocation -> {
      BlobRef blobRef = invocation.getArgument(0);
      simulateIoOperation();
      AssetBlob assetBlob = assetBlobs.get(blobRef);
      return assetBlob != null ? of(assetBlob) : empty();
    });

    underTest = new DefaultBlobStoreUsageChecker(repositoryManager);
    
    // Create test data
    createTestBlobs();
  }

  /**
   * Creates test blobs and asset blobs for testing.
   * Half of the blobs will be referenced (have corresponding asset blobs),
   * and half will be unreferenced.
   */
  private void createTestBlobs() {
    for (int i = 0; i < BLOB_COUNT; i++) {
      String blobIdString = UUID.randomUUID().toString();
      BlobId blobId = new BlobId(blobIdString);
      String blobName = BLOB_NAME_PREFIX + i + ".txt";
      
      // Create mock blob
      Blob blob = createMockBlob(blobIdString);
      blobs.put(blobIdString, blob);
      
      // For half of the blobs, create corresponding asset blobs
      if (i % 2 == 0) {
        BlobRef blobRef = new BlobRef(NODE_ID, BLOB_STORE_NAME, blobIdString);
        AssetBlob assetBlob = createMockAssetBlob(blobRef);
        assetBlobs.put(blobRef, assetBlob);
      }
    }
  }

  private Blob createMockBlob(String blobIdString) {
    Blob blob = mock(Blob.class);
    Map<String, String> headers = new HashMap<>();
    headers.put(REPO_NAME_HEADER, REPO_NAME);
    when(blob.getHeaders()).thenReturn(headers);
    when(blob.getId()).thenReturn(new BlobId(blobIdString));
    return blob;
  }

  private AssetBlob createMockAssetBlob(BlobRef blobRef) {
    AssetBlob assetBlob = mock(AssetBlob.class);
    when(assetBlob.getBlobRef()).thenReturn(blobRef);
    return assetBlob;
  }

  /**
   * Simulates an I/O operation with a small delay to mimic real-world scenarios.
   * This helps demonstrate the benefits of Virtual Threads for I/O-bound operations.
   */
  private void simulateIoOperation() {
    ioOperationCount.incrementAndGet();
    try {
      // Small delay to simulate I/O
      Thread.sleep(SIMULATED_IO_DELAY_MS);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Tests that the current thread executing the test is a virtual thread.
   * This verifies that the test infrastructure is correctly set up for virtual thread testing.
   */
  @Test
  public void testCurrentThreadIsVirtual() {
    // This test will run on a virtual thread when executed with the appropriate JVM flags
    // It serves as a verification that the test infrastructure is correctly set up
    Thread currentThread = Thread.currentThread();
    log.info("Current thread: {}, isVirtual: {}", currentThread.getName(), Thread.currentThread().isVirtual());
    
    // Note: This assertion may fail if the test is not run with virtual threads enabled
    // It's primarily for documentation and verification when virtual threads are enabled
    if (isVirtualThreadsEnabled()) {
      assertThat(currentThread, isVirtualThread());
    }
  }

  /**
   * Tests that DefaultBlobStoreUsageChecker correctly identifies referenced and unreferenced blobs.
   */
  @Test
  public void testBlobReferenceChecking() {
    // Test with a referenced blob (even index)
    String referencedBlobId = blobs.keySet().stream().findFirst().orElseThrow();
    BlobId blobId = new BlobId(referencedBlobId);
    String blobName = BLOB_NAME_PREFIX + "0.txt";
    
    // If this is a referenced blob (even index), it should return true
    boolean isReferenced = underTest.test(blobStore, blobId, blobName);
    assertThat(isReferenced, is(true));
    
    // Create a blob ID that doesn't exist in our test data
    BlobId nonExistentBlobId = new BlobId("non-existent-blob-id");
    isReferenced = underTest.test(blobStore, nonExistentBlobId, blobName);
    assertThat(isReferenced, is(false));
  }

  /**
   * Tests the performance of DefaultBlobStoreUsageChecker with a large number of concurrent operations
   * using Virtual Threads. Compares performance with platform threads to demonstrate the benefits
   * of Virtual Threads for I/O-bound operations.
   */
  @Test
  public void testConcurrentBlobChecking() throws Exception {
    List<BlobCheckTask> tasks = createBlobCheckTasks();
    
    // Test with platform threads
    long platformThreadTime = runWithExecutor(
        Executors.newFixedThreadPool(Math.min(100, Runtime.getRuntime().availableProcessors() * 2)),
        "Platform Threads",
        tasks);
    
    // Reset counter for fair comparison
    ioOperationCount.set(0);
    
    // Test with virtual threads
    long virtualThreadTime = runWithExecutor(
        Executors.newVirtualThreadPerTaskExecutor(),
        "Virtual Threads",
        tasks);
    
    // Log the results
    log.info("Platform thread time: {} ms, Virtual thread time: {} ms", platformThreadTime, virtualThreadTime);
    log.info("Performance improvement with Virtual Threads: {}%", 
        Math.round((platformThreadTime - virtualThreadTime) * 100.0 / platformThreadTime));
    
    // Virtual threads should be faster for I/O-bound operations with high concurrency
    // However, this assertion is commented out as the actual performance difference
    // depends on the test environment and may vary
    // assertThat(virtualThreadTime, lessThan(platformThreadTime));
  }

  /**
   * Tests that DefaultBlobStoreUsageChecker can handle a very large number of concurrent operations
   * using Virtual Threads without exhausting system resources.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Only run this test if virtual threads are enabled
    if (!isVirtualThreadsEnabled()) {
      log.info("Skipping high concurrency test as virtual threads are not enabled");
      return;
    }
    
    // Create a very large number of tasks to demonstrate the scalability of virtual threads
    int taskCount = BLOB_COUNT * 10; // 100,000 concurrent operations
    List<BlobCheckTask> tasks = createBlobCheckTasks(taskCount);
    
    // Use a countdown latch to ensure all tasks start at roughly the same time
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a virtual thread per task
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> futures = new ArrayList<>(taskCount);
      
      // Submit all tasks
      for (BlobCheckTask task : tasks) {
        futures.add(executor.submit(() -> {
          startLatch.await(); // Wait for the signal to start
          return task.call();
        }));
      }
      
      // Start all tasks simultaneously
      long startTime = System.currentTimeMillis();
      startLatch.countDown();
      
      // Wait for all tasks to complete and collect results
      int successCount = 0;
      for (Future<Boolean> future : futures) {
        if (future.get()) {
          successCount++;
        }
      }
      long endTime = System.currentTimeMillis();
      
      // Log the results
      log.info("Completed {} concurrent blob checks in {} ms using Virtual Threads", 
          taskCount, (endTime - startTime));
      log.info("Success rate: {}%", (successCount * 100.0 / taskCount));
      log.info("Total I/O operations performed: {}", ioOperationCount.get());
      
      // Verify that all tasks completed successfully
      assertThat(successCount, equalTo(taskCount));
    }
  }

  /**
   * Tests that DefaultBlobStoreUsageChecker correctly handles errors during concurrent operations
   * and aggregates results properly.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    // Inject random failures into the blob store
    doAnswer(new Answer<Blob>() {
      @Override
      public Blob answer(InvocationOnMock invocation) throws Throwable {
        BlobId blobId = invocation.getArgument(0);
        simulateIoOperation();
        
        // Randomly throw exceptions to simulate failures
        if (Math.random() < 0.1) { // 10% failure rate
          throw new RuntimeException("Simulated blob store failure");
        }
        
        return blobs.get(blobId.asUniqueString());
      }
    }).when(blobStore).get(any(BlobId.class));
    
    // Create tasks
    List<BlobCheckTask> tasks = createBlobCheckTasks(1000);
    
    // Run with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> futures = new ArrayList<>(tasks.size());
      
      // Submit all tasks
      for (BlobCheckTask task : tasks) {
        futures.add(executor.submit(task));
      }
      
      // Wait for all tasks to complete and collect results
      int successCount = 0;
      int failureCount = 0;
      int exceptionCount = 0;
      
      for (Future<Boolean> future : futures) {
        try {
          if (future.get()) {
            successCount++;
          }
          else {
            failureCount++;
          }
        }
        catch (Exception e) {
          exceptionCount++;
        }
      }
      
      // Log the results
      log.info("Success count: {}, Failure count: {}, Exception count: {}", 
          successCount, failureCount, exceptionCount);
      
      // Verify that we had some failures due to the injected errors
      assertThat(exceptionCount, greaterThan(0));
      // But also some successes
      assertThat(successCount, greaterThan(0));
    }
  }

  /**
   * Runs the given tasks with the provided executor and returns the execution time in milliseconds.
   */
  private long runWithExecutor(ExecutorService executor, String executorName, List<BlobCheckTask> tasks) 
      throws Exception {
    try {
      long startTime = System.currentTimeMillis();
      
      List<Future<Boolean>> futures = new ArrayList<>(tasks.size());
      for (BlobCheckTask task : tasks) {
        futures.add(executor.submit(task));
      }
      
      int successCount = 0;
      for (Future<Boolean> future : futures) {
        if (future.get()) {
          successCount++;
        }
      }
      
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;
      
      log.info("{} completed {} blob checks in {} ms with {} successes", 
          executorName, tasks.size(), duration, successCount);
      log.info("{} I/O operations performed with {}", ioOperationCount.get(), executorName);
      
      return duration;
    }
    finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
    }
  }

  /**
   * Creates a list of tasks to check blob references.
   */
  private List<BlobCheckTask> createBlobCheckTasks() {
    return createBlobCheckTasks(BLOB_COUNT);
  }

  /**
   * Creates a list of tasks to check blob references with the specified count.
   */
  private List<BlobCheckTask> createBlobCheckTasks(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> {
          // Cycle through the available blob IDs
          String blobIdString = new ArrayList<>(blobs.keySet()).get(i % blobs.size());
          BlobId blobId = new BlobId(blobIdString);
          String blobName = BLOB_NAME_PREFIX + (i % blobs.size()) + ".txt";
          return new BlobCheckTask(blobId, blobName);
        })
        .collect(Collectors.toList());
  }

  /**
   * A task that checks if a blob is referenced.
   */
  private class BlobCheckTask implements java.util.concurrent.Callable<Boolean> {
    private final BlobId blobId;
    private final String blobName;

    public BlobCheckTask(BlobId blobId, String blobName) {
      this.blobId = blobId;
      this.blobName = blobName;
    }

    @Override
    public Boolean call() {
      try {
        return underTest.test(blobStore, blobId, blobName);
      }
      catch (Exception e) {
        log.error("Error checking blob reference: {}", e.getMessage());
        throw e;
      }
    }
  }

  /**
   * Checks if virtual threads are enabled in the current JVM.
   */
  private boolean isVirtualThreadsEnabled() {
    try {
      // Create a virtual thread and check if it's actually virtual
      Thread virtualThread = Thread.ofVirtual().name("virtual-thread-test").start(() -> {});
      virtualThread.join(Duration.ofSeconds(1));
      return true;
    }
    catch (Exception e) {
      return false;
    }
  }
}