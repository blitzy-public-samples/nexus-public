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
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.restore.RestoreBlobData;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;

/**
 * Test class demonstrating Java 21's Record Pattern feature for processing blob metadata properties.
 * 
 * @since 3.60
 */
public class BlobMetadataPatternTest
    extends TestSupport
{
  private static final String REPO_NAME = "maven-central";
  private static final String BLOB_NAME = "com/example/artifact/1.0/artifact-1.0.jar";
  private static final String CONTENT_TYPE = "application/java-archive";
  private static final String CREATED_BY = "admin";
  private static final String CREATED_BY_IP = "127.0.0.1";
  
  @Mock
  private Properties blobProperties;
  
  @Before
  public void setup() {
    when(blobProperties.getProperty(HEADER_PREFIX + REPO_NAME_HEADER)).thenReturn(REPO_NAME);
    when(blobProperties.getProperty(HEADER_PREFIX + BLOB_NAME_HEADER)).thenReturn(BLOB_NAME);
    when(blobProperties.getProperty(HEADER_PREFIX + CONTENT_TYPE_HEADER)).thenReturn(CONTENT_TYPE);
    when(blobProperties.getProperty("createdBy")).thenReturn(CREATED_BY);
    when(blobProperties.getProperty("createdByIp")).thenReturn(CREATED_BY_IP);
  }
  
  /**
   * Record representing blob metadata for demonstration purposes.
   */
  record BlobMetadata(String repoName, String blobName, String contentType) {}
  
  /**
   * Record with optional fields for demonstrating pattern matching with optional values.
   */
  record BlobAuditInfo(String createdBy, String createdByIp, OffsetDateTime createdTime) {}
  
  /**
   * Composite record that combines multiple metadata records.
   */
  record BlobInfo(BlobMetadata metadata, Optional<BlobAuditInfo> auditInfo) {}
  
  /**
   * Demonstrates traditional property access without pattern matching.
   */
  @Test
  public void traditionalPropertyAccess() {
    // Traditional approach to extract properties
    String repoName = blobProperties.getProperty(HEADER_PREFIX + REPO_NAME_HEADER);
    String blobName = blobProperties.getProperty(HEADER_PREFIX + BLOB_NAME_HEADER);
    String contentType = blobProperties.getProperty(HEADER_PREFIX + CONTENT_TYPE_HEADER);
    
    // Create metadata object manually
    BlobMetadata metadata = new BlobMetadata(repoName, blobName, contentType);
    
    // Assertions
    assertThat(metadata.repoName(), is(REPO_NAME));
    assertThat(metadata.blobName(), is(BLOB_NAME));
    assertThat(metadata.contentType(), is(CONTENT_TYPE));
  }
  
  /**
   * Demonstrates basic record pattern matching to extract blob metadata.
   */
  @Test
  public void basicRecordPatternMatching() {
    // Create metadata object
    BlobMetadata metadata = new BlobMetadata(
        blobProperties.getProperty(HEADER_PREFIX + REPO_NAME_HEADER),
        blobProperties.getProperty(HEADER_PREFIX + BLOB_NAME_HEADER),
        blobProperties.getProperty(HEADER_PREFIX + CONTENT_TYPE_HEADER));
    
    // Use record pattern to extract components directly
    if (metadata instanceof BlobMetadata(String repo, String blob, String type)) {
      assertThat(repo, is(REPO_NAME));
      assertThat(blob, is(BLOB_NAME));
      assertThat(type, is(CONTENT_TYPE));
    } else {
      // This should never happen
      fail("Record pattern matching failed");
    }
  }
  
  /**
   * Demonstrates using var with record patterns for type inference.
   */
  @Test
  public void recordPatternWithTypeInference() {
    BlobMetadata metadata = new BlobMetadata(REPO_NAME, BLOB_NAME, CONTENT_TYPE);
    
    // Using var for type inference in pattern matching
    if (metadata instanceof BlobMetadata(var repo, var blob, var type)) {
      // The compiler infers the correct types for the variables
      assertThat(repo, is(REPO_NAME));
      assertThat(blob, is(BLOB_NAME));
      assertThat(type, is(CONTENT_TYPE));
    }
  }
  
  /**
   * Demonstrates nested record pattern matching with optional values.
   */
  @Test
  public void nestedRecordPatternMatching() {
    // Create nested records
    BlobMetadata metadata = new BlobMetadata(REPO_NAME, BLOB_NAME, CONTENT_TYPE);
    BlobAuditInfo auditInfo = new BlobAuditInfo(CREATED_BY, CREATED_BY_IP, null);
    BlobInfo blobInfo = new BlobInfo(metadata, Optional.of(auditInfo));
    
    // Use nested record pattern to extract components from multiple levels
    if (blobInfo instanceof BlobInfo(BlobMetadata(var repo, var blob, var type), var auditOpt)) {
      assertThat(repo, is(REPO_NAME));
      assertThat(blob, is(BLOB_NAME));
      assertThat(type, is(CONTENT_TYPE));
      assertThat(auditOpt.isPresent(), is(true));
      
      // Further pattern matching on the optional value
      if (auditOpt.get() instanceof BlobAuditInfo(var creator, var ip, var timestamp)) {
        assertThat(creator, is(CREATED_BY));
        assertThat(ip, is(CREATED_BY_IP));
        assertThat(timestamp, is(nullValue()));
      }
    }
  }
  
  /**
   * Demonstrates using record patterns in switch expressions.
   */
  @Test
  public void recordPatternInSwitchExpression() {
    // Create test objects
    BlobMetadata mavenMetadata = new BlobMetadata("maven-central", BLOB_NAME, CONTENT_TYPE);
    BlobMetadata npmMetadata = new BlobMetadata("npm", "package.json", "application/json");
    BlobMetadata dockerMetadata = new BlobMetadata("docker", "image.tar", "application/x-tar");
    
    // Use record pattern in switch expression
    for (BlobMetadata metadata : new BlobMetadata[] {mavenMetadata, npmMetadata, dockerMetadata}) {
      String formatType = switch (metadata) {
        case BlobMetadata("maven-central", var name, var type) -> "Maven artifact: " + name;
        case BlobMetadata("npm", var name, var type) -> "NPM package: " + name;
        case BlobMetadata(var repo, var name, "application/x-tar") -> "Docker image in " + repo;
        default -> "Unknown format";
      };
      
      // Verify the switch expression worked correctly
      if (metadata == mavenMetadata) {
        assertThat(formatType, is("Maven artifact: " + BLOB_NAME));
      } else if (metadata == npmMetadata) {
        assertThat(formatType, is("NPM package: package.json"));
      } else if (metadata == dockerMetadata) {
        assertThat(formatType, is("Docker image in docker"));
      }
    }
  }
  
  /**
   * Demonstrates using record patterns with guards for conditional matching.
   */
  @Test
  public void recordPatternWithGuards() {
    // Create test objects with different content types
    BlobMetadata jarMetadata = new BlobMetadata(REPO_NAME, "artifact.jar", "application/java-archive");
    BlobMetadata pomMetadata = new BlobMetadata(REPO_NAME, "pom.xml", "application/xml");
    BlobMetadata unknownMetadata = new BlobMetadata(REPO_NAME, "unknown.bin", "application/octet-stream");
    
    for (BlobMetadata metadata : new BlobMetadata[] {jarMetadata, pomMetadata, unknownMetadata}) {
      String contentCategory = switch (metadata) {
        // Using guards with record patterns for more specific matching
        case BlobMetadata(var repo, var name, var type) when type.contains("java-archive") -> "Java binary";
        case BlobMetadata(var repo, var name, var type) when type.contains("xml") -> "XML document";
        case BlobMetadata(var repo, var name, var type) when name.endsWith(".md") -> "Documentation";
        default -> "Unknown content";
      };
      
      // Verify the pattern matching with guards worked correctly
      if (metadata == jarMetadata) {
        assertThat(contentCategory, is("Java binary"));
      } else if (metadata == pomMetadata) {
        assertThat(contentCategory, is("XML document"));
      } else if (metadata == unknownMetadata) {
        assertThat(contentCategory, is("Unknown content"));
      }
    }
  }
  
  /**
   * Demonstrates a practical use case: validating blob metadata using pattern matching.
   */
  @Test
  public void validateBlobMetadataWithPatternMatching() {
    // Valid metadata
    BlobMetadata validMetadata = new BlobMetadata(REPO_NAME, BLOB_NAME, CONTENT_TYPE);
    
    // Invalid metadata (missing content type)
    BlobMetadata invalidMetadata = new BlobMetadata(REPO_NAME, BLOB_NAME, null);
    
    // Validation function using pattern matching
    boolean isValidMetadata = validMetadata instanceof BlobMetadata(var repo, var blob, var type) 
        && repo != null && !repo.isEmpty()
        && blob != null && !blob.isEmpty()
        && type != null && !type.isEmpty();
    
    boolean isInvalidMetadata = invalidMetadata instanceof BlobMetadata(var repo, var blob, var type) 
        && repo != null && !repo.isEmpty()
        && blob != null && !blob.isEmpty()
        && type != null && !type.isEmpty();
    
    assertThat(isValidMetadata, is(true));
    assertThat(isInvalidMetadata, is(false));
  }
  
  /**
   * Demonstrates extracting and converting metadata values using pattern matching.
   */
  @Test
  public void extractAndConvertMetadataValues() {
    // Create metadata with numeric values as strings
    Map<String, String> metadataMap = Map.of(
        "size", "1024",
        "downloads", "42",
        "rating", "4.5"
    );
    
    record NumericMetadata(String sizeStr, String downloadsStr, String ratingStr) {}
    
    // Create record from map
    NumericMetadata metadata = new NumericMetadata(
        metadataMap.get("size"),
        metadataMap.get("downloads"),
        metadataMap.get("rating")
    );
    
    // Extract and convert values using pattern matching
    if (metadata instanceof NumericMetadata(var sizeStr, var downloadsStr, var ratingStr)) {
      // Convert string values to appropriate numeric types
      long size = Long.parseLong(sizeStr);
      int downloads = Integer.parseInt(downloadsStr);
      double rating = Double.parseDouble(ratingStr);
      
      // Verify conversions
      assertThat(size, is(1024L));
      assertThat(downloads, is(42));
      assertThat(rating, is(4.5));
    }
  }
}