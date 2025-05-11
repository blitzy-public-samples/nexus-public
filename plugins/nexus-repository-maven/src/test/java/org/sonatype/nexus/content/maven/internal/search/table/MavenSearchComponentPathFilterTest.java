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

import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MavenSearchComponentPathFilter}.
 * 
 * This test validates the path filtering logic for Maven repository search components.
 * The filter determines which file paths should be included or excluded from search results
 * based on their extensions.
 *
 * @since 3.38
 */
@DisplayName("MavenSearchComponentPathFilter Tests")
public class MavenSearchComponentPathFilterTest
    extends TestSupport
{
  private MavenSearchComponentPathFilter underTest;

  @BeforeEach
  public void setup() {
    underTest = new MavenSearchComponentPathFilter();
  }

  @Test
  @DisplayName("Should filter Maven uncommon type extensions")
  public void shouldFilterMavenUncommonType() {
    String path = "foo/bar/foobar.jar.sha1";
    assertTrue(underTest.shouldFilterPathExtension(path), 
        "Path with uncommon extension should be filtered");
  }

  @Test
  @DisplayName("Should not filter Maven common type extensions")
  public void shouldNotFilterMavenCommonTypes() {
    validMavenPaths().forEach(path -> 
        assertFalse(underTest.shouldFilterPathExtension(path),
            "Path with common extension should not be filtered: " + path));
  }

  /**
   * Returns a list of valid Maven artifact paths with common extensions.
   * These extensions represent the standard Maven artifact types that should not be filtered
   * from search results.
   * 
   * @return list of valid Maven paths with common extensions
   */
  private List<String> validMavenPaths() {
    return asList(
        "foo/bar/foobar.jar",
        "foo/bar/foobar.war",
        "foo/bar/foobar.aar",
        "foo/bar/foobar.zip",
        "foo/bar/foobar.pom",
        "foo/bar/foobar.tar.gz");
  }
}
