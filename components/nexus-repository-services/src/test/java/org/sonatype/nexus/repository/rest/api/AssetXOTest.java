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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
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
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

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

import static java.lang.StringTemplate.STR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class AssetXOTest
    extends TestSupport
{

  @BeforeEach
  void setup() {
    BaseUrlHolder.set("https://nexus-url", "");
  }

  @ParameterizedTest
  @MethodSource("repositoryPathProvider")
  void testFrom(String repositoryName, String path, String expectedUrl) throws Exception {
    Repository repository = createRepository(new HostedType(), repositoryName);
    AssetSearchResult assetSearchResult = Mockito.mock(AssetSearchResult.class);
    when(assetSearchResult.getPath()).thenReturn(path);
    when(assetSearchResult.getId()).thenReturn("resource-id");
    when(assetSearchResult.getFormat()).thenReturn("test-format");
    AssetXO assetXO = AssetXO.from(assetSearchResult, repository, null);
    assertTrue(assetXO.getDownloadUrl().contains(expectedUrl));
  }
  
  static Stream<Arguments> repositoryPathProvider() {
    return Stream.of(
        arguments("hosted", "/path/to/resource", "/hosted/path/to/resource"),
        arguments("hosted", "path/to/resource", "/hosted/path/to/resource")
    );
  }

  @Test
  void testGetExpandedAttributes_withExposedKeys() {
    Map<String, Object> attributes = new HashMap<>();
    Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put("key1", "value1");
    formatAttributes.put("key2", "value2");
    attributes.put("test-format", formatAttributes);

    Map<String, AssetXODescriptor> assetDescriptors = new HashMap<>();
    AssetXODescriptor descriptor = new TestAssetXODescriptor(Set.of("key1"));
    assetDescriptors.put("test-format", descriptor);

    Map<String, Object> result = AssetXO.getExpandedAttributes(attributes, "test-format", assetDescriptors);

    assertEquals(1, result.size());
    assertTrue(result.containsKey("test-format"));
    Map<String, Object> resultFormatAttributes = (Map<String, Object>) result.get("test-format");
    assertEquals(1, resultFormatAttributes.size());
    assertEquals("value1", resultFormatAttributes.get("key1"));
  }

  @Test
  void testGetExpandedAttributes_withoutExposedKeys() {
    Map<String, Object> attributes = new HashMap<>();
    Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put("key1", "value1");
    formatAttributes.put("key2", "value2");
    attributes.put("test-format", formatAttributes);

    Map<String, AssetXODescriptor> assetDescriptors = new HashMap<>();
    AssetXODescriptor descriptor = new TestAssetXODescriptor(Set.of());
    assetDescriptors.put("test-format", descriptor);

    Map<String, Object> result = AssetXO.getExpandedAttributes(attributes, "test-format", assetDescriptors);

    assertEquals(1, result.size());
    assertTrue(result.containsKey("test-format"));
    Map<String, Object> resultFormatAttributes = (Map<String, Object>) result.get("test-format");
    assertTrue(resultFormatAttributes.isEmpty());
  }

  @Test
  void testGetExpandedAttributes_withNullDescriptors() {
    Map<String, Object> attributes = new HashMap<>();
    Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put("key1", "value1");
    formatAttributes.put("key2", "value2");
    attributes.put("test-format", formatAttributes);

    Map<String, Object> result = AssetXO.getExpandedAttributes(attributes, "test-format", null);

    assertEquals(1, result.size());
    assertTrue(result.containsKey("test-format"));
    Map<String, Object> resultFormatAttributes = (Map<String, Object>) result.get("test-format");
    assertTrue(resultFormatAttributes.isEmpty());
  }
  
  @Test
  @Category(Java21TestGroup.class)
  void testAssetXOUrlGenerationWithVirtualThreads() throws ExecutionException, InterruptedException {
    Repository repository = createRepository(new HostedType(), "hosted");
    AssetSearchResult assetSearchResult = Mockito.mock(AssetSearchResult.class);
    when(assetSearchResult.getPath()).thenReturn("/path/to/resource");
    when(assetSearchResult.getId()).thenReturn("resource-id");
    when(assetSearchResult.getFormat()).thenReturn("test-format");
    
    // Create a virtual thread to generate the AssetXO
    CompletableFuture<AssetXO> future = CompletableFuture.supplyAsync(
        () -> AssetXO.from(assetSearchResult, repository, null),
        Thread.ofVirtual().factory()
    );
    
    AssetXO assetXO = future.get();
    assertTrue(assetXO.getDownloadUrl().contains("/hosted/path/to/resource"));
  }
  
  @Test
  @Category(Java21TestGroup.class)
  void testStringTemplateInUrlFormatting() throws Exception {
    Repository repository = createRepository(new HostedType(), "hosted");
    AssetSearchResult assetSearchResult = Mockito.mock(AssetSearchResult.class);
    String path = "/path/to/resource";
    when(assetSearchResult.getPath()).thenReturn(path);
    when(assetSearchResult.getId()).thenReturn("resource-id");
    when(assetSearchResult.getFormat()).thenReturn("test-format");
    
    AssetXO assetXO = AssetXO.from(assetSearchResult, repository, null);
    
    // Using String Template to format the expected URL
    String repoName = "hosted";
    String expectedUrl = STR."https://nexus-url/repository/\{repoName}\{path}";
    
    // Verify the download URL contains the expected path
    assertTrue(assetXO.getDownloadUrl().contains(path));
    assertTrue(assetXO.getDownloadUrl().contains(repoName));
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