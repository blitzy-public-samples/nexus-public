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
package org.sonatype.nexus.repository.rest.internal.resources;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.rest.SearchMapping;
import org.sonatype.nexus.repository.search.AssetSearchResult;
import org.sonatype.nexus.repository.search.ComponentSearchResult;
import org.sonatype.nexus.repository.search.SearchUtils;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.ImmutableMap.of;
import static java.util.Optional.empty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.rest.internal.resources.SearchResultFilterUtils.getValueFromAssetMap;

@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(Java21TestGroup.class)
public class SearchResultFilterUtilsTest
    extends TestSupport
{
  private static final String CLASSIFIER_ATTRIBUTE_NAME = "assets.attributes.maven2.classifier";

  private static final String EXTENSION_ATTRIBUTE_NAME = "assets.attributes.maven2.extension";

  private static final String ARTIFACT_ID_ATTRIBUTE_NAME = "assets.attributes.maven2.artifactId";

  private AssetSearchResult asset;

  private AssetSearchResult assetWithClassifier;

  private ComponentSearchResult component;

  private SearchResultFilterUtils underTest;

  @Mock
  private Repository repository;

  @Mock
  private SearchUtils searchUtils;

  @Mock
  private SearchMapping extMapping;

  @Mock
  private SearchMapping descriptionMapping;

  @BeforeEach
  void setup() {
    when(repository.getUrl()).thenReturn("http://localhost/repository/maven/");

    asset = createAsset("antlr.jar", "maven2", "first-sha1", of("extension", "jar"));
    assetWithClassifier =
        createAsset("antlr-sources.jar", "maven2", "first-sha1",
            of("extension", "jar",
                "classifier", "sources",
                "description", "Reindeer have antlrs"));

    component = new ComponentSearchResult();
    component.setAssets(Arrays.asList(asset, assetWithClassifier));

    when(extMapping.getAttribute()).thenReturn("assets.attributes.maven2.extension");
    when(extMapping.isExactMatch()).thenReturn(true);
    when(descriptionMapping.getAttribute()).thenReturn("assets.attributes.maven2.description");
    when(descriptionMapping.isExactMatch()).thenReturn(false);
    List<SearchMapping> mappings = Arrays.asList(extMapping, descriptionMapping);

    underTest = new SearchResultFilterUtils(searchUtils, mappings);
  }

  @Test
  void shouldGetValueFromAssetMapForSha1() {
    runGetValueFromAssetMapTest(asset, "assets.attributes.checksum.sha1", "first-sha1");
  }

  @Test
  void shouldGetValueFromAssetMapForMavenExtension() {
    runGetValueFromAssetMapTest(asset, "assets.attributes.maven2.extension", "jar");
  }

  @Test
  void shouldKeepAssetWithPartialMatch() {
    assertThat(underTest.keepAsset(assetWithClassifier, "assets.attributes.maven2.description", "HAVE"),
        is(true));
  }

  @Test
  void shouldReturnEmptyForBadQueryParam() {
    runGetValueFromAssetMapTest(asset, "junk", null);
  }

  @Test
  void shouldReturnEmptyForBadQueryParam2() {
    runGetValueFromAssetMapTest(asset, "junk.junk", null);
  }

  @Test
  void shouldReturnEmptyForMissingIdentifier() {
    runGetValueFromAssetMapTest(asset, null, null);
  }

  @Test
  void shouldReturnEmptyForEmptyMap() {
    runGetValueFromAssetMapTest(new AssetSearchResult(), "assets.attributes.checksum.sha1", null);
  }

  @Test
  void shouldReturnClassifierWhenIncludeClassifierFlagReturnsOne() {
    Optional<Object> value = getValueFromAssetMap(assetWithClassifier, CLASSIFIER_ATTRIBUTE_NAME);
    assertTrue(value.isPresent());
    assertThat(value.get(), equalTo("sources"));
  }

  @Test
  void shouldReturnExtensionWhenIncludeClassifierFlag() {
    Optional<Object> value = getValueFromAssetMap(asset, EXTENSION_ATTRIBUTE_NAME);
    assertTrue(value.isPresent());
    assertThat(value.get(), equalTo("jar"));
  }

  @Test
  void shouldFilterAssetWhenAssetMapClassifierMatchesAssetParamClassifier() {
    when(searchUtils.getFullAssetAttributeName(any(String.class))).thenReturn(CLASSIFIER_ATTRIBUTE_NAME);
    Map<String, String>  assetParams = new HashMap<>();
    assetParams.put(CLASSIFIER_ATTRIBUTE_NAME, "sources");

    assertTrue(underTest.filterAsset(assetWithClassifier, assetParams));
  }

  @Test
  void shouldFilterAssetWhenAssetMapNoClassifierMatchesAssetParamNoClassifier() {
    when(searchUtils.getFullAssetAttributeName(any(String.class))).thenReturn(EXTENSION_ATTRIBUTE_NAME);
    Map<String, String>  assetParams = new HashMap<>();
    assetParams.put(EXTENSION_ATTRIBUTE_NAME, "jar");

    assertTrue(underTest.filterAsset(asset, assetParams));
  }

  @Test
  void shouldNotFilterAssetWhenAssetMapNoClassifierWithAssetParamClassifier() {
    when(searchUtils.getFullAssetAttributeName(any(String.class))).thenReturn(CLASSIFIER_ATTRIBUTE_NAME);
    Map<String, String>  assetParams = new HashMap<>();
    assetParams.put(CLASSIFIER_ATTRIBUTE_NAME, "sources");

    assertFalse(underTest.filterAsset(asset, assetParams));
  }

  @Test
  void shouldNotFilterAssetWhenAssetMapClassifierWithAssetParamNoClassifier() {
    when(searchUtils.getFullAssetAttributeName(any(String.class))).thenReturn(EXTENSION_ATTRIBUTE_NAME);
    Map<String, String>  assetParams = new HashMap<>();
    assetParams.put(EXTENSION_ATTRIBUTE_NAME, "sources");

    assertFalse(underTest.filterAsset(assetWithClassifier, assetParams));
  }

  @Test
  void shouldFilterComponentWhenAssetMapNoClassifierWithAssetParamEmptyClassifier() {
    when(searchUtils.getFullAssetAttributeName(any(String.class))).thenReturn(CLASSIFIER_ATTRIBUTE_NAME);
    MultivaluedMap<String, String>  assetParams = new MultivaluedHashMap<>();
    assetParams.add(CLASSIFIER_ATTRIBUTE_NAME, "");

    List<?> assets = underTest.filterComponentAssets(component, assetParams).collect(Collectors.toList());
    assertThat(assets, hasSize(1));
  }

  @Test
  void shouldNotFilterAssetWhenAssetMapClassifierWithAssetParamEmptyClassifier() {
    when(searchUtils.getFullAssetAttributeName(any(String.class))).thenReturn(CLASSIFIER_ATTRIBUTE_NAME);
    Map<String, String>  assetParams = new HashMap<>();
    assetParams.put(CLASSIFIER_ATTRIBUTE_NAME, "");

    assertFalse(underTest.filterAsset(assetWithClassifier, assetParams));
  }

  @Test
  void shouldFilterAssetWhenAssetMapClassifierWithEmptyAssetParam() {
    when(searchUtils.getFullAssetAttributeName(any(String.class))).thenReturn(CLASSIFIER_ATTRIBUTE_NAME);
    Map<String, String>  assetParams = new HashMap<>();

    assertTrue(underTest.filterAsset(assetWithClassifier, assetParams));
  }

  @Test
  void shouldFilterAssetWithMultipleAssetParams() {
    when(searchUtils.getFullAssetAttributeName(CLASSIFIER_ATTRIBUTE_NAME)).thenReturn(CLASSIFIER_ATTRIBUTE_NAME);
    when(searchUtils.getFullAssetAttributeName(EXTENSION_ATTRIBUTE_NAME)).thenReturn(EXTENSION_ATTRIBUTE_NAME);
    Map<String, String>  assetParams = new HashMap<>();
    assetParams.put(CLASSIFIER_ATTRIBUTE_NAME, "sources");
    assetParams.put(EXTENSION_ATTRIBUTE_NAME, "jar");

    assertTrue(underTest.filterAsset(assetWithClassifier, assetParams));
    assertFalse(underTest.filterAsset(asset, assetParams));
  }

  @Test
  void shouldGetEmptyAssetParams() {
    when(searchUtils.getFullAssetAttributeName(any(String.class))).thenReturn(any(String.class));

    Map<String, String> params = ImmutableMap.of(CLASSIFIER_ATTRIBUTE_NAME, "", EXTENSION_ATTRIBUTE_NAME, "jar",
        ARTIFACT_ID_ATTRIBUTE_NAME, "foo");
    List<String> emptyAssetParamsList = underTest.getEmptyAssetParams(params);

    assertThat(emptyAssetParamsList.size(), equalTo(1));
  }

  @Test
  void shouldGetNonEmptyAssetParams() {
    when(searchUtils.getFullAssetAttributeName(CLASSIFIER_ATTRIBUTE_NAME)).thenReturn(CLASSIFIER_ATTRIBUTE_NAME);
    when(searchUtils.getFullAssetAttributeName(EXTENSION_ATTRIBUTE_NAME)).thenReturn(EXTENSION_ATTRIBUTE_NAME);
    when(searchUtils.getFullAssetAttributeName(ARTIFACT_ID_ATTRIBUTE_NAME)).thenReturn(ARTIFACT_ID_ATTRIBUTE_NAME);
    Map<String, String> nonEmptyAssetParamsList = underTest.getNonEmptyAssetParams(getPopulatedMultiValueMap());

    assertThat(nonEmptyAssetParamsList.size(), equalTo(2));
    assertTrue(nonEmptyAssetParamsList.containsKey(ARTIFACT_ID_ATTRIBUTE_NAME));
    assertThat(nonEmptyAssetParamsList.get(ARTIFACT_ID_ATTRIBUTE_NAME), equalTo("foo"));
    assertTrue(nonEmptyAssetParamsList.containsKey(EXTENSION_ATTRIBUTE_NAME));
    assertThat(nonEmptyAssetParamsList.get(EXTENSION_ATTRIBUTE_NAME), equalTo("jar"));
    assertFalse(nonEmptyAssetParamsList.containsKey(CLASSIFIER_ATTRIBUTE_NAME));
  }

  /**
   * Test for Record Pattern usage in search result filtering
   */
  @Test
  void shouldUseRecordPatternForSwitchExpression() {
    // Test the switch expression with pattern matching in getValueFromAssetMap
    // This tests the Java 21 feature where the method uses pattern matching in switch
    AssetSearchResult testAsset = createAsset("test.jar", "maven2", "test-sha1", of("extension", "jar"));
    
    // Test path attribute access via pattern matching
    Optional<Object> pathValue = getValueFromAssetMap(testAsset, "assets.path");
    assertTrue(pathValue.isPresent());
    assertThat(pathValue.get(), equalTo("test.jar"));
    
    // Test format attribute access via pattern matching
    Optional<Object> formatValue = getValueFromAssetMap(testAsset, "assets.format");
    assertTrue(formatValue.isPresent());
    assertThat(formatValue.get(), equalTo("maven2"));
    
    // Test nested attributes access via pattern matching
    Optional<Object> checksumValue = getValueFromAssetMap(testAsset, "assets.attributes.checksum.sha1");
    assertTrue(checksumValue.isPresent());
    assertThat(checksumValue.get(), equalTo("test-sha1"));
  }
  
  private Map<String, String> getPopulatedMultiValueMap() {
    Map<String, String>  assetParams = new HashMap<>();
    assetParams.put(CLASSIFIER_ATTRIBUTE_NAME, "");
    assetParams.put(EXTENSION_ATTRIBUTE_NAME, "jar");
    assetParams.put(ARTIFACT_ID_ATTRIBUTE_NAME, "foo");
    return assetParams;
  }

  private void runGetValueFromAssetMapTest(final AssetSearchResult assetMap, final String query, final String match) {
    Optional<Object> value = getValueFromAssetMap(assetMap, query);
    if (match == null) {
      assertThat(value, equalTo(empty()));
    }
    else {
      assertTrue(value.isPresent());
      assertThat(value.get(), equalTo(match));
    }
  }

  private static AssetSearchResult createAsset(
      final String name,
      final String format,
      final String sha1,
      final Map<String, Object> formatAttributes)
  {
    return createAsset(name, format, "maven-central", sha1, formatAttributes);
  }

  private static AssetSearchResult createAsset(
      final String name,
      final String format,
      final String repositoryName,
      final String sha1,
      final Map<String, Object> formatAttributes)
  {
    AssetSearchResult asset = new AssetSearchResult();
    asset.setPath(name);
    asset.setFormat(format);
    asset.setChecksum(of("sha1", sha1));
    asset.setId(UUID.randomUUID().toString());
    asset.setRepository(repositoryName);
    Map<String, Object> attributes = of("cache", of("last_verified", 1234), "checksum", of("sha1", sha1), format,
        formatAttributes);
    asset.setAttributes(attributes);
    return asset;
  }
}
