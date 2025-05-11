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
package org.sonatype.nexus.coreui;

import java.util.HashMap;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;

// JUnit Jupiter (JUnit 5) imports - updated for Java 21 compatibility
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Hamcrest 2.2 compatible imports
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.sonatype.nexus.coreui.ConanAttributeTransformer.CONAN_FORMAT;
import static org.sonatype.nexus.coreui.ConanAttributeTransformer.INFO_BINARY_ATTRIBUTE;

/**
 * Tests for {@link ConanAttributeTransformer}.
 * 
 * Updated for Java 21 compatibility and migrated to JUnit Jupiter (JUnit 5.10.1).
 */
public class ConanAttributeTransformerTest
    extends TestSupport
{
  private static final String JSON = "{\"settings\":{\"os\":\"Windows\",\"compiler.runtime_type\":\"Release\",\"compiler.runtime\":\"dynamic\",\"arch\":\"x86_64\",\"compiler\":\"msvc\",\"build_type\":\"Release\",\"mysub\":{\"foo\":\"bar\"},\"compiler.version\":\"193\"},\"options\":{\"shared\":\"True\"}}";

  private static final String INVALID_JSON = "{\"settings\":{{\"os\":\"Windows\"}}";

  private ConanAttributeTransformer underTest;

  @BeforeEach // Updated from JUnit 4's @Before to JUnit Jupiter's @BeforeEach
  public void setUp() {
    underTest = new ConanAttributeTransformer();
  }

  @Test // Using JUnit Jupiter's @Test annotation
  public void testTransform() {
    AssetXO assetXO = getAssetXO(JSON);
    underTest.transform(assetXO);

    Map<String, Object> result = (Map<String, Object>) assetXO.getAttributes().get(CONAN_FORMAT);

    assertThat(result.size(), equalTo(9));
    assertThat(result, not(hasKey(INFO_BINARY_ATTRIBUTE)));
    assertThat(result, hasKey("settings.os"));
    assertThat(result, hasKey("settings.compiler.runtime_type"));
    assertThat(result, hasKey("settings.compiler.runtime"));
    assertThat(result, hasKey("settings.arch"));
    assertThat(result, hasKey("settings.compiler"));
    assertThat(result, hasKey("settings.build_type"));
    assertThat(result, hasKey("settings.mysub.foo"));
    assertThat(result, hasKey("settings.compiler.version"));
    assertThat(result, hasKey("options.shared"));
    assertThat(result, hasEntry("settings.os", "Windows"));
    assertThat(result, hasEntry("settings.compiler.runtime_type", "Release"));
    assertThat(result, hasEntry("settings.compiler.runtime", "dynamic"));
    assertThat(result, hasEntry("settings.arch", "x86_64"));
    assertThat(result, hasEntry("settings.compiler", "msvc"));
    assertThat(result, hasEntry("settings.build_type", "Release"));
    assertThat(result, hasEntry("settings.mysub.foo", "bar"));
    assertThat(result, hasEntry("settings.compiler.version", "193"));
    assertThat(result, hasEntry("options.shared", "True"));
  }

  @Test // Using JUnit Jupiter's @Test annotation
  public void testTransformInvalidJson() {
    AssetXO assetXO = getAssetXO(INVALID_JSON);
    underTest.transform(assetXO);

    Map<String, Object> result = (Map<String, Object>) assetXO.getAttributes().get(CONAN_FORMAT);

    assertThat(result, hasKey(INFO_BINARY_ATTRIBUTE));
    assertThat(result.size(), equalTo(1));
  }

  @Test // Using JUnit Jupiter's @Test annotation
  public void testTransformNoInfoBinary() {
    AssetXO assetXO = new AssetXO();
    Map<String, Object> attributes = new HashMap<>();
    Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put("foo", "bar");
    attributes.put(CONAN_FORMAT, formatAttributes);
    assetXO.setAttributes(attributes);
    assetXO.setFormat(CONAN_FORMAT);
    underTest.transform(assetXO);

    Map<String, Object> result = (Map<String, Object>) assetXO.getAttributes().get(CONAN_FORMAT);
    assertThat(result, hasKey("foo"));
    assertThat(result.size(), equalTo(1));
  }

  /**
   * Helper method to create an AssetXO with the given JSON content.
   * 
   * @param json The JSON string to use as the INFO_BINARY_ATTRIBUTE value
   * @return A configured AssetXO instance
   */
  private static AssetXO getAssetXO(final String json) {
    AssetXO assetXO = new AssetXO();
    Map<String, Object> attributes = new HashMap<>();
    Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put(INFO_BINARY_ATTRIBUTE, json);
    attributes.put(CONAN_FORMAT, formatAttributes);
    assetXO.setAttributes(attributes);
    assetXO.setFormat(CONAN_FORMAT);
    return assetXO;
  }
}