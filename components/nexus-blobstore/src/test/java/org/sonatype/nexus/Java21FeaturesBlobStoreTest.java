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
package org.sonatype.nexus.blobstore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test class demonstrating Java 21 language features in BlobStore test code.
 * <p>
 * This class showcases how Pattern Matching, Record Patterns, and String Templates
 * can be used to make BlobStore tests more concise, readable, and maintainable.
 */
public class Java21FeaturesBlobStoreTest
    extends TestSupport
{
  private static final String BLOB_CONTENT = "Test blob content";
  private static final String HEADER_CONTENT_TYPE = "content-type";
  private static final String CONTENT_TYPE_TEXT = "text/plain";
  private static final String BLOB_NAME = "test-blob";

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreConfiguration configuration;

  @Before
  public void setUp() {
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(configuration.getName()).thenReturn("test-blob-store");
  }

  /**
   * Demonstrates Pattern Matching for switch to handle different blob types.
   * Java 21 feature: Pattern Matching for switch
   */
  @Test
  public void testPatternMatchingForBlobTypes() {
    // Create different types of test objects
    Blob regularBlob = createMockBlob("regular", false, 100L);
    Blob temporaryBlob = createMockBlob("tmp$123", false, 50L);
    Blob deletedBlob = createMockBlob("deleted", true, 200L);
    
    // Use pattern matching for switch to handle different blob types
    String regularResult = describeBlobUsingPatternMatching(regularBlob);
    String temporaryResult = describeBlobUsingPatternMatching(temporaryBlob);
    String deletedResult = describeBlobUsingPatternMatching(deletedBlob);
    
    // Verify results
    assertThat(regularResult, is("Regular blob: regular, size: 100 bytes"));
    assertThat(temporaryResult, is("Temporary blob: tmp$123, size: 50 bytes"));
    assertThat(deletedResult, is("Deleted blob: deleted, size: 200 bytes"));
  }

  /**
   * Demonstrates Record Patterns to extract and validate data from blob attributes.
   * Java 21 feature: Record Patterns
   */
  @Test
  public void testRecordPatternsForBlobAttributes() {
    // Create test data with nested structures
    BlobMetrics metrics = createMockMetrics(1024L, "SHA1", "abcdef123456");
    Map<String, String> headers = new HashMap<>();
    headers.put(HEADER_CONTENT_TYPE, CONTENT_TYPE_TEXT);
    headers.put("X-Test-Header", "test-value");
    
    BlobAttributes attributes = createMockAttributes(new BlobId("test-id"), metrics, headers);
    
    // Use record patterns to extract and validate data
    boolean isValid = validateBlobAttributesUsingRecordPatterns(attributes);
    String contentType = extractContentTypeUsingRecordPatterns(attributes);
    
    // Verify results
    assertThat(isValid, is(true));
    assertThat(contentType, is(CONTENT_TYPE_TEXT));
  }

  /**
   * Demonstrates String Templates for more readable logging and assertions.
   * Java 21 feature: String Templates
   */
  @Test
  public void testStringTemplatesForBlobLogging() {
    // Create test data
    BlobId blobId = new BlobId("test-template-id");
    BlobMetrics metrics = createMockMetrics(2048L, "SHA256", "fedcba987654");
    OffsetDateTime creationTime = OffsetDateTime.now().minusDays(1);
    
    // Use string templates for logging and assertions
    String logMessage = createLogMessageUsingStringTemplate(blobId, metrics, creationTime);
    String assertionMessage = createAssertionMessageUsingStringTemplate(blobId, "test-operation");
    
    // Verify results
    assertThat(logMessage.contains("test-template-id"), is(true));
    assertThat(logMessage.contains("2048"), is(true));
    assertThat(logMessage.contains("SHA256"), is(true));
    assertThat(assertionMessage, is("Operation test-operation completed successfully on blob test-template-id"));
  }

  /**
   * Demonstrates combining multiple Java 21 features in a single test scenario.
   * Java 21 features: Pattern Matching for switch, Record Patterns, and String Templates
   */
  @Test
  public void testCombinedJava21Features() {
    // Create test data
    Blob regularBlob = createMockBlob("combined-test", false, 512L);
    BlobAttributes attributes = createMockAttributes(
        new BlobId("combined-test"), 
        createMockMetrics(512L, "MD5", "123456abcdef"), 
        Map.of(HEADER_CONTENT_TYPE, CONTENT_TYPE_TEXT)
    );
    
    when(regularBlob.getAttributes()).thenReturn(attributes);
    
    // Use combined Java 21 features
    String result = processBlobWithCombinedFeatures(regularBlob);
    
    // Verify result
    assertThat(result, is("Processed regular blob combined-test with content-type text/plain and size 512 bytes"));
  }

  /**
   * Helper method demonstrating Pattern Matching for switch.
   * This shows how to handle different blob types with cleaner, more expressive code.
   */
  private String describeBlobUsingPatternMatching(Blob blob) {
    BlobId blobId = blob.getId();
    BlobMetrics metrics = blob.getMetrics();
    
    return switch (blobId.asUniqueString()) {
      case String id when id.startsWith("tmp$") -> 
          "Temporary blob: " + id + ", size: " + metrics.getContentSize() + " bytes";
      case String id when metrics.isDeleted() -> 
          "Deleted blob: " + id + ", size: " + metrics.getContentSize() + " bytes";
      case String id -> 
          "Regular blob: " + id + ", size: " + metrics.getContentSize() + " bytes";
    };
  }

  /**
   * Helper method demonstrating Record Patterns.
   * This shows how to extract and validate nested data from blob attributes.
   */
  private boolean validateBlobAttributesUsingRecordPatterns(BlobAttributes attributes) {
    // Using record patterns to destructure the nested objects
    if (attributes instanceof BlobAttributes(BlobId id, BlobMetrics(long size, String sha1, String _), Map<String, String> headers)) {
      // Validate using the destructured variables
      return id != null && size > 0 && sha1 != null && headers.containsKey(HEADER_CONTENT_TYPE);
    }
    return false;
  }

  /**
   * Helper method demonstrating Record Patterns to extract content type.
   * This shows how to extract specific data from nested structures.
   */
  private String extractContentTypeUsingRecordPatterns(BlobAttributes attributes) {
    // Using record patterns to extract the headers map
    if (attributes instanceof BlobAttributes(BlobId _, BlobMetrics _, Map<String, String> headers)) {
      return headers.getOrDefault(HEADER_CONTENT_TYPE, "application/octet-stream");
    }
    return "application/octet-stream";
  }

  /**
   * Helper method demonstrating String Templates for log messages.
   * This shows how to create more readable and maintainable log messages.
   */
  private String createLogMessageUsingStringTemplate(BlobId blobId, BlobMetrics metrics, OffsetDateTime creationTime) {
    // Using string templates for more readable string formatting
    return STR."Blob ID: \{blobId.asUniqueString()}, Size: \{metrics.getContentSize()} bytes, " + 
           STR."Hash: \{metrics.getSha1Hash()}, Created: \{creationTime}";
  }

  /**
   * Helper method demonstrating String Templates for assertion messages.
   * This shows how to create more readable assertion messages.
   */
  private String createAssertionMessageUsingStringTemplate(BlobId blobId, String operation) {
    // Using string templates for more readable assertion messages
    return STR."Operation \{operation} completed successfully on blob \{blobId.asUniqueString()}";
  }

  /**
   * Helper method demonstrating combined Java 21 features.
   * This shows how to use Pattern Matching, Record Patterns, and String Templates together.
   */
  private String processBlobWithCombinedFeatures(Blob blob) {
    // Get blob attributes
    BlobAttributes attributes = blob.getAttributes();
    
    // Use pattern matching for switch to determine blob type
    String blobType = switch (blob.getId().asUniqueString()) {
      case String id when id.startsWith("tmp$") -> "temporary";
      case String id when blob.getMetrics().isDeleted() -> "deleted";
      default -> "regular";
    };
    
    // Use record patterns to extract content type and size
    String contentType = "unknown";
    long size = 0;
    
    if (attributes instanceof BlobAttributes(BlobId id, BlobMetrics(long blobSize, String _, String _), Map<String, String> headers)) {
      contentType = headers.getOrDefault(HEADER_CONTENT_TYPE, "unknown");
      size = blobSize;
    }
    
    // Use string templates to format the result
    return STR."Processed \{blobType} blob \{blob.getId().asUniqueString()} with content-type \{contentType} and size \{size} bytes";
  }

  /**
   * Helper method to create a mock Blob for testing.
   */
  private Blob createMockBlob(String id, boolean deleted, long size) {
    Blob blob = mock(Blob.class);
    BlobId blobId = new BlobId(id);
    BlobMetrics metrics = createMockMetrics(size, "SHA1", "abcdef123456");
    
    when(blob.getId()).thenReturn(blobId);
    when(blob.getMetrics()).thenReturn(metrics);
    when(metrics.isDeleted()).thenReturn(deleted);
    
    try {
      when(blob.getInputStream()).thenReturn(new ByteArrayInputStream(BLOB_CONTENT.getBytes(StandardCharsets.UTF_8)));
    } 
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    
    return blob;
  }

  /**
   * Helper method to create mock BlobMetrics for testing.
   */
  private BlobMetrics createMockMetrics(long size, String hashAlgorithm, String hash) {
    BlobMetrics metrics = mock(BlobMetrics.class);
    when(metrics.getContentSize()).thenReturn(size);
    when(metrics.getSha1Hash()).thenReturn(hash);
    return metrics;
  }

  /**
   * Helper method to create mock BlobAttributes for testing.
   */
  private BlobAttributes createMockAttributes(BlobId blobId, BlobMetrics metrics, Map<String, String> headers) {
    BlobAttributes attributes = mock(BlobAttributes.class);
    when(attributes.getBlobId()).thenReturn(blobId);
    when(attributes.getMetrics()).thenReturn(metrics);
    when(attributes.getHeaders()).thenReturn(headers);
    return attributes;
  }
}