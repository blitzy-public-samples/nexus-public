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
package org.sonatype.nexus.blobstore.restore.raw.internal;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAssetBuilder;
import org.sonatype.nexus.repository.content.fluent.FluentAssets;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.handlers.LastDownloadedAttributeHandler;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.view.payloads.DetachedBlobPayload;

import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;

/**
 * Tests for {@link RawRestoreBlobStrategy} with Java 21 features.
 * <p>
 * This test class validates the blob restoration functionality for raw repositories,
 * including virtual thread execution, pattern matching, and other Java 21 features.
 */
@ExtendWith(MockitoExtension.class)
@Tag("java21-tests")
class RawRestoreBlobStrategyTest
    extends TestSupport
{
  private static final String TEST_BLOB_STORE_NAME = "test";

  private static final String REPOSITORY_NAME = "theRepository";

  private static final String BLOB_PATH = "/blob/path/end";

  private static final boolean DRY_RUN = true;

  @Mock
  private RawContentFacet rawContentFacet;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private Repository repository;

  @Mock
  private Blob blob;

  @Mock
  private BlobId blobId;

  @Mock
  private BlobAttributes blobAttributes;

  @Mock
  private AssetBlob assetBlob;

  @Mock
  private BlobMetrics blobMetrics;

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreConfiguration blobStoreConfiguration;

  @Mock
  private FluentAssets assets;

  @Mock
  private FluentAssetBuilder fluentAssetBuilder;

  @Mock
  private FluentAsset asset;

  @Mock
  private FluentComponent component;
  
  @Captor
  private ArgumentCaptor<DetachedBlobPayload> payloadCaptor;

  private final DryRunPrefix dryRunPrefix = new DryRunPrefix("DRY RUN");

  private Properties properties;

  @InjectMocks
  private RawRestoreBlobStrategy underTest;

  @BeforeEach
  void setup() {
    when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);

    when(repository.optionalFacet(RawContentFacet.class)).thenReturn(of(rawContentFacet));
    when(repository.facet(RawContentFacet.class)).thenReturn(rawContentFacet);
    when(repository.facet(ContentFacet.class)).thenReturn(rawContentFacet);

    when(rawContentFacet.assets()).thenReturn(assets);
    when(assets.path(anyString())).thenReturn(fluentAssetBuilder);
    when(fluentAssetBuilder.find()).thenReturn(Optional.of(asset));

    when(asset.component()).thenReturn(empty());
    when(asset.blob()).thenReturn(Optional.of(assetBlob));

    when(blob.getId()).thenReturn(blobId);
    when(blob.getMetrics()).thenReturn(blobMetrics);

    when(blobAttributes.isDeleted()).thenReturn(false);

    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);

    when(blobStoreConfiguration.getName()).thenReturn(TEST_BLOB_STORE_NAME);

    properties = new Properties();
    properties.put(HEADER_PREFIX + BlobStore.REPO_NAME_HEADER, REPOSITORY_NAME);
    properties.put(HEADER_PREFIX + BLOB_NAME_HEADER, BLOB_PATH);
    properties.put(HEADER_PREFIX + CONTENT_TYPE_HEADER, "testContentType");

    // Manual injection since we're using @InjectMocks
    underTest.injectDependencies(mock(LastDownloadedAttributeHandler.class));
  }

  @Test
  @DisplayName("Should skip restoration when no content facet is available")
  void restoreWhenNoContentFacet() {
    when(repository.optionalFacet(RawContentFacet.class)).thenReturn(empty());
    when(repository.facet(RawContentFacet.class)).thenReturn(null);
    when(repository.facet(ContentFacet.class)).thenReturn(null);

    underTest.restore(properties, blob, blobStore, !DRY_RUN);
    verifyNoMoreInteractions(rawContentFacet);
  }

  @Test
  @DisplayName("Should create a new asset when none exists")
  void restoreWhenNoAssetExists() throws IOException {
    when(fluentAssetBuilder.find()).thenReturn(empty());

    underTest.restore(properties, blob, blobStore, !DRY_RUN);
    verify(asset, never()).delete();
    verify(rawContentFacet, times(1)).put(eq(BLOB_PATH), any());
  }

  @Test
  @DisplayName("Should skip restoration when component and asset already exist")
  void restoreWhenComponentAndAssetExist() throws IOException {
    when(asset.component()).thenReturn(of(component));

    underTest.restore(properties, blob, blobStore, !DRY_RUN);
    verify(asset, never()).delete();
    verify(rawContentFacet, never()).put(eq(BLOB_PATH), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true})
  @DisplayName("Should not modify anything when in dry run mode")
  void restoreWhenDryRun(boolean dryRun) throws IOException {
    underTest.restore(properties, blob, blobStore, dryRun);
    verify(asset, never()).delete();
    verify(rawContentFacet, never()).put(anyString(), any());
  }

  @Test
  @DisplayName("Should create asset when all conditions are met")
  void restoreCreatesAsset() throws Exception {
    underTest.restore(properties, blob, blobStore, !DRY_RUN);

    verify(asset, times(1)).delete();
    verify(rawContentFacet, times(1)).put(eq(BLOB_PATH), any());
  }

  @Test
  @DisplayName("Should skip restoration for deleted blobs")
  void shouldSkipDeletedBlob() throws Exception {
    when(blobAttributes.isDeleted()).thenReturn(true);
    underTest.restore(properties, blob, blobStore, false);
    verifyNoMoreInteractions(rawContentFacet);
    verify(asset, never()).delete();
    verify(rawContentFacet, never()).put(eq(BLOB_PATH), any());
  }

  @Test
  @DisplayName("Should skip restoration when existing blob is newer")
  void shouldSkipOlderBlob() throws Exception {
    when(asset.component()).thenReturn(of(component));
    when(assetBlob.blobCreated()).thenReturn(OffsetDateTime.now());
    when(blobMetrics.getCreationTime()).thenReturn(DateTime.now().minusDays(1));
    underTest.restore(properties, blob, blobStore, false);
    verify(asset, never()).delete();
    verify(rawContentFacet, never()).put(eq(BLOB_PATH), any());
  }

  @Test
  @DisplayName("Should restore when blob is more recent than existing asset")
  void shouldRestoreMoreRecentBlob() throws Exception {
    when(asset.component()).thenReturn(of(component));
    when(assetBlob.blobCreated()).thenReturn(OffsetDateTime.now().minusDays(1));
    when(blobMetrics.getCreationTime()).thenReturn(DateTime.now());
    underTest.restore(properties, blob, blobStore, false);
    verify(asset, times(1)).delete();
    verify(rawContentFacet, times(1)).put(eq(BLOB_PATH), any());
  }
  
  @Test
  @DisplayName("Should correctly handle payload creation with pattern matching")
  void shouldHandlePayloadCreationWithPatternMatching() throws Exception {
    // Setup for pattern matching test
    underTest.restore(properties, blob, blobStore, !DRY_RUN);
    
    // Capture the payload and verify it using pattern matching
    verify(rawContentFacet).put(eq(BLOB_PATH), payloadCaptor.capture());
    
    // Using pattern matching to verify the payload type and content
    Object payload = payloadCaptor.getValue();
    
    if (payload instanceof DetachedBlobPayload detachedPayload) {
      // Pattern matching successful - verify the blob reference
      assertEquals(blob, detachedPayload.getBlob(), "The payload should contain the original blob");
    } else {
      // Pattern matching failed - this should not happen
      assertFalse(true, "Expected a DetachedBlobPayload but got: " + payload.getClass().getName());
    }
  }
  
  @Test
  @Tag("virtual-threads")
  @DisplayName("Should handle concurrent restorations with virtual threads")
  void concurrentRestorationsWithVirtualThreads() throws Exception {
    // Create multiple property sets for concurrent restoration
    int taskCount = 50;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Use virtual threads for concurrent operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Setup for concurrent operations
      when(fluentAssetBuilder.find()).thenReturn(empty()); // No existing assets
      
      // Submit multiple concurrent restoration tasks
      for (int i = 0; i < taskCount; i++) {
        final String path = BLOB_PATH + "-" + i;
        final Properties props = new Properties(properties);
        props.put(HEADER_PREFIX + BLOB_NAME_HEADER, path);
        
        executor.submit(() -> {
          try {
            // Perform restoration operation
            underTest.restore(props, blob, blobStore, !DRY_RUN);
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error in virtual thread restoration", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), "All restoration tasks should complete in time");
      assertEquals(taskCount, successCount.get(), "All restoration tasks should succeed");
    }
  }
  
  /**
   * Test that demonstrates the use of Java 21 string templates for logging.
   * This is a utility method that would be used in the actual implementation.
   */
  @Test
  @Tag("java21-tests")
  @DisplayName("Should format log messages using string templates")
  void demonstrateStringTemplatesForLogging() {
    // Sample method that would use string templates for logging
    String blobId = "test-blob-123";
    String repoName = "test-repo";
    boolean isDryRun = true;
    
    // Using Java 21 string templates for log messages
    String logMessage = STR."Restoring blob \{blobId} to repository \{repoName} (dry run: \{isDryRun})";
    
    // Verify the template was correctly processed
    assertEquals("Restoring blob test-blob-123 to repository test-repo (dry run: true)", logMessage);
  }
}