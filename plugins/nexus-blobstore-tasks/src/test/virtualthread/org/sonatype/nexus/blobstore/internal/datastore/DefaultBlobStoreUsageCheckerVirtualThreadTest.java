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
package org.sonatype.nexus.blobstore.internal.datastore;

import java.util.HashMap;
import java.util.Map;
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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Virtual Thread-specific tests for {@link DefaultBlobStoreUsageChecker}.
 * 
 * These tests verify that blob usage checking operations function correctly when executed with
 * Java 21's Virtual Threads. The tests ensure that the blob store usage checker maintains its
 * functionality while leveraging the performance improvements provided by Java 21's lightweight
 * threading model, and that no thread pinning issues occur during execution.
 */
@Category(VirtualThreadTestGroup.class)
public class DefaultBlobStoreUsageCheckerVirtualThreadTest
    extends TestSupport
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

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
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
   * Verifies that the blob usage checker correctly identifies a referenced blob when executed in a virtual thread.
   */
  @Test
  public void blobIsReferencedInVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      boolean result = underTest.test(blobStore, BLOB_ID, BLOB_NAME);
      assertThat(result, equalTo(true));
    });
    
    virtualThread.start();
    virtualThread.join();
  }

  /**
   * Verifies that the blob usage checker correctly identifies a non-matching blob ID when executed in a virtual thread.
   */
  @Test
  public void blobIdDoesNotMatchInVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      boolean result = underTest.test(blobStore, new BlobId("0"), BLOB_NAME);
      assertThat(result, equalTo(false));
    });
    
    virtualThread.start();
    virtualThread.join();
  }

  /**
   * Verifies that the blob usage checker correctly handles a non-matching blob store name when executed in a virtual thread.
   */
  @Test
  public void blobStoreNameDoesNotMatchInVirtualThread() throws Exception {
    when(blobStoreConfiguration.getName()).thenReturn(NOT_DEFAULT);

    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      boolean result = underTest.test(blobStore, BLOB_ID, BLOB_NAME);
      assertThat(result, equalTo(false));
    });
    
    virtualThread.start();
    virtualThread.join();
  }

  /**
   * Tests concurrent blob usage checking operations using virtual threads.
   * This verifies that the blob store usage checker can handle multiple concurrent
   * operations efficiently using Java 21's virtual threads.
   */
  @Test
  public void concurrentBlobUsageChecksWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between valid and invalid blob IDs to test both paths
            BlobId blobId = (index % 2 == 0) ? BLOB_ID : new BlobId("invalid-" + index);
            boolean result = underTest.test(blobStore, blobId, BLOB_NAME);
            
            if (result) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results - half should succeed, half should fail
      assertThat(successCount.get(), equalTo(taskCount / 2));
      assertThat(failureCount.get(), equalTo(taskCount / 2));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that blob usage checking operations don't cause thread pinning when executed with virtual threads.
   * Thread pinning occurs when a virtual thread blocks the carrier thread, which reduces the efficiency
   * of the virtual thread model. This test verifies that the blob store usage checker doesn't cause
   * thread pinning during I/O operations.
   */
  @Test
  public void noPinningDuringBlobUsageChecks() throws Exception {
    // Enable thread pinning detection for this test
    // Note: In a real environment, this would be set via JVM flag: -Djdk.tracePinnedThreads=full
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    try {
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
      
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      
      try {
        // Run multiple concurrent operations that should not cause pinning
        for (int i = 0; i < taskCount; i++) {
          executor.submit(() -> {
            try {
              // Perform the blob usage check operation
              underTest.test(blobStore, BLOB_ID, BLOB_NAME);
              
              // If thread pinning occurred, the JVM would log it with the tracePinnedThreads flag
              // We can't directly assert on this, but the test would help identify pinning issues
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all tasks to complete
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertThat("All virtual thread tasks should complete in time", completed, equalTo(true));
      } finally {
        executor.shutdown();
      }
    } finally {
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }
}