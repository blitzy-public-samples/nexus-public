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
package org.sonatype.recordpatterns;

import java.time.OffsetDateTime;
import java.util.Properties;
import java.util.function.Function;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.restore.RestoreBlobData;
import org.sonatype.nexus.blobstore.restore.datastore.DataStoreRestoreBlobData;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Test class demonstrating Java 21's Record Pattern feature with blob restoration data structures.
 * 
 * This class shows how record patterns can simplify the extraction and processing of nested data
 * from RestoreBlobData objects, improving code clarity and reducing error-prone manual data extraction
 * when working with complex blob metadata during restoration operations.
 */
public class RestoreBlobDataPatternTest
{
  /**
   * Record representing blob metadata for testing pattern matching
   */
  record BlobMetadata(String name, String type, String repositoryName) {}

  /**
   * Record representing repository information for testing pattern matching
   */
  record RepositoryInfo(String name, String format, boolean online) {}

  /**
   * Record representing blob restoration metadata for testing pattern matching
   */
  record RestoreMetadata(OffsetDateTime timestamp, String reason, boolean complete) {}

  /**
   * Nested record structure combining blob metadata, repository info, and restoration metadata
   * for demonstrating nested pattern matching
   */
  record BlobRestoreData(
      BlobMetadata blobMetadata,
      RepositoryInfo repositoryInfo,
      RestoreMetadata restoreMetadata) {}

  /**
   * Record representing a blob with its associated properties and metadata
   */
  record BlobWrapper(Blob blob, BlobMetadata metadata, BlobStore store) {}

  /**
   * Tests basic record pattern matching with instanceof to extract blob metadata properties
   */
  @Test
  public void testBasicRecordPatternMatching() {
    // Create test data
    BlobMetadata metadata = new BlobMetadata("test-blob", "application/octet-stream", "maven-central");
    
    // Traditional approach without pattern matching
    String blobName = metadata.name();
    String blobType = metadata.type();
    String repoName = metadata.repositoryName();
    assertEquals("test-blob", blobName);
    assertEquals("application/octet-stream", blobType);
    assertEquals("maven-central", repoName);
    
    // Using record pattern matching with instanceof
    Object obj = metadata;
    if (obj instanceof BlobMetadata(String name, String type, String repo)) {
      // Direct access to components through pattern variables
      assertEquals("test-blob", name);
      assertEquals("application/octet-stream", type);
      assertEquals("maven-central", repo);
    } else {
      // This should not happen
      assertFalse(true, "Pattern matching failed");
    }
  }

  /**
   * Tests nested record pattern matching to extract data from complex nested structures
   */
  @Test
  public void testNestedRecordPatternMatching() {
    // Create nested test data
    BlobMetadata metadata = new BlobMetadata("nested-blob", "application/json", "npm-proxy");
    RepositoryInfo repoInfo = new RepositoryInfo("npm-proxy", "npm", true);
    RestoreMetadata restoreInfo = new RestoreMetadata(
        OffsetDateTime.now(), "scheduled restore", true);
    
    BlobRestoreData restoreData = new BlobRestoreData(metadata, repoInfo, restoreInfo);
    
    // Traditional approach without pattern matching
    String blobName = restoreData.blobMetadata().name();
    String repoFormat = restoreData.repositoryInfo().format();
    String restoreReason = restoreData.restoreMetadata().reason();
    
    assertEquals("nested-blob", blobName);
    assertEquals("npm", repoFormat);
    assertEquals("scheduled restore", restoreReason);
    
    // Using nested record pattern matching with instanceof
    Object obj = restoreData;
    if (obj instanceof BlobRestoreData(
        BlobMetadata(String name, var type, var repo),
        RepositoryInfo(var repoName, String format, boolean online),
        RestoreMetadata(var timestamp, String reason, var complete))) {
      
      // Direct access to nested components through pattern variables
      assertEquals("nested-blob", name);
      assertEquals("npm", format);
      assertEquals("scheduled restore", reason);
      assertTrue(online);
      assertTrue(complete);
    } else {
      // This should not happen
      assertFalse(true, "Nested pattern matching failed");
    }
  }

  /**
   * Tests pattern matching in switch expressions for different blob types
   */
  @Test
  public void testPatternMatchingInSwitch() {
    // Create test data for different blob types
    BlobMetadata assetBlob = new BlobMetadata("asset.jar", "application/java-archive", "maven-central");
    BlobMetadata metadataBlob = new BlobMetadata("maven-metadata.xml", "application/xml", "maven-central");
    BlobMetadata unknownBlob = new BlobMetadata("unknown.dat", "application/octet-stream", "raw");
    
    // Function that uses switch expression with pattern matching to process different blob types
    Function<BlobMetadata, String> getBlobCategory = blob -> switch(blob) {
      case BlobMetadata(var name, var type, var repo) when name.endsWith(".jar") || name.endsWith(".war") ->
          "Java Archive";
          
      case BlobMetadata(var name, String type, var repo) when type.equals("application/xml") && name.contains("metadata") ->
          "Repository Metadata";
          
      case BlobMetadata(var name, var type, String repo) when repo.equals("raw") ->
          "Raw Content";
          
      default -> "Unknown";
    };
    
    // Test the switch expression with pattern matching
    assertEquals("Java Archive", getBlobCategory.apply(assetBlob));
    assertEquals("Repository Metadata", getBlobCategory.apply(metadataBlob));
    assertEquals("Raw Content", getBlobCategory.apply(unknownBlob));
  }

  /**
   * Tests pattern matching with RestoreBlobData mock objects to simulate real-world usage
   */
  @Test
  public void testPatternMatchingWithRestoreBlobData() {
    // Create mocks for testing with RestoreBlobData
    Blob blob = mock(Blob.class);
    BlobId blobId = mock(BlobId.class);
    BlobStore blobStore = mock(BlobStore.class);
    Repository repository = mock(Repository.class);
    RepositoryManager repositoryManager = mock(RepositoryManager.class);
    
    // Setup properties for RestoreBlobData
    Properties properties = new Properties();
    properties.setProperty(HEADER_PREFIX + BLOB_NAME_HEADER, "test-asset.jar");
    properties.setProperty(HEADER_PREFIX + CONTENT_TYPE_HEADER, "application/java-archive");
    properties.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
    
    // Setup mocks
    when(blob.getId()).thenReturn(blobId);
    when(blobId.asUniqueString()).thenReturn("test-blob-id");
    when(repositoryManager.get("maven-central")).thenReturn(repository);
    when(repository.getName()).thenReturn("maven-central");
    
    // Create RestoreBlobData object
    RestoreBlobData restoreBlobData = new DataStoreRestoreBlobData(
        blob, properties, blobStore, repositoryManager);
    
    // Create a wrapper record for the RestoreBlobData to demonstrate pattern matching
    record RestoreBlobDataWrapper(RestoreBlobData data, String operation, boolean critical) {}
    
    RestoreBlobDataWrapper wrapper = new RestoreBlobDataWrapper(restoreBlobData, "restore", true);
    
    // Use pattern matching to extract and process data
    Object obj = wrapper;
    if (obj instanceof RestoreBlobDataWrapper(var data, String operation, boolean critical)) {
      assertEquals("restore", operation);
      assertTrue(critical);
      
      // Access RestoreBlobData properties
      assertEquals("/test-asset.jar", data.getBlobName());
      assertEquals("application/java-archive", data.getBlobType());
      assertEquals("maven-central", data.getRepository().getName());
    } else {
      // This should not happen
      assertFalse(true, "Pattern matching with RestoreBlobData failed");
    }
  }

  /**
   * Tests conditional pattern matching with different blob types
   */
  @Test
  public void testConditionalPatternMatching() {
    // Create test data
    BlobWrapper assetBlob = new BlobWrapper(
        mock(Blob.class),
        new BlobMetadata("asset.jar", "application/java-archive", "maven-central"),
        mock(BlobStore.class));
    
    BlobWrapper metadataBlob = new BlobWrapper(
        mock(Blob.class),
        new BlobMetadata("maven-metadata.xml", "application/xml", "maven-central"),
        mock(BlobStore.class));
    
    // Process blob based on its type using conditional pattern matching
    String processAssetBlob = processBlob(assetBlob);
    String processMetadataBlob = processBlob(metadataBlob);
    
    assertEquals("Processing JAR asset: asset.jar", processAssetBlob);
    assertEquals("Processing XML metadata: maven-metadata.xml", processMetadataBlob);
  }
  
  /**
   * Helper method that demonstrates conditional pattern matching with guards
   */
  private String processBlob(Object obj) {
    return switch(obj) {
      // Match JAR assets
      case BlobWrapper(var blob, BlobMetadata(String name, var type, var repo), var store) 
          when name.endsWith(".jar") -> 
          "Processing JAR asset: " + name;
      
      // Match XML metadata
      case BlobWrapper(var blob, BlobMetadata(String name, String type, var repo), var store) 
          when type.equals("application/xml") -> 
          "Processing XML metadata: " + name;
      
      // Default case
      default -> "Unknown blob type";
    };
  }
}