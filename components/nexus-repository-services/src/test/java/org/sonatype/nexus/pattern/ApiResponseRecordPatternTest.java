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
package org.sonatype.nexus.pattern;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository.RepositoryAttributes;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository.StorageAttributesRecord;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository.HostedStorageAttributesRecord;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository.CleanupPolicyAttributesRecord;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Java 21 Record Pattern Matching with API response objects.
 * 
 * These tests validate that record pattern matching works correctly with
 * repository API models after upgrading to Java 21.
 */
@Category(Java21TestGroup.class)
public class ApiResponseRecordPatternTest
    extends TestSupport
{
  /**
   * Test implementation of AbstractApiRepository for testing.
   */
  private static class TestRepository extends AbstractApiRepository {
    public TestRepository(String name, String format, String type, String url, Boolean online) {
      super(name, format, type, url, online);
    }
  }
  
  /**
   * Tests basic record pattern matching with repository attributes.
   */
  @Test
  public void testBasicRepositoryAttributePatternMatching() {
    // Create a test repository
    TestRepository repository = new TestRepository("test-repo", "maven", "hosted", "http://example.com/repo", true);
    
    // Convert to record for pattern matching
    RepositoryAttributes attributes = repository.toAttributes();
    
    // Test pattern matching with record pattern
    if (attributes instanceof RepositoryAttributes(String name, String format, String type, String url, Boolean online)) {
      assertEquals("test-repo", name, "Repository name should match");
      assertEquals("maven", format, "Format should match");
      assertEquals("hosted", type, "Type should match");
      assertEquals("http://example.com/repo", url, "URL should match");
      assertEquals(true, online, "Online status should match");
    } else {
      // This should never happen
      assertTrue(false, "Pattern matching failed");
    }
  }
  
  /**
   * Tests pattern matching with storage attributes.
   */
  @Test
  public void testStorageAttributePatternMatching() {
    // Create a test repository
    TestRepository repository = new TestRepository("test-repo", "maven", "hosted", "http://example.com/repo", true);
    
    // Create storage attributes record
    StorageAttributesRecord storageAttributes = new StorageAttributesRecord("default", true);
    
    // Test pattern matching using the processAttributes method
    String result = repository.processAttributes(storageAttributes);
    
    assertThat(result, containsString("Storage: default"));
    assertThat(result, containsString("strict validation: true"));
  }
  
  /**
   * Tests pattern matching with hosted storage attributes.
   */
  @Test
  public void testHostedStorageAttributePatternMatching() {
    // Create a test repository
    TestRepository repository = new TestRepository("test-repo", "maven", "hosted", "http://example.com/repo", true);
    
    // Create hosted storage attributes record
    HostedStorageAttributesRecord hostedAttributes = 
        new HostedStorageAttributesRecord("default", true, "ALLOW");
    
    // Test pattern matching using the processAttributes method
    String result = repository.processAttributes(hostedAttributes);
    
    assertThat(result, containsString("Hosted Storage: default"));
    assertThat(result, containsString("strict validation: true"));
    assertThat(result, containsString("write policy: ALLOW"));
  }
  
  /**
   * Tests pattern matching with cleanup policy attributes.
   */
  @Test
  public void testCleanupPolicyAttributePatternMatching() {
    // Create a test repository
    TestRepository repository = new TestRepository("test-repo", "maven", "hosted", "http://example.com/repo", true);
    
    // Create cleanup policy attributes record
    CleanupPolicyAttributesRecord cleanupAttributes = 
        new CleanupPolicyAttributesRecord(new String[]{"policy1", "policy2"});
    
    // Test pattern matching using the processAttributes method
    String result = repository.processAttributes(cleanupAttributes);
    
    assertThat(result, containsString("Cleanup Policies: policy1, policy2"));
  }
  
  /**
   * Tests nested pattern matching with repository configuration.
   */
  @Test
  public void testNestedPatternMatching() {
    // Create a test repository
    TestRepository repository = new TestRepository("test-repo", "maven", "hosted", "http://example.com/repo", true);
    
    // Create a nested configuration map
    Map<String, Object> config = new HashMap<>();
    config.put("repository", repository.toAttributes());
    
    // Test nested pattern matching
    String result = repository.processRepositoryConfig(config);
    
    assertThat(result, containsString("Repository config for test-repo"));
    assertThat(result, containsString("(maven/hosted)"));
  }
  
  /**
   * Tests pattern matching with guards.
   */
  @Test
  public void testPatternMatchingWithGuards() {
    // Create a test repository
    TestRepository repository = new TestRepository("test-repo", "maven", "hosted", "http://example.com/repo", true);
    
    // Create a configuration with cleanup policies
    Map<String, Object> config = new HashMap<>();
    config.put("cleanup", new CleanupPolicyAttributesRecord(new String[]{"policy1", "policy2"}));
    
    // Test pattern matching with guards
    String result = repository.processRepositoryConfig(config);
    
    assertThat(result, containsString("Cleanup config with 2 policies"));
  }
  
  /**
   * Tests extracting attribute values using pattern matching.
   */
  @Test
  public void testExtractAttributeValues() {
    // Create a test repository
    TestRepository repository = new TestRepository("test-repo", "maven", "hosted", "http://example.com/repo", true);
    
    // Create a nested configuration map
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("storage", new StorageAttributesRecord("default", true));
    
    Map<String, Object> config = new HashMap<>();
    config.put("attributes", attributes);
    
    // Extract the blob store name using pattern matching
    Optional<String> blobStoreName = repository.extractAttributeValue(config, attrs -> {
      if (attrs instanceof Map<?, ?> attrMap && attrMap.get("storage") instanceof StorageAttributesRecord(String name, var strict)) {
        return name;
      }
      return null;
    });
    
    assertTrue(blobStoreName.isPresent(), "Blob store name should be extracted");
    assertEquals("default", blobStoreName.get(), "Extracted blob store name should match");
  }
}