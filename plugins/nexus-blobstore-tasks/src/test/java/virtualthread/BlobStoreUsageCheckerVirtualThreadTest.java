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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
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

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Test class to validate that {@link DefaultBlobStoreUsageChecker} effectively utilizes Java 21 Virtual Threads
 * for concurrent blob usage checking operations.
 * 
 * This test verifies that the checker can efficiently process large volumes of blob usage checks simultaneously
 * without thread resource exhaustion. It simulates high-concurrency scenarios with multiple blob stores and repositories,
 * measures performance improvements when using Virtual Threads compared to platform threads, and ensures proper
 * handling of I/O operations during blob usage checking.
 */
@Category(VirtualThreadTestGroup.class)
public class BlobStoreUsageCheckerVirtualThreadTest
    extends TestSupport
{
  private static final String REPO_NAME = "repoName";
  private static final String NODE_ID = "repoName";
  private static final String DEFAULT = "default";
  private static final String BLOB_NAME = "/fake/blob.name";

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

  // Number of concurrent tasks to run
  private static final int TASK_COUNT = 1000;

  // Number of blobs to check in each task
  private static final int BLOBS_PER_TASK = 10;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    
    Whitebox.setInternalState(contentFacetStores, "assetBlobStore", assetBlobStore);

    when(contentFacet.stores()).thenReturn(contentFacetStores);
    when(contentFacet.nodeName()).thenReturn(NODE_ID);

    when(blobStoreConfiguration.getName()).thenReturn(DEFAULT);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);

    when(repositoryManager.get(REPO_NAME)).thenReturn(repository);
    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);

    underTest = new DefaultBlobStoreUsageChecker(repositoryManager);
  }

  /**
   * Tests the performance and behavior of DefaultBlobStoreUsageChecker when using Virtual Threads
   * for concurrent blob usage checking operations.
   * 
   * This test simulates a high-concurrency scenario with multiple threads simultaneously checking
   * blob usage across repositories. It verifies that Virtual Threads can efficiently handle
   * a large number of concurrent operations without exhausting system resources.
   */
  @Test
  public void testConcurrentBlobUsageCheckingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(TASK_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Set up mock blobs and their references
    setupMockBlobs();
    
    long startTime = System.currentTimeMillis();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < TASK_COUNT; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Check multiple blobs in each task
            for (int j = 0; j < BLOBS_PER_TASK; j++) {
              BlobId blobId = new BlobId(UUID.randomUUID().toString());
              // Set up this specific blob to be found
              setupMockBlob(blobId);
              // Check if the blob is in use
              boolean result = underTest.test(blobStore, blobId, BLOB_NAME);
              if (!result) {
                errorCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            log.error("Error in task {}", taskId, e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      long duration = System.currentTimeMillis() - startTime;
      log.info("Virtual Thread test completed in {} ms", duration);
      
      // Verify all tasks completed successfully
      assertThat("All tasks should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent blob checking", errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Compares the performance of Virtual Threads versus Platform Threads for concurrent
   * blob usage checking operations.
   * 
   * This test runs the same workload using both thread types and measures the execution time
   * to demonstrate the efficiency gains from using Virtual Threads for I/O-bound operations.
   */
  @Test
  public void compareVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    // Run with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    long virtualThreadTime = runConcurrentBlobChecks(virtualThreadFactory, "Virtual Threads");
    
    // Run with platform threads
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    long platformThreadTime = runConcurrentBlobChecks(platformThreadFactory, "Platform Threads");
    
    log.info("Performance comparison: Virtual Threads: {} ms, Platform Threads: {} ms", 
        virtualThreadTime, platformThreadTime);
    
    // Log the performance difference
    if (platformThreadTime > virtualThreadTime) {
      double improvement = (double) (platformThreadTime - virtualThreadTime) / platformThreadTime * 100.0;
      log.info("Virtual Threads were {}% faster than Platform Threads", String.format("%.2f", improvement));
    } else {
      double difference = (double) (virtualThreadTime - platformThreadTime) / platformThreadTime * 100.0;
      log.info("Platform Threads were {}% faster than Virtual Threads", String.format("%.2f", difference));
    }
    
    // We don't assert on the actual performance difference as it can vary by environment,
    // but we log it for analysis. In most I/O-bound scenarios, Virtual Threads should show
    // better performance at high concurrency levels.
  }

  /**
   * Tests the behavior of DefaultBlobStoreUsageChecker with a very high number of Virtual Threads
   * to verify scalability under extreme concurrency.
   * 
   * This test creates a large number of Virtual Threads to simulate a high-load scenario
   * and verifies that the system can handle it without resource exhaustion.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Use a higher number of tasks for this test to demonstrate Virtual Thread scalability
    final int highConcurrencyTaskCount = 5000;
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(highConcurrencyTaskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Set up mock blobs
    setupMockBlobs();
    
    long startTime = System.currentTimeMillis();
    
    try {
      // Submit a very high number of concurrent tasks
      for (int i = 0; i < highConcurrencyTaskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            BlobId blobId = new BlobId(UUID.randomUUID().toString());
            setupMockBlob(blobId);
            boolean result = underTest.test(blobStore, blobId, BLOB_NAME);
            if (result) {
              successCount.incrementAndGet();
            } else {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in high concurrency task {}", taskId, e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a longer timeout due to the higher task count
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      
      long duration = System.currentTimeMillis() - startTime;
      log.info("High concurrency test with {} tasks completed in {} ms", 
          highConcurrencyTaskCount, duration);
      log.info("Success count: {}, Error count: {}", successCount.get(), errorCount.get());
      
      // Verify all tasks completed successfully
      assertThat("All high concurrency tasks should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during high concurrency blob checking", errorCount.get(), is(0));
      assertThat("All blob checks should succeed", successCount.get(), equalTo(highConcurrencyTaskCount));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to run concurrent blob checks with the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (virtual or platform)
   * @param threadType A descriptive name for the thread type being used
   * @return The execution time in milliseconds
   */
  private long runConcurrentBlobChecks(ThreadFactory threadFactory, String threadType) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    CountDownLatch latch = new CountDownLatch(TASK_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Set up mock blobs
    setupMockBlobs();
    
    long startTime = System.currentTimeMillis();
    
    try {
      // Submit concurrent tasks
      for (int i = 0; i < TASK_COUNT; i++) {
        executor.submit(() -> {
          try {
            // Check multiple blobs in each task
            for (int j = 0; j < BLOBS_PER_TASK; j++) {
              BlobId blobId = new BlobId(UUID.randomUUID().toString());
              setupMockBlob(blobId);
              boolean result = underTest.test(blobStore, blobId, BLOB_NAME);
              if (result) {
                successCount.incrementAndGet();
              } else {
                errorCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      long duration = System.currentTimeMillis() - startTime;
      log.info("{} test completed in {} ms with {} successful checks and {} errors", 
          threadType, duration, successCount.get(), errorCount.get());
      
      return duration;
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Sets up mock blobs for testing.
   */
  private void setupMockBlobs() {
    // Pre-setup some mock blobs to avoid excessive mock setup during concurrent execution
    for (int i = 0; i < 100; i++) {
      BlobId blobId = new BlobId(UUID.randomUUID().toString());
      setupMockBlob(blobId);
    }
  }

  /**
   * Sets up a mock blob with the specified ID.
   * 
   * @param blobId The ID of the blob to set up
   */
  private void setupMockBlob(BlobId blobId) {
    Blob blob = mock(Blob.class);
    AssetBlob assetBlob = mock(AssetBlob.class);
    BlobRef blobRef = new BlobRef(NODE_ID, DEFAULT, blobId.asUniqueString());
    
    Map<String, String> headers = new HashMap<>();
    headers.put(REPO_NAME_HEADER, REPO_NAME);
    
    when(blob.getHeaders()).thenReturn(headers);
    when(blobStore.get(blobId)).thenReturn(blob);
    when(assetBlobStore.readAssetBlob(any())).thenReturn(empty());
    when(assetBlobStore.readAssetBlob(eq(blobRef))).thenReturn(of(assetBlob));
  }

  /**
   * Creates a mock object of the specified type.
   * 
   * @param <T> The type of the mock to create
   * @param classToMock The class to mock
   * @return A mock object of the specified type
   */
  private <T> T mock(Class<T> classToMock) {
    return org.mockito.Mockito.mock(classToMock);
  }
}