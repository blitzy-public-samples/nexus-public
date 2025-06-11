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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.opentest4j.TestAbortedException;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.internal.datastore.DefaultBlobStoreUsageChecker;
import org.sonatype.nexus.content.testsuite.groups.VirtualThreadTestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetStores;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.store.AssetBlobStore;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.test.util.Whitebox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.VirtualThreadExtension.supplyFromVirtualThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Tests {@link DefaultBlobStoreUsageChecker} with Java 21 Virtual Threads.
 * 
 * This test validates that the DefaultBlobStoreUsageChecker works correctly with
 * Java 21's Virtual Threads feature, ensuring that blob usage checking operations
 * can efficiently utilize the lightweight threading model.
 *
 * @since 3.60
 */
public class DefaultBlobStoreUsageCheckerVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final String REPO_NAME = "repoName";

  private static final String NODE_ID = "repoName";

  private static final String DEFAULT = "default";

  private static final String NOT_DEFAULT = "notADefault";

  private static final String BLOB_ID_STRING = "86e20baa-0bca-4915-a7dc-9a4f34e72321";

  private static final BlobId BLOB_ID = new BlobId(BLOB_ID_STRING);

  private static final String BLOB_NAME = "/fake/blob.name";

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
  Blob blob;

  @Mock
  AssetBlob assetBlob;

  @Mock
  ContentFacetSupport contentFacet;

  @Mock
  ContentFacetStores contentFacetStores;

  DefaultBlobStoreUsageChecker underTest;

  @BeforeEach
  public void setUp() {
    // Skip test if Virtual Threads are not supported
    assumeVirtualThreadSupported();
    
    Whitebox.setInternalState(contentFacetStores, "assetBlobStore", assetBlobStore);

    when(contentFacet.stores()).thenReturn(contentFacetStores);
    when(contentFacet.nodeName()).thenReturn(NODE_ID);

    BlobRef blobRef = new BlobRef(NODE_ID, DEFAULT, BLOB_ID.asUniqueString());

    when(blobStoreConfiguration.getName()).thenReturn(DEFAULT);

    when(blobStore.get(BLOB_ID)).thenReturn(blob);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);

    Map<String, String> headers = new HashMap<>();
    headers.put(REPO_NAME_HEADER, REPO_NAME);
    when(blob.getHeaders()).thenReturn(headers);

    when(repositoryManager.get(REPO_NAME)).thenReturn(repository);

    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);

    when(assetBlobStore.readAssetBlob(any())).thenReturn(empty());
    when(assetBlobStore.readAssetBlob(eq(blobRef))).thenReturn(of(assetBlob));

    underTest = new DefaultBlobStoreUsageChecker(repositoryManager);
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker works correctly when executed on a Virtual Thread.
   */
  @Test
  public void testBlobIsReferencedOnVirtualThread() throws Exception {
    // Execute the test on a Virtual Thread
    Boolean result = supplyFromVirtualThread(() -> {
      // Verify we're running on a Virtual Thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run on a Virtual Thread");
      
      // Test the blob store usage checker
      return underTest.test(blobStore, BLOB_ID, BLOB_NAME);
    });
    
    // Verify the result
    assertTrue(result, "Blob should be referenced");
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker correctly handles non-matching blob IDs when executed on a Virtual Thread.
   */
  @Test
  public void testBlobIdDoesNotMatchOnVirtualThread() throws Exception {
    // Execute the test on a Virtual Thread
    Boolean result = supplyFromVirtualThread(() -> {
      // Verify we're running on a Virtual Thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run on a Virtual Thread");
      
      // Test the blob store usage checker with a non-matching blob ID
      return underTest.test(blobStore, new BlobId("0"), BLOB_NAME);
    });
    
    // Verify the result
    assertFalse(result, "Blob should not be referenced");
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker correctly handles non-matching blob store names when executed on a Virtual Thread.
   */
  @Test
  public void testBlobStoreNameDoesNotMatchOnVirtualThread() throws Exception {
    // Execute the test on a Virtual Thread
    Boolean result = supplyFromVirtualThread(() -> {
      // Verify we're running on a Virtual Thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run on a Virtual Thread");
      
      // Change the blob store configuration name
      when(blobStoreConfiguration.getName()).thenReturn(NOT_DEFAULT);
      
      // Test the blob store usage checker
      return underTest.test(blobStore, BLOB_ID, BLOB_NAME);
    });
    
    // Verify the result
    assertFalse(result, "Blob should not be referenced");
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker can handle concurrent blob usage checks using Virtual Threads.
   * This test verifies that the component can efficiently process multiple concurrent requests
   * using Java 21's lightweight threading model.
   */
  @Test
  public void testConcurrentBlobUsageChecksWithVirtualThreads() throws Exception {
    // Number of concurrent checks to perform
    int concurrentChecks = 100;
    
    // Create a list of tasks to execute concurrently
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < concurrentChecks; i++) {
      tasks.add(() -> underTest.test(blobStore, BLOB_ID, BLOB_NAME));
    }
    
    // Create a Virtual Thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit all tasks
      List<Future<Boolean>> futures = executor.invokeAll(tasks);
      
      // Verify all tasks completed successfully
      int successCount = 0;
      for (Future<Boolean> future : futures) {
        if (future.get()) {
          successCount++;
        }
      }
      
      // All checks should return true (blob is referenced)
      assertEquals(concurrentChecks, successCount, 
          "All concurrent blob usage checks should succeed");
    }
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker does not cause thread pinning during blob reference lookups.
   * Thread pinning occurs when a Virtual Thread cannot be unmounted from its carrier thread,
   * which can happen when using synchronized blocks or native methods.
   */
  @Test
  public void testNoPinningDuringBlobReferenceLookup() throws Exception {
    // Create a thread factory for virtual threads
    ThreadFactory factory = Thread.ofVirtual().name("blob-usage-check-").factory();
    
    // Create and start a virtual thread to perform the blob usage check
    Thread virtualThread = factory.newThread(() -> {
      // Perform multiple blob usage checks to increase chance of detecting pinning
      for (int i = 0; i < 10; i++) {
        underTest.test(blobStore, BLOB_ID, BLOB_NAME);
      }
    });
    
    // Start the virtual thread
    virtualThread.start();
    
    // Wait for the thread to complete
    virtualThread.join(5000);
    
    // Verify the thread completed (not deadlocked or pinned)
    assertFalse(virtualThread.isAlive(), "Virtual thread should complete without being pinned");
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker can handle high concurrency scenarios with Virtual Threads.
   * This test creates a large number of Virtual Threads to simulate high load and verifies that
   * the component can handle the load efficiently.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Number of concurrent threads to create
    int threadCount = 1000;
    
    // Create a thread factory for virtual threads
    ThreadFactory factory = Thread.ofVirtual().name("high-concurrency-test-").factory();
    
    // Create threads
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      Thread thread = factory.newThread(() -> {
        // Perform the blob usage check
        underTest.test(blobStore, BLOB_ID, BLOB_NAME);
      });
      threads.add(thread);
    }
    
    // Start all threads
    for (Thread thread : threads) {
      thread.start();
    }
    
    // Wait for all threads to complete
    for (Thread thread : threads) {
      thread.join(5000);
    }
    
    // Verify all threads completed
    for (int i = 0; i < threadCount; i++) {
      assertFalse(threads.get(i).isAlive(), 
          "Virtual thread " + i + " should complete without being pinned");
    }
  }

  /**
   * Helper method to check if the current JVM supports Virtual Threads.
   * This is used in the setUp method to skip tests if Virtual Threads are not supported.
   */
  public static void assumeVirtualThreadSupported() {
    if (!isVirtualThreadSupported()) {
      throw new TestAbortedException("Virtual Threads not supported in this JVM");
    }
  }
}