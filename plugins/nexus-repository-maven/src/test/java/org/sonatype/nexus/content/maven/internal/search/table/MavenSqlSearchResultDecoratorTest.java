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
package org.sonatype.nexus.content.maven.internal.search.table;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.search.ComponentSearchResult;
import org.sonatype.nexus.repository.search.sql.SearchResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_BASE_VERSION;

/**
 * Tests for {@link MavenSqlSearchResultDecorator} with Java 21 compatibility.
 * <p>
 * This test class has been updated to use JUnit Jupiter (JUnit 5) and is compatible with Java 21.
 * It validates the Maven SQL search result decorator functionality for adding base version annotations
 * to search results when the format is Maven.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
public class MavenSqlSearchResultDecoratorTest
    extends TestSupport
{
  private NestedAttributesMap attributes;

  private NestedAttributesMap childAttributes;

  private MavenSqlSearchResultDecorator underTest;

  @BeforeEach
  public void setup() {
    underTest = new MavenSqlSearchResultDecorator();

    attributes = new NestedAttributesMap();
    childAttributes = attributes.child(Maven2Format.NAME);
  }

  /**
   * Tests that the base version annotation is added when the format is Maven.
   * <p>
   * This test verifies that when processing a Maven format component, the decorator correctly
   * extracts the base version from attributes and adds it as an annotation to the component.
   * </p>
   */
  @Test
  void shouldAddBaseVersionAnnotationWhenFormatIsMaven() {
    String baseVersion = "1.1.1";
    ComponentSearchResult component = aComponentSearchResult(Maven2Format.NAME);
    SearchResult searchResult = aSearchResult();
    childAttributes.set(P_BASE_VERSION, baseVersion);

    underTest.updateComponent(component, searchResult);

    assertThat(component.getAnnotation(P_BASE_VERSION), is(baseVersion));
  }

  /**
   * Tests that the base version annotation is not added when the format is not Maven.
   * <p>
   * This test verifies that when processing a non-Maven format component (e.g., "raw"),
   * the decorator correctly skips adding the base version annotation.
   * </p>
   */
  @Test
  void shouldNotAddBaseVersionAnnotationWhenFormatIsNotMaven() {
    ComponentSearchResult component = aComponentSearchResult("raw");
    SearchResult searchResult = aSearchResult();

    underTest.updateComponent(component, searchResult);

    assertThat(component.getAnnotation(P_BASE_VERSION), is(nullValue()));
  }

  /**
   * Creates a mock SearchResult with the test attributes.
   * <p>
   * This helper method creates a mock SearchResult that returns the test attributes
   * when the attributes() method is called.
   * </p>
   * 
   * @return a mock SearchResult configured for testing
   */
  private SearchResult aSearchResult() {
    SearchResult searchResult = mock(SearchResult.class);
    when(searchResult.attributes()).thenReturn(attributes);
    return searchResult;
  }

  /**
   * Creates a ComponentSearchResult with the specified format.
   * <p>
   * This helper method creates a new ComponentSearchResult and sets its format
   * to the specified value.
   * </p>
   * 
   * @param format the format to set on the component
   * @return a ComponentSearchResult configured for testing
   */
  private ComponentSearchResult aComponentSearchResult(final String format) {
    ComponentSearchResult component = new ComponentSearchResult();
    component.setFormat(format);
    return component;
  }
}
