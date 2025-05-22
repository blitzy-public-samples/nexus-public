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
package org.sonatype.patternmatching;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.sonatype.goodies.testsupport.TestSupport;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Test class demonstrating how Java 21's Pattern Matching for switch expressions can improve
 * the DefaultBlobStoreUsageChecker implementation.
 */
@ExtendWith(MockitoExtension.class)
public class DefaultBlobStoreUsageCheckerPatternMatchingTest
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
  private Blob blob;

  @Mock
  private AssetBlob assetBlob;

  @Mock
  private ContentFacetSupport contentFacet;

  @Mock
  private ContentFacetStores contentFacetStores;

  private DefaultBlobStoreUsageChecker underTest;

  @BeforeEach
  public void setUp() {
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
   * Demonstrates the standard test case where a blob is referenced.
   */
  @Test
  public void blobIsReferenced() {
    assertTrue(underTest.test(blobStore, BLOB_ID, BLOB_NAME));
  }

  /**
   * Demonstrates how Pattern Matching can be used to process different BlobRef types.
   * This test shows how we could refactor the BlobRef handling in DefaultBlobStoreUsageChecker
   * using Pattern Matching for switch expressions.
   */
  @Test
  public void demonstratePatternMatchingForBlobRefTypes() {
    // Create different types of BlobRefs for testing
    BlobRef standardBlobRef = new BlobRef(NODE_ID, DEFAULT, BLOB_ID.asUniqueString());
    BlobRef simpleBlobRef = new BlobRef(DEFAULT, BLOB_ID.asUniqueString());
    BlobRef dateBasedBlobRef = new BlobRef(NODE_ID, DEFAULT, BLOB_ID.asUniqueString(), 
        OffsetDateTime.now(ZoneOffset.UTC));

    // Demonstrate how Pattern Matching can simplify BlobRef type checking
    boolean isReferenced = processBlobRefWithPatternMatching(standardBlobRef);
    assertTrue(isReferenced, "Standard BlobRef should be referenced");

    // Test with a simple BlobRef (no node)
    when(assetBlobStore.readAssetBlob(eq(simpleBlobRef))).thenReturn(of(assetBlob));
    isReferenced = processBlobRefWithPatternMatching(simpleBlobRef);
    assertTrue(isReferenced, "Simple BlobRef should be referenced");

    // Test with a date-based BlobRef
    when(assetBlobStore.readAssetBlob(eq(dateBasedBlobRef))).thenReturn(of(assetBlob));
    isReferenced = processBlobRefWithPatternMatching(dateBasedBlobRef);
    assertTrue(isReferenced, "Date-based BlobRef should be referenced");

    // Test with a non-referenced BlobRef
    BlobRef nonReferencedBlobRef = new BlobRef(NODE_ID, NOT_DEFAULT, BLOB_ID.asUniqueString());
    isReferenced = processBlobRefWithPatternMatching(nonReferencedBlobRef);
    assertFalse(isReferenced, "Non-referenced BlobRef should not be referenced");
  }

  /**
   * Demonstrates how Pattern Matching can simplify the handling of different BlobStore types.
   */
  @Test
  public void demonstratePatternMatchingForBlobStoreTypes() {
    // Test with a standard BlobStore
    String blobStoreType = determineBlobStoreTypeWithPatternMatching(blobStore);
    assertEquals("Standard", blobStoreType);

    // Test with a null BlobStore
    blobStoreType = determineBlobStoreTypeWithPatternMatching(null);
    assertEquals("Unknown", blobStoreType);
  }

  /**
   * Demonstrates how Pattern Matching can simplify the handling of different Repository types.
   */
  @Test
  public void demonstratePatternMatchingForRepositoryTypes() {
    // Test with a standard Repository
    String repositoryType = determineRepositoryTypeWithPatternMatching(repository);
    assertEquals("Standard", repositoryType);

    // Test with a null Repository
    repositoryType = determineRepositoryTypeWithPatternMatching(null);
    assertEquals("Unknown", repositoryType);
  }

  /**
   * Example method demonstrating how Pattern Matching for switch expressions can simplify
   * BlobRef type checking and processing.
   * 
   * This shows how we could refactor the BlobRef handling in DefaultBlobStoreUsageChecker
   * using Pattern Matching for switch expressions instead of traditional if-else chains.
   */
  private boolean processBlobRefWithPatternMatching(BlobRef blobRef) {
    return switch (blobRef) {
      case BlobRef br when br.getDateBasedRef() != null ->
        // Handle date-based BlobRef
        assetBlobStore.readAssetBlob(blobRef).isPresent();
      
      case BlobRef br when br.getNode() == null ->
        // Handle simple BlobRef (no node)
        assetBlobStore.readAssetBlob(blobRef).isPresent();
      
      case BlobRef br when !DEFAULT.equals(br.getStore()) ->
        // Handle BlobRef with non-default store
        false;
      
      case BlobRef br ->
        // Handle standard BlobRef
        assetBlobStore.readAssetBlob(blobRef).isPresent();
      
      default -> false;
    };
  }

  /**
   * Example method demonstrating how Pattern Matching for switch expressions can simplify
   * BlobStore type checking.
   */
  private String determineBlobStoreTypeWithPatternMatching(BlobStore store) {
    return switch (store) {
      case null -> "Unknown";
      case BlobStore bs when bs.getBlobStoreConfiguration() == null -> "Unconfigured";
      case BlobStore bs -> "Standard";
    };
  }

  /**
   * Example method demonstrating how Pattern Matching for switch expressions can simplify
   * Repository type checking.
   */
  private String determineRepositoryTypeWithPatternMatching(Repository repo) {
    return switch (repo) {
      case null -> "Unknown";
      case Repository r when r.facet(ContentFacet.class) == null -> "No Content Facet";
      case Repository r -> "Standard";
    };
  }

  /**
   * Example method demonstrating how Pattern Matching with records can simplify
   * handling of blob reference results.
   */
  @Test
  public void demonstratePatternMatchingWithRecords() {
    // Define a record to represent a blob reference result
    record BlobReferenceResult(boolean isReferenced, String message) {}
    
    // Create different result scenarios
    BlobReferenceResult success = new BlobReferenceResult(true, "Blob is referenced");
    BlobReferenceResult failure = new BlobReferenceResult(false, "Blob is not referenced");
    BlobReferenceResult error = new BlobReferenceResult(false, "Error checking blob reference");
    
    // Demonstrate Pattern Matching with records
    String resultMessage = switch (success) {
      case BlobReferenceResult(true, var message) -> "Success: " + message;
      case BlobReferenceResult(false, var message) when message.contains("Error") -> "Error: " + message;
      case BlobReferenceResult(false, var message) -> "Failure: " + message;
    };
    
    assertEquals("Success: Blob is referenced", resultMessage);
    
    // Test with failure result
    resultMessage = switch (failure) {
      case BlobReferenceResult(true, var message) -> "Success: " + message;
      case BlobReferenceResult(false, var message) when message.contains("Error") -> "Error: " + message;
      case BlobReferenceResult(false, var message) -> "Failure: " + message;
    };
    
    assertEquals("Failure: Blob is not referenced", resultMessage);
    
    // Test with error result
    resultMessage = switch (error) {
      case BlobReferenceResult(true, var message) -> "Success: " + message;
      case BlobReferenceResult(false, var message) when message.contains("Error") -> "Error: " + message;
      case BlobReferenceResult(false, var message) -> "Failure: " + message;
    };
    
    assertEquals("Error: Error checking blob reference", resultMessage);
  }
}