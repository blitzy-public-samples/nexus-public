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

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

public class AssetXOTest
    extends TestSupport
{

  @BeforeEach
  public void setup() {
    BaseUrlHolder.set("https://nexus-url", "");
  }

  @ParameterizedTest
  @CsvSource({
      "hosted, /path/to/resource, /hosted/path/to/resource",
      "hosted, path/to/resource, /hosted/path/to/resource"
  })
  public void testFrom(String repositoryName, String path, String expectedUrl) throws Exception {
    Repository repository = createRepository(new HostedType(), repositoryName);
    AssetSearchResult assetSearchResult = Mockito.mock(AssetSearchResult.class);
    when(assetSearchResult.getPath()).thenReturn(path);
    when(assetSearchResult.getId()).thenReturn("resource-id");
    when(assetSearchResult.getFormat()).thenReturn("test-format");
    AssetXO assetXO = AssetXO.from(assetSearchResult, repository, null);
    assertTrue(assetXO.getDownloadUrl().contains(expectedUrl));
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

    assertEquals(1, result.size());
    assertTrue(result.containsKey("test-format"));
    Map<String, Object> resultFormatAttributes = (Map<String, Object>) result.get("test-format");
    assertEquals(1, resultFormatAttributes.size());
    assertEquals("value1", resultFormatAttributes.get("key1"));
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

    assertEquals(1, result.size());
    assertTrue(result.containsKey("test-format"));
    Map<String, Object> resultFormatAttributes = (Map<String, Object>) result.get("test-format");
    assertTrue(resultFormatAttributes.isEmpty());
  }

  @Test
  public void testGetExpandedAttributes_withNullDescriptors() {
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
  public void testRecordPatternWithAssetXO() {
    // Create an AssetXO instance with specific properties
    AssetXO assetXO = AssetXO.builder()
        .path("/test/path")
        .downloadUrl("https://nexus-url/test/path")
        .id("test-id")
        .repository("test-repo")
        .format("test-format")
        .contentType("application/json")
        .lastModified(new Date())
        .build();
    
    // Using Java 21 record pattern matching to extract fields
    if (assetXO instanceof AssetXO(var path, var id, var repository, var format, var contentType)) {
      // Verify extracted fields match expected values
      assertEquals("/test/path", path);
      assertEquals("test-id", id);
      assertEquals("test-repo", repository);
      assertEquals("test-format", format);
      assertEquals("application/json", contentType);
    } else {
      Assertions.fail("Record pattern matching failed");
    }
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

  // Helper record for pattern matching with AssetXO
  private record AssetXO(String path, String id, String repository, String format, String contentType) {
    // This record is used for pattern matching with the AssetXO class
    static AssetXO(org.sonatype.nexus.repository.rest.api.AssetXO assetXO) {
      return new AssetXO(
          assetXO.getPath(),
          assetXO.getId(),
          assetXO.getRepository(),
          assetXO.getFormat(),
          assetXO.getContentType()
      );
    }
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