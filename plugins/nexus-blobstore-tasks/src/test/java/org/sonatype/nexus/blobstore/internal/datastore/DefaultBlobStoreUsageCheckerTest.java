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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

@ExtendWith(MockitoExtension.class)
class DefaultBlobStoreUsageCheckerTest
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

  @BeforeEach
  void setUp() {
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

  @Test
  void blobIsReferenced() {
    assertThat(underTest.test(blobStore, BLOB_ID, BLOB_NAME), equalTo(true));
  }

  @Test
  void blobIdDoesNotMatch() {
    assertThat(underTest.test(blobStore, new BlobId("0"), BLOB_NAME), equalTo(false));
  }

  @Test
  void blobStoreNameDoesNotMatch() {
    when(blobStoreConfiguration.getName()).thenReturn(NOT_DEFAULT);

    assertThat(underTest.test(blobStore, BLOB_ID, BLOB_NAME), equalTo(false));
  }
  
  /**
   * Tests the usage checker with pattern matching for different blob types.
   * This test demonstrates Java 21's pattern matching capabilities.
   */
  @Test
  void patternMatchingWithDifferentBlobTypes() {
    // Create a blob with a different ID for testing pattern matching
    BlobId testBlobId = new BlobId("test-pattern-matching");
    
    // Test with different blob types using pattern matching
    Object result = underTest.test(blobStore, testBlobId, BLOB_NAME) ? "Referenced" : "Not Referenced";
    
    // Using pattern matching to handle the result
    switch (result) {
      case String s when s.equals("Referenced") -> 
          assertTrue(false, "Blob should not be referenced");
      case String s when s.equals("Not Referenced") -> 
          assertTrue(true, "Blob is correctly identified as not referenced");
      default -> 
          assertTrue(false, "Unexpected result type");
    }
  }
  
  /**
   * Tests the usage checker with concurrent operations using Java 21 Virtual Threads.
   * This demonstrates how to leverage virtual threads for concurrent testing.
   */
  @Test
  @org.junit.jupiter.api.Tag("Java21")
  @org.junit.jupiter.api.Tag("VirtualThread")
  void concurrentBlobChecksWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Test with the main blob ID for even numbers, and a non-existent one for odd numbers
            BlobId blobId = (index % 2 == 0) ? BLOB_ID : new BlobId("non-existent-" + index);
            boolean result = underTest.test(blobStore, blobId, BLOB_NAME);
            
            // For even indices, we expect true (blob exists)
            // For odd indices, we expect false (blob doesn't exist)
            if ((index % 2 == 0 && result) || (index % 2 != 0 && !result)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with timeout for safety)
      assertTrue(latch.await(5, TimeUnit.SECONDS), "All tasks should complete within timeout");
      
      // Verify all checks produced the expected results
      assertEquals(taskCount, successCount.get(), "All blob checks should produce expected results");
    }
  }
}
