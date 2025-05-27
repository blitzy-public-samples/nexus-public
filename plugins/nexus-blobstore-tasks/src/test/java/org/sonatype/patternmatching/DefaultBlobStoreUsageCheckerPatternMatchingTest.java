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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * This test class demonstrates how Java 21's Pattern Matching for switch feature can improve
 * the DefaultBlobStoreUsageChecker implementation by simplifying type checking and conditional logic
 * when determining if a blob is referenced in a repository.
 * 
 * Java 21's Pattern Matching for switch allows for more concise and readable code by:
 * 1. Eliminating the need for explicit type checking and casting
 * 2. Simplifying complex conditional logic with pattern-based case labels
 * 3. Supporting guarded patterns with 'when' clauses for conditional matching
 * 4. Handling null values directly in switch statements
 * 
 * This test shows how these features can be applied to improve the blob store usage checking logic.
 */
@ExtendWith(MockitoExtension.class)
class DefaultBlobStoreUsageCheckerPatternMatchingTest
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
    // Set up the assetBlobStore in contentFacetStores using reflection
    // This is equivalent to Whitebox.setInternalState in JUnit 4 tests
    try {
      java.lang.reflect.Field field = ContentFacetStores.class.getDeclaredField("assetBlobStore");
      field.setAccessible(true);
      field.set(contentFacetStores, assetBlobStore);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set assetBlobStore field", e);
    }

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
   * Demonstrates how Pattern Matching for switch can be used to check if a blob is referenced
   * in a repository. This approach simplifies the code by eliminating the need for explicit
   * type checking and casting.
   * 
   * This test shows how Pattern Matching can replace complex if-else chains when determining
   * if a blob is referenced in a repository, making the code more readable and maintainable.
   */
  @Test
  void demonstratePatternMatchingForBlobReferenceCheck() {
    // Verify that the blob is referenced using Pattern Matching approach
    boolean isReferenced = checkBlobReference(blobStore, BLOB_ID, BLOB_NAME);
    assertTrue(isReferenced, "Blob should be referenced");

    // Verify that a blob with a different ID is not referenced
    boolean differentBlobIdNotReferenced = checkBlobReference(blobStore, new BlobId("0"), BLOB_NAME);
    assertFalse(differentBlobIdNotReferenced, "Blob with different ID should not be referenced");

    // Verify that a blob with a different store name is not referenced
    when(blobStoreConfiguration.getName()).thenReturn(NOT_DEFAULT);
    boolean differentStoreNameNotReferenced = checkBlobReference(blobStore, BLOB_ID, BLOB_NAME);
    assertFalse(differentStoreNameNotReferenced, "Blob with different store name should not be referenced");
    
    // Reset the store name for subsequent tests
    when(blobStoreConfiguration.getName()).thenReturn(DEFAULT);
    
    // Compare with traditional approach
    boolean isReferencedTraditional = checkBlobReferenceTraditional(blobStore, BLOB_ID, BLOB_NAME);
    assertTrue(isReferencedTraditional, "Traditional approach should also find the blob referenced");
    
    // Verify both approaches yield the same results
    assertEquals(isReferenced, isReferencedTraditional, "Both approaches should yield the same result");
  }

  /**
   * This method demonstrates how Pattern Matching for switch can be used to simplify
   * the logic for checking if a blob is referenced in a repository.
   * 
   * @param store The BlobStore to check
   * @param blobId The BlobId to check
   * @param blobName The name of the blob
   * @return true if the blob is referenced, false otherwise
   */
  private boolean checkBlobReference(BlobStore store, BlobId blobId, String blobName) {
    // Using Pattern Matching for switch to handle different cases in the blob reference check process
    // This approach eliminates the need for multiple if-else statements and makes the code more readable
    
    // Get the blob from the store and handle null case
    Object blobResult = store.get(blobId);
    if (blobResult == null) {
      return false;
    }
    
    // Use Pattern Matching to process the blob
    return switch (blobResult) {
      case Blob foundBlob -> {
        // Get the repository name from the blob headers
        Map<String, String> headers = foundBlob.getHeaders();
        String repoName = headers.get(REPO_NAME_HEADER);
        if (repoName == null) {
          yield false;
        }
        
        // Get the repository from the repository manager
        Object repoResult = repositoryManager.get(repoName);
        yield switch (repoResult) {
          case Repository repo -> {
            // Get the content facet from the repository
            Object contentResult = repo.facet(ContentFacet.class);
            yield switch (contentResult) {
              case ContentFacet content -> {
                // Create a BlobRef for the blob
                String storeName = store.getBlobStoreConfiguration().getName();
                BlobRef blobRef = new BlobRef(content.nodeName(), storeName, blobId.asUniqueString());
                
                // Check if the blob is referenced
                Optional<AssetBlob> assetBlobOptional = content.stores().assetBlobStore.readAssetBlob(blobRef);
                yield switch (assetBlobOptional) {
                  case Optional<AssetBlob> opt when opt.isPresent() -> true;
                  default -> false;
                };
              }
              default -> false;
            };
          }
          default -> false;
        };
      }
      default -> false;
    };
  }
  
  /**
   * This method shows the traditional approach without Pattern Matching for switch.
   * Compare this with the checkBlobReference method to see how Pattern Matching simplifies the code.
   * 
   * @param store The BlobStore to check
   * @param blobId The BlobId to check
   * @param blobName The name of the blob
   * @return true if the blob is referenced, false otherwise
   */
  private boolean checkBlobReferenceTraditional(BlobStore store, BlobId blobId, String blobName) {
    // Traditional approach with multiple if-else statements
    
    // Get the blob from the store
    Blob foundBlob = store.get(blobId);
    if (foundBlob == null) {
      return false;
    }

    // Get the repository name from the blob headers
    Map<String, String> headers = foundBlob.getHeaders();
    String repoName = headers.get(REPO_NAME_HEADER);
    if (repoName == null) {
      return false;
    }

    // Get the repository from the repository manager
    Repository repo = repositoryManager.get(repoName);
    if (repo == null) {
      return false;
    }

    // Get the content facet from the repository
    ContentFacet content = repo.facet(ContentFacet.class);
    if (content == null) {
      return false;
    }

    // Create a BlobRef for the blob
    String storeName = store.getBlobStoreConfiguration().getName();
    BlobRef blobRef = new BlobRef(content.nodeName(), storeName, blobId.asUniqueString());

    // Check if the blob is referenced
    Optional<AssetBlob> assetBlobOptional = content.stores().assetBlobStore.readAssetBlob(blobRef);
    return assetBlobOptional.isPresent();
  }
  
  /**
   * Demonstrates how Pattern Matching for switch can be used to handle different types of
   * BlobRef objects. This approach simplifies the code by eliminating the need for explicit
   * type checking and casting.
   * 
   * This test shows how Pattern Matching can replace complex if-else chains or traditional
   * switch statements when dealing with different BlobRef scenarios.
   */
  @Test
  void demonstratePatternMatchingForDifferentBlobRefTypes() {
    // Create different types of BlobRef objects
    BlobRef standardBlobRef = new BlobRef(NODE_ID, DEFAULT, BLOB_ID.asUniqueString());
    BlobRef nullNodeBlobRef = new BlobRef(null, DEFAULT, BLOB_ID.asUniqueString());
    BlobRef nullStoreBlobRef = new BlobRef(NODE_ID, null, BLOB_ID.asUniqueString());
    BlobRef nullBlobIdBlobRef = new BlobRef(NODE_ID, DEFAULT, null);
    
    // Verify the results of processing different BlobRef types using Pattern Matching
    assertEquals("Valid BlobRef with all fields", processBlobRef(standardBlobRef));
    assertEquals("BlobRef with missing node ID", processBlobRef(nullNodeBlobRef));
    assertEquals("BlobRef with missing store name", processBlobRef(nullStoreBlobRef));
    assertEquals("BlobRef with missing blob ID", processBlobRef(nullBlobIdBlobRef));
    assertEquals("Not a BlobRef object", processBlobRef("Not a BlobRef"));
    assertEquals("Null input", processBlobRef(null));
    
    // Compare with traditional approach that would require multiple instanceof checks
    assertEquals("Valid BlobRef with all fields", processBlobRefTraditional(standardBlobRef));
    assertEquals("BlobRef with missing node ID", processBlobRefTraditional(nullNodeBlobRef));
  }

  /**
   * This method demonstrates how Pattern Matching for switch can be used to handle
   * different types of BlobRef objects with concise, readable code.
   * 
   * @param obj The object to process, which may be a BlobRef or something else
   * @return A string describing the type of object
   */
  private String processBlobRef(Object obj) {
    // Using Pattern Matching for switch with guarded patterns to handle different BlobRef scenarios
    return switch (obj) {
      case null -> "Null input";
      case BlobRef ref when ref.getNode() == null -> "BlobRef with missing node ID";
      case BlobRef ref when ref.getStore() == null -> "BlobRef with missing store name";
      case BlobRef ref when ref.getBlobId() == null -> "BlobRef with missing blob ID";
      case BlobRef ref -> "Valid BlobRef with all fields";
      default -> "Not a BlobRef object";
    };
  }
  
  /**
   * This method shows the traditional approach without Pattern Matching for switch.
   * Compare this with the processBlobRef method to see how Pattern Matching simplifies the code.
   * 
   * @param obj The object to process, which may be a BlobRef or something else
   * @return A string describing the type of object
   */
  private String processBlobRefTraditional(Object obj) {
    // Traditional approach with multiple if-else statements and explicit type checking
    if (obj == null) {
      return "Null input";
    } else if (obj instanceof BlobRef) {
      BlobRef ref = (BlobRef) obj;
      if (ref.getNode() == null) {
        return "BlobRef with missing node ID";
      } else if (ref.getStore() == null) {
        return "BlobRef with missing store name";
      } else if (ref.getBlobId() == null) {
        return "BlobRef with missing blob ID";
      } else {
        return "Valid BlobRef with all fields";
      }
    } else {
      return "Not a BlobRef object";
    }
  }

  /**
   * Demonstrates how Pattern Matching for switch can be used to process different types
   * of repository objects. This approach simplifies the code by eliminating the need for
   * explicit type checking and casting.
   * 
   * This test shows how Pattern Matching can be used to handle different repository types
   * and extract information from them in a concise way.
   */
  @Test
  void demonstratePatternMatchingForRepositoryProcessing() {
    // Set up a mock repository with a name
    when(repository.getName()).thenReturn(REPO_NAME);
    
    // Verify the results of processing different repository objects using Pattern Matching
    assertEquals("Repository: repoName", processRepository(repository));
    assertEquals("Not a repository object", processRepository("Not a repository"));
    assertEquals("Null input", processRepository(null));
    
    // Compare with traditional approach
    assertEquals("Repository: repoName", processRepositoryTraditional(repository));
  }

  /**
   * This method demonstrates how Pattern Matching for switch can be used to process
   * different types of repository objects with concise, readable code.
   * 
   * @param obj The object to process, which may be a Repository or something else
   * @return A string describing the type of object
   */
  private String processRepository(Object obj) {
    // Using Pattern Matching for switch to handle different repository scenarios
    return switch (obj) {
      case null -> "Null input";
      case Repository repo -> "Repository: " + repo.getName();
      default -> "Not a repository object";
    };
  }
  
  /**
   * This method shows the traditional approach without Pattern Matching for switch.
   * Compare this with the processRepository method to see how Pattern Matching simplifies the code.
   * 
   * @param obj The object to process, which may be a Repository or something else
   * @return A string describing the type of object
   */
  private String processRepositoryTraditional(Object obj) {
    // Traditional approach with if-else statements and explicit type checking
    if (obj == null) {
      return "Null input";
    } else if (obj instanceof Repository) {
      Repository repo = (Repository) obj;
      return "Repository: " + repo.getName();
    } else {
      return "Not a repository object";
    }
  }
}