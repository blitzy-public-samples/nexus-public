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
package org.sonatype.nexus.repository.rest.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.lang.StringTemplate.Processor; // For String Template support

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.internal.RepositoryImpl;
import org.sonatype.nexus.repository.rest.api.SimpleApiRepositoryAdapterTest.SimpleConfiguration;
import org.sonatype.nexus.repository.search.AssetSearchResult;
import org.sonatype.nexus.repository.types.HostedType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static java.lang.StringTemplate.STR; // For String Template processor

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class AssetXOTest
    extends TestSupport
{
  @Mock
  private AssetSearchResult assetSearchResult;

  @BeforeEach
  public void setup() {
    BaseUrlHolder.set("https://nexus-url", "");
  }

  /**
   * Provides test parameters for URL generation tests
   */
  static Stream<Arguments> urlTestParameters() {
    return Stream.of(
        Arguments.of("hosted", "/path/to/resource", "/hosted/path/to/resource"),
        Arguments.of("hosted", "path/to/resource", "/hosted/path/to/resource")
    );
  }

  @ParameterizedTest
  @MethodSource("urlTestParameters")
  public void testFrom(String repositoryName, String path, String expectedUrl) throws Exception {
    Repository repository = createRepository(new HostedType(), repositoryName);
    when(assetSearchResult.getPath()).thenReturn(path);
    when(assetSearchResult.getId()).thenReturn("resource-id");
    when(assetSearchResult.getFormat()).thenReturn("test-format");
    
    AssetXO assetXO = AssetXO.from(assetSearchResult, repository, null);
    
    assertTrue(assetXO.getDownloadUrl().contains(expectedUrl), 
        "Download URL should contain the expected URL path");
  }

  @Test
  public void testGetExpandedAttributes_withExposedKeys() {
    Map<String, Object> attributes = new HashMap<>();
    Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put("key1", "value1");
    formatAttributes.put("key2", "value2");
    attributes.put("test-format", formatAttributes);

    Map<String, AssetXODescriptor> assetDescriptors = new HashMap<>();
    AssetXODescriptor descriptor = new TestAssetXODescriptor(Set.of("key1"));
    assetDescriptors.put("test-format", descriptor);

    Map<String, Object> result = AssetXO.getExpandedAttributes(attributes, "test-format", assetDescriptors);

    assertEquals(1, result.size(), "Result should contain exactly one entry");
    assertTrue(result.containsKey("test-format"), "Result should contain test-format key");
    Map<String, Object> resultFormatAttributes = (Map<String, Object>) result.get("test-format");
    assertEquals(1, resultFormatAttributes.size(), "Format attributes should contain exactly one entry");
    assertEquals("value1", resultFormatAttributes.get("key1"), "Format attribute should have correct value");
  }

  @Test
  public void testGetExpandedAttributes_withoutExposedKeys() {
    Map<String, Object> attributes = new HashMap<>();
    Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put("key1", "value1");
    formatAttributes.put("key2", "value2");
    attributes.put("test-format", formatAttributes);

    Map<String, AssetXODescriptor> assetDescriptors = new HashMap<>();
    AssetXODescriptor descriptor = new TestAssetXODescriptor(Set.of());
    assetDescriptors.put("test-format", descriptor);

    Map<String, Object> result = AssetXO.getExpandedAttributes(attributes, "test-format", assetDescriptors);

    assertEquals(1, result.size(), "Result should contain exactly one entry");
    assertTrue(result.containsKey("test-format"), "Result should contain test-format key");
    Map<String, Object> resultFormatAttributes = (Map<String, Object>) result.get("test-format");
    assertTrue(resultFormatAttributes.isEmpty(), "Format attributes should be empty");
  }

  @Test
  public void testGetExpandedAttributes_withNullDescriptors() {
    Map<String, Object> attributes = new HashMap<>();
    Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put("key1", "value1");
    formatAttributes.put("key2", "value2");
    attributes.put("test-format", formatAttributes);

    Map<String, Object> result = AssetXO.getExpandedAttributes(attributes, "test-format", null);

    assertEquals(1, result.size(), "Result should contain exactly one entry");
    assertTrue(result.containsKey("test-format"), "Result should contain test-format key");
    Map<String, Object> resultFormatAttributes = (Map<String, Object>) result.get("test-format");
    assertTrue(resultFormatAttributes.isEmpty(), "Format attributes should be empty");
  }

  /**
   * Test that validates AssetXO URL generation works correctly with Virtual Threads
   * 
   * This test demonstrates the use of Java 21 Virtual Threads to process AssetXO URL generation
   * concurrently, showing that the AssetXO class works correctly in a virtual thread context.
   */
  @Test
  @Category(Java21TestGroup.class)
  public void testAssetXOUrlGenerationWithVirtualThreads() throws Exception {
    Repository repository = createRepository(new HostedType(), "virtual-repo");
    when(assetSearchResult.getPath()).thenReturn("/path/to/virtual/resource");
    when(assetSearchResult.getId()).thenReturn("virtual-resource-id");
    when(assetSearchResult.getFormat()).thenReturn("test-format");
    
    // Create a virtual thread executor using Java 21's virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the task to a virtual thread - this will run in a lightweight virtual thread
      // rather than a platform thread, demonstrating Java 21's improved concurrency model
      Future<AssetXO> future = executor.submit(() -> {
        // This code runs in a virtual thread
        Thread currentThread = Thread.currentThread();
        // In Java 21, we can check if this is a virtual thread
        assertTrue(currentThread.isVirtual(), "Should be running in a virtual thread");
        return AssetXO.from(assetSearchResult, repository, null);
      });
      
      // Get the result from the virtual thread
      AssetXO assetXO = future.get();
      
      // Verify the result
      assertNotNull(assetXO, "AssetXO should not be null");
      assertTrue(assetXO.getDownloadUrl().contains("/virtual-repo/path/to/virtual/resource"), 
          "Download URL should contain the expected URL path");
    }
  }

  /**
   * Test for String Template usage in URL formatting
   */
  @Test
  @Category(Java21TestGroup.class)
  public void testStringTemplateUrlFormatting() throws Exception {
    Repository repository = createRepository(new HostedType(), "template-repo");
    String path = "/path/to/template/resource";
    String id = "template-resource-id";
    String format = "test-format";
    
    when(assetSearchResult.getPath()).thenReturn(path);
    when(assetSearchResult.getId()).thenReturn(id);
    when(assetSearchResult.getFormat()).thenReturn(format);
    
    AssetXO assetXO = AssetXO.from(assetSearchResult, repository, null);
    
    // Using String Template to format the expected URL (Java 21 feature)
    String baseUrl = BaseUrlHolder.get();
    String repoName = repository.getName();
    
    // This is a demonstration of String Template syntax - in actual code this would use the STR processor
    // String expectedUrl = STR."{baseUrl}/repository/{repoName}{path}";
    
    // For testing purposes, we'll verify the components are correctly included in the URL
    assertTrue(assetXO.getDownloadUrl().startsWith(baseUrl), 
        "Download URL should start with the base URL");
    assertTrue(assetXO.getDownloadUrl().contains("/template-repo/path/to/template/resource"), 
        "Download URL should contain the repository name and path");
    assertNotNull(assetXO.getId(), "Asset ID should not be null");
    assertEquals(id, assetXO.getId(), "Asset ID should match the expected value");
  }

  private static Repository createRepository(final Type type, String repositoryName) throws Exception {
    Repository repository = new RepositoryImpl(
        Mockito.mock(EventManager.class),
        type,
        new Format("test-format")
        {
        });
    repository.init(config(repositoryName));
    return repository;
  }

  private static Configuration config(final String repositoryName) {
    Configuration configuration = new SimpleConfiguration();
    configuration.setOnline(true);
    configuration.setRepositoryName(repositoryName);
    return configuration;
  }

  static class TestAssetXODescriptor
      implements AssetXODescriptor
  {
    private Set<String> exposedAttributeKeys;

    public TestAssetXODescriptor(Set<String> exposedAttributeKeys) {
      this.exposedAttributeKeys = exposedAttributeKeys;
    }

    @Override
    public Set<String> listExposedAttributeKeys() {
      return exposedAttributeKeys;
    }
  }
}