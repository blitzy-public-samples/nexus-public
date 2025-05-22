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
package org.sonatype.virtualthread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.sonatype.nexus.test.util.Whitebox;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Tests the {@link DefaultBlobStoreUsageChecker} with Java 21 Virtual Threads to verify that
 * blob usage checking operations can efficiently utilize the lightweight threading model.
 *
 * @since 3.60
 */
public class DefaultBlobStoreUsageCheckerVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final String REPO_NAME = "repoName";

  private static final String NODE_ID = "repoName";

  private static final String DEFAULT = "default";

  private static final int CONCURRENT_CHECKS = 1000;

  private static final int BLOB_COUNT = 100;

  @Mock
  RepositoryManager repositoryManager;

  @Mock
  BlobStore blobStore;

  @Mock
  Repository repository;

  @Mock
  BlobStoreConfiguration blobStoreConfiguration;

  @Mock
  AssetBlobStore assetBlobStore;

  @Mock
  ContentFacetSupport contentFacet;

  @Mock
  ContentFacetStores contentFacetStores;

  // Map to hold our mock blobs
  private Map<BlobId, Blob> blobMap = new HashMap<>();

  // Map to track which blobs are referenced
  private Map<String, AssetBlob> assetBlobMap = new HashMap<>();

  // The component under test
  private DefaultBlobStoreUsageChecker underTest;

  @Before
  public void setUp() {
    // Skip test if virtual threads are not supported
    assumeVirtualThreadSupported();

    // Set up the content facet and stores
    Whitebox.setInternalState(contentFacetStores, "assetBlobStore", assetBlobStore);
    when(contentFacet.stores()).thenReturn(contentFacetStores);
    when(contentFacet.nodeName()).thenReturn(NODE_ID);

    // Set up the blob store configuration
    when(blobStoreConfiguration.getName()).thenReturn(DEFAULT);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);

    // Set up the repository manager
    when(repositoryManager.get(REPO_NAME)).thenReturn(repository);
    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);

    // Create test blobs - half will be referenced, half will not
    for (int i = 0; i < BLOB_COUNT; i++) {
      String blobIdString = UUID.randomUUID().toString();
      BlobId blobId = new BlobId(blobIdString);
      Blob blob = createMockBlob(blobId);
      blobMap.put(blobId, blob);

      // Make every other blob referenced
      if (i % 2 == 0) {
        BlobRef blobRef = new BlobRef(NODE_ID, DEFAULT, blobIdString);
        AssetBlob assetBlob = createMockAssetBlob(blobRef);
        assetBlobMap.put(blobIdString, assetBlob);
      }
    }

    // Set up the blob store to return our mock blobs
    when(blobStore.get(any(BlobId.class))).thenAnswer(invocation -> {
      BlobId blobId = invocation.getArgument(0);
      return blobMap.get(blobId);
    });

    // Set up the asset blob store to return our mock asset blobs
    when(assetBlobStore.readAssetBlob(any(BlobRef.class))).thenAnswer(invocation -> {
      BlobRef blobRef = invocation.getArgument(0);
      AssetBlob assetBlob = assetBlobMap.get(blobRef.getBlobId());
      return assetBlob != null ? of(assetBlob) : empty();
    });

    // Create the component under test
    underTest = new DefaultBlobStoreUsageChecker(repositoryManager);
  }

  /**
   * Creates a mock blob with the given ID and standard headers.
   */
  private Blob createMockBlob(BlobId blobId) {
    Blob blob = mock(Blob.class);
    Map<String, String> headers = new HashMap<>();
    headers.put(REPO_NAME_HEADER, REPO_NAME);
    when(blob.getHeaders()).thenReturn(headers);
    when(blob.getId()).thenReturn(blobId);
    return blob;
  }

  /**
   * Creates a mock asset blob with the given blob reference.
   */
  private AssetBlob createMockAssetBlob(BlobRef blobRef) {
    AssetBlob assetBlob = mock(AssetBlob.class);
    when(assetBlob.getBlobRef()).thenReturn(blobRef);
    return assetBlob;
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker correctly identifies referenced blobs
   * when running on a virtual thread.
   */
  @Test
  public void testBlobReferenceCheckOnVirtualThread() throws Exception {
    // Get a referenced blob ID
    BlobId referencedBlobId = blobMap.keySet().stream()
        .filter(id -> assetBlobMap.containsKey(id.asUniqueString()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No referenced blob found"));

    // Run the check on a virtual thread
    Boolean result = callVirtual(() -> underTest.test(blobStore, referencedBlobId, "test-blob"));

    // Verify the result
    assertThat(result, is(true));
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker correctly identifies unreferenced blobs
   * when running on a virtual thread.
   */
  @Test
  public void testUnreferencedBlobCheckOnVirtualThread() throws Exception {
    // Get an unreferenced blob ID
    BlobId unreferencedBlobId = blobMap.keySet().stream()
        .filter(id -> !assetBlobMap.containsKey(id.asUniqueString()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No unreferenced blob found"));

    // Run the check on a virtual thread
    Boolean result = callVirtual(() -> underTest.test(blobStore, unreferencedBlobId, "test-blob"));

    // Verify the result
    assertThat(result, is(false));
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker can handle high concurrency with virtual threads.
   * This test runs many concurrent blob reference checks and verifies that all results are correct.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a list of all blob IDs
    List<BlobId> allBlobIds = new ArrayList<>(blobMap.keySet());
    
    // Create a map to store results
    Map<BlobId, Boolean> results = new ConcurrentHashMap<>();
    
    // Create a countdown latch to wait for all checks to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_CHECKS);
    
    // Create a virtual thread factory
    ThreadFactory threadFactory = virtualThreadFactory("blob-check-");
    
    // Run concurrent checks
    for (int i = 0; i < CONCURRENT_CHECKS; i++) {
      // Select a blob ID (cycling through the available IDs)
      BlobId blobId = allBlobIds.get(i % allBlobIds.size());
      
      // Create a virtual thread to check the blob
      Thread thread = threadFactory.newThread(() -> {
        try {
          // Check if the blob is referenced
          boolean isReferenced = underTest.test(blobStore, blobId, "test-blob");
          // Store the result
          results.put(blobId, isReferenced);
        }
        finally {
          latch.countDown();
        }
      });
      
      // Start the thread
      thread.start();
    }
    
    // Wait for all checks to complete (with timeout)
    assertThat("All concurrent checks should complete in time",
        latch.await(30, SECONDS), is(true));
    
    // Verify results
    for (Map.Entry<BlobId, Boolean> entry : results.entrySet()) {
      BlobId blobId = entry.getKey();
      boolean expectedResult = assetBlobMap.containsKey(blobId.asUniqueString());
      assertThat("Result for blob " + blobId, entry.getValue(), is(expectedResult));
    }
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker performs better with virtual threads than with
   * platform threads when handling many concurrent blob reference checks.
   */
  @Test
  public void testPerformanceComparisonWithPlatformThreads() throws Exception {
    // Create a list of all blob IDs
    List<BlobId> allBlobIds = new ArrayList<>(blobMap.keySet());
    
    // Add a small delay to the asset blob lookup to simulate database access
    doAnswer(new Answer<Object>() {
      @Override
      public Object answer(InvocationOnMock invocation) throws Throwable {
        // Small delay to simulate database access
        Thread.sleep(5);
        return invocation.callRealMethod();
      }
    }).when(assetBlobStore).readAssetBlob(any(BlobRef.class));
    
    // Measure time with platform threads
    long platformThreadTime = measureTimeWithThreadFactory(
        Thread.ofPlatform().factory(), allBlobIds);
    
    // Measure time with virtual threads
    long virtualThreadTime = measureTimeWithThreadFactory(
        Thread.ofVirtual().factory(), allBlobIds);
    
    // Log the results
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be faster for I/O-bound operations
    assertThat("Virtual threads should be faster than platform threads",
        virtualThreadTime, lessThan(platformThreadTime));
  }

  /**
   * Measures the time taken to perform concurrent blob reference checks using the given thread factory.
   */
  private long measureTimeWithThreadFactory(ThreadFactory threadFactory, List<BlobId> blobIds) 
      throws Exception {
    // Number of concurrent checks to perform
    final int concurrentChecks = 100;
    
    // Create a countdown latch to wait for all checks to complete
    CountDownLatch latch = new CountDownLatch(concurrentChecks);
    
    // Record start time
    long startTime = System.currentTimeMillis();
    
    // Run concurrent checks
    for (int i = 0; i < concurrentChecks; i++) {
      // Select a blob ID (cycling through the available IDs)
      BlobId blobId = blobIds.get(i % blobIds.size());
      
      // Create a thread to check the blob
      Thread thread = threadFactory.newThread(() -> {
        try {
          // Check if the blob is referenced
          underTest.test(blobStore, blobId, "test-blob");
        }
        finally {
          latch.countDown();
        }
      });
      
      // Start the thread
      thread.start();
    }
    
    // Wait for all checks to complete
    latch.await();
    
    // Return elapsed time
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker does not cause thread pinning when running
   * on virtual threads. Thread pinning would negate many of the benefits of virtual threads.
   */
  @Test
  public void testNoPinningWithVirtualThreads() throws Exception {
    // Enable thread pinning detection
    ThreadPinningDetector.enableJdkPinningDetection();
    
    try {
      // Get a referenced blob ID
      BlobId blobId = blobMap.keySet().iterator().next();
      
      // Check for thread pinning
      boolean pinningDetected = ThreadPinningDetector.detectThreadPinning(() -> {
        // Run the blob reference check
        underTest.test(blobStore, blobId, "test-blob");
      });
      
      // Verify no pinning was detected
      assertThat("No thread pinning should be detected", pinningDetected, is(false));
    }
    finally {
      // Clean up
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker can handle a large number of concurrent
   * blob reference checks using an executor service with virtual threads.
   */
  @Test
  public void testWithVirtualThreadExecutorService() throws Exception {
    // Create a list of all blob IDs
    List<BlobId> allBlobIds = new ArrayList<>(blobMap.keySet());
    
    // Create an executor service with virtual threads
    ExecutorService executor = newVirtualThreadExecutor("blob-check-executor");
    
    try {
      // Track the number of checks performed
      AtomicInteger checksPerformed = new AtomicInteger(0);
      
      // Submit tasks to the executor
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_CHECKS; i++) {
        // Select a blob ID (cycling through the available IDs)
        BlobId blobId = allBlobIds.get(i % allBlobIds.size());
        
        // Submit a task to check the blob
        futures.add(executor.submit(() -> {
          checksPerformed.incrementAndGet();
          return underTest.test(blobStore, blobId, "test-blob");
        }));
      }
      
      // Wait for all tasks to complete and verify results
      for (int i = 0; i < futures.size(); i++) {
        Future<Boolean> future = futures.get(i);
        BlobId blobId = allBlobIds.get(i % allBlobIds.size());
        boolean expectedResult = assetBlobMap.containsKey(blobId.asUniqueString());
        
        // Get the result (with timeout)
        Boolean result = future.get(10, TimeUnit.SECONDS);
        
        // Verify the result
        assertThat("Result for blob " + blobId, result, is(expectedResult));
      }
      
      // Verify all checks were performed
      assertThat(checksPerformed.get(), equalTo(CONCURRENT_CHECKS));
    }
    finally {
      // Shut down the executor
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}