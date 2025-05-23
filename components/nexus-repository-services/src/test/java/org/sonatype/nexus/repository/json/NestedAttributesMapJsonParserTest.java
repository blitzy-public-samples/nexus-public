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
package org.sonatype.nexus.repository.json;

import java.io.IOException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.collect.NestedAttributesMap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Maps.newHashMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Category(Java21TestGroup.class)
@ExtendWith(MockitoExtension.class)
public class NestedAttributesMapJsonParserTest
    extends TestSupport
{
  @Mock
  private JsonParser jsonParser;

  private NestedAttributesMap nestedAttributesMap = new NestedAttributesMap("testMap", newHashMap());

  private NestedAttributesMapJsonParser underTest;

  @BeforeEach
  public void setUp() {
    underTest = new NestedAttributesMapJsonParser(jsonParser, nestedAttributesMap);
  }

  @Test
  void defaultMappingDisabledByDefault() {
    assertFalse(underTest.isDefaultMapping());
  }

  @Test
  void changeDefaultMapping() {
    underTest.enableDefaultMapping();
    assertTrue(underTest.isDefaultMapping());

    underTest.disableDefaultMapping();
    assertFalse(underTest.isDefaultMapping());
  }

  @Test
  void mappingInsideArrayDisabledByDefault() {
    assertFalse(underTest.isMappingInsideArray());
  }

  @Test
  void markMappingInsideArray() {
    underTest.markMappingInsideArray();
    assertTrue(underTest.isMappingInsideArray());

    underTest.unMarkMappingInsideArray();
    assertFalse(underTest.isMappingInsideArray());
  }

  @Test
  void assureSameRootMap() {
    assertEquals(underTest.getRoot(), nestedAttributesMap);
  }

  @Test
  void noChildMapFromRootMapOnMappingInsideArray() {
    underTest.markMappingInsideArray();
    assertThat(underTest.getChildFromRoot(), nullValue());
  }

  @Test
  void noChildMapFromRootMapOnNoChildFound() {
    assertThat(underTest.getChildFromRoot(), nullValue());
  }

  @Test
  void retrieveChildMapFromRootMap() throws IOException {
    String simpleJson = "{\"user\":{\"description\":\"simplestuff\"}}";
    underTest = new NestedAttributesMapJsonParser(new JsonFactory().createParser(simpleJson), nestedAttributesMap);

    // for the test fast forward to first chid
    underTest.nextValue();
    underTest.nextValue();
    NestedAttributesMap child = underTest.getChildFromRoot();
    assertThat(child.getKey(), equalTo("user"));

    underTest.nextValue();
    child = underTest.getChildFromRoot();
    assertThat(child.getKey(), equalTo("description"));
  }
}