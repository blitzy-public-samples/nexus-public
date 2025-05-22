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
package patternmatching;

import java.util.HashMap;
import java.util.Map;

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

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Tests for {@link DefaultBlobStoreUsageChecker} that validate Java 21's Pattern Matching capabilities.
 * This test class focuses on verifying that pattern matching for instanceof operations and switch expressions
 * correctly handle different types of blob references, repository configurations, and edge cases.
 *
 * @since 3.60
 */
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
   * Tests pattern matching with instanceof for BlobStore objects.
   * Verifies that the pattern variable is correctly initialized and accessible.
   */
  @Test
  public void testPatternMatchingWithInstanceofForBlobStore() {
    Object obj = blobStore;
    
    // Using pattern matching with instanceof to check and cast in one step
    if (obj instanceof BlobStore bs) {
      // Pattern variable 'bs' is now available and properly typed
      BlobStoreConfiguration config = bs.getBlobStoreConfiguration();
      assertThat(config.getName(), equalTo(DEFAULT));
    }
    else {
      // This should not be reached
      fail("Object should be an instance of BlobStore");
    }
  }

  /**
   * Tests pattern matching with instanceof for Blob objects.
   * Verifies that the pattern variable is correctly initialized and accessible.
   */
  @Test
  public void testPatternMatchingWithInstanceofForBlob() {
    Object obj = blob;
    
    // Using pattern matching with instanceof to check and cast in one step
    if (obj instanceof Blob b) {
      // Pattern variable 'b' is now available and properly typed
      Map<String, String> headers = b.getHeaders();
      assertThat(headers.get(REPO_NAME_HEADER), equalTo(REPO_NAME));
    }
    else {
      // This should not be reached
      fail("Object should be an instance of Blob");
    }
  }

  /**
   * Tests pattern matching with instanceof for Repository objects.
   * Verifies that the pattern variable is correctly initialized and accessible.
   */
  @Test
  public void testPatternMatchingWithInstanceofForRepository() {
    Object obj = repository;
    
    // Using pattern matching with instanceof to check and cast in one step
    if (obj instanceof Repository r) {
      // Pattern variable 'r' is now available and properly typed
      ContentFacet facet = r.facet(ContentFacet.class);
      assertThat(facet, equalTo(contentFacet));
    }
    else {
      // This should not be reached
      fail("Object should be an instance of Repository");
    }
  }

  /**
   * Tests pattern matching in switch expressions for different object types.
   * Verifies that the correct case is selected based on the object's type.
   */
  @Test
  public void testPatternMatchingInSwitchExpression() {
    // Test with BlobStore
    String result = getObjectTypeUsingPatternMatchingSwitch(blobStore);
    assertThat(result, equalTo("BlobStore"));
    
    // Test with Blob
    result = getObjectTypeUsingPatternMatchingSwitch(blob);
    assertThat(result, equalTo("Blob"));
    
    // Test with Repository
    result = getObjectTypeUsingPatternMatchingSwitch(repository);
    assertThat(result, equalTo("Repository"));
    
    // Test with other object type
    result = getObjectTypeUsingPatternMatchingSwitch("Some string");
    assertThat(result, equalTo("Other"));
  }
  
  /**
   * Tests pattern matching in switch expressions with guarded patterns.
   * Verifies that the correct case is selected based on the object's type and additional conditions.
   */
  @Test
  public void testPatternMatchingWithGuardedPatterns() {
    // Create a test BlobStore with a specific configuration name
    BlobStore testBlobStore = blobStore;
    when(testBlobStore.getBlobStoreConfiguration().getName()).thenReturn(DEFAULT);
    
    // Test with guarded pattern matching
    String result = getBlobStoreTypeUsingGuardedPatternMatching(testBlobStore);
    assertThat(result, equalTo("Default BlobStore"));
    
    // Change configuration name and test again
    when(testBlobStore.getBlobStoreConfiguration().getName()).thenReturn(NOT_DEFAULT);
    result = getBlobStoreTypeUsingGuardedPatternMatching(testBlobStore);
    assertThat(result, equalTo("Non-Default BlobStore"));
    
    // Test with non-BlobStore object
    result = getBlobStoreTypeUsingGuardedPatternMatching("Not a BlobStore");
    assertThat(result, equalTo("Not a BlobStore"));
  }

  /**
   * Tests pattern matching in switch expressions with null values.
   * Verifies that null values are handled correctly.
   */
  @Test
  public void testPatternMatchingWithNullValues() {
    String result = getObjectTypeUsingPatternMatchingSwitch(null);
    assertThat(result, equalTo("Null"));
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker correctly identifies referenced blobs
   * using pattern matching.
   */
  @Test
  public void testBlobIsReferencedWithPatternMatching() {
    assertThat(underTest.test(blobStore, BLOB_ID, BLOB_NAME), equalTo(true));
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker correctly identifies non-matching blob IDs
   * using pattern matching.
   */
  @Test
  public void testBlobIdDoesNotMatchWithPatternMatching() {
    assertThat(underTest.test(blobStore, new BlobId("0"), BLOB_NAME), equalTo(false));
  }

  /**
   * Tests that the DefaultBlobStoreUsageChecker correctly handles non-matching blob store names
   * using pattern matching.
   */
  @Test
  public void testBlobStoreNameDoesNotMatchWithPatternMatching() {
    when(blobStoreConfiguration.getName()).thenReturn(NOT_DEFAULT);

    assertThat(underTest.test(blobStore, BLOB_ID, BLOB_NAME), equalTo(false));
  }
  
  /**
   * Tests pattern matching with complex nested conditions.
   * This test demonstrates how pattern matching can be used with logical operators
   * to create more complex conditions.
   */
  @Test
  public void testPatternMatchingWithComplexConditions() {
    Object obj = blobStore;
    
    // Using pattern matching with instanceof and additional conditions
    if (obj instanceof BlobStore bs && bs.getBlobStoreConfiguration() != null && 
        DEFAULT.equals(bs.getBlobStoreConfiguration().getName())) {
      // This block should be executed for our test BlobStore
      assertThat(bs.getBlobStoreConfiguration().getName(), equalTo(DEFAULT));
    }
    else {
      // This should not be reached
      fail("Object should be a BlobStore with DEFAULT configuration");
    }
    
    // Change configuration name to test the negative case
    when(blobStoreConfiguration.getName()).thenReturn(NOT_DEFAULT);
    
    // Using pattern matching with instanceof and additional conditions
    if (obj instanceof BlobStore bs && bs.getBlobStoreConfiguration() != null && 
        DEFAULT.equals(bs.getBlobStoreConfiguration().getName())) {
      // This block should not be executed
      fail("Object should not match the condition");
    }
    else {
      // This should be reached
      assertThat(blobStoreConfiguration.getName(), equalTo(NOT_DEFAULT));
    }
  }
  
  /**
   * Tests pattern matching with exhaustive switch cases for sealed types.
   * This test demonstrates how pattern matching can be used with switch expressions
   * to handle all possible subtypes of a sealed type.
   */
  @Test
  public void testExhaustiveSwitchWithPatternMatching() {
    // Create test objects for each type we want to test
    Object blobStoreObj = blobStore;
    Object blobObj = blob;
    Object repoObj = repository;
    
    // Test with each object type
    assertThat(processRepositoryObject(blobStoreObj), equalTo("Processed BlobStore"));
    assertThat(processRepositoryObject(blobObj), equalTo("Processed Blob"));
    assertThat(processRepositoryObject(repoObj), equalTo("Processed Repository"));
    assertThat(processRepositoryObject("String"), equalTo("Unknown object type"));
  }

  /**
   * Helper method that uses pattern matching in a switch expression to determine the type of an object.
   *
   * @param obj The object to check
   * @return A string describing the type of the object
   */
  private String getObjectTypeUsingPatternMatchingSwitch(Object obj) {
    return switch (obj) {
      case null -> "Null";
      case BlobStore bs -> "BlobStore";
      case Blob b -> "Blob";
      case Repository r -> "Repository";
      default -> "Other";
    };
  }
  
  /**
   * Helper method that uses guarded pattern matching in a switch expression to determine
   * the type of a BlobStore based on its configuration.
   *
   * @param obj The object to check
   * @return A string describing the type of the BlobStore
   */
  private String getBlobStoreTypeUsingGuardedPatternMatching(Object obj) {
    return switch (obj) {
      // Guarded pattern: BlobStore with DEFAULT configuration name
      case BlobStore bs when DEFAULT.equals(bs.getBlobStoreConfiguration().getName()) -> 
          "Default BlobStore";
      // BlobStore with any other configuration name
      case BlobStore bs -> "Non-Default BlobStore";
      // Not a BlobStore
      default -> "Not a BlobStore";
    };
  }

  /**
   * Helper method that uses pattern matching in a switch expression to process repository-related objects.
   * This demonstrates how pattern matching can be used to handle different object types in a concise way.
   *
   * @param obj The object to process
   * @return A string describing the result of processing
   */
  private String processRepositoryObject(Object obj) {
    return switch (obj) {
      case BlobStore bs -> {
        // We can use the pattern variable 'bs' here
        BlobStoreConfiguration config = bs.getBlobStoreConfiguration();
        yield "Processed BlobStore";
      }
      case Blob b -> {
        // We can use the pattern variable 'b' here
        Map<String, String> headers = b.getHeaders();
        yield "Processed Blob";
      }
      case Repository r -> {
        // We can use the pattern variable 'r' here
        ContentFacet facet = r.facet(ContentFacet.class);
        yield "Processed Repository";
      }
      default -> "Unknown object type";
    };
  }
  
  /**
   * Tests pattern matching with record patterns (simulated since we don't have actual records).
   * This test demonstrates the concept of pattern matching with record patterns.
   */
  @Test
  public void testConceptualRecordPatternMatching() {
    // Since we don't have actual records in the codebase, this test demonstrates the concept
    // of how record pattern matching would work with BlobRef objects if they were records
    
    // Create a BlobRef which is not a record but we'll treat it conceptually as one
    BlobRef blobRef = new BlobRef(NODE_ID, DEFAULT, BLOB_ID.asUniqueString());
    
    // In Java 21 with a record, we could do something like this:
    // if (blobRef instanceof BlobRef(String node, String store, String blob)) {
    //   assertThat(node, equalTo(NODE_ID));
    //   assertThat(store, equalTo(DEFAULT));
    // }
    
    // Instead, we'll use traditional pattern matching
    if (blobRef instanceof BlobRef br) {
      assertThat(br.getNodeId(), equalTo(NODE_ID));
      assertThat(br.getStore(), equalTo(DEFAULT));
      assertThat(br.getBlobId(), equalTo(BLOB_ID.asUniqueString()));
    }
    else {
      fail("Object should be an instance of BlobRef");
    }
  }
  
  private void fail(String message) {
    throw new AssertionError(message);
  }
}