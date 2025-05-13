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
package org.sonatype.nexus.common.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for {@link EscapeHelper}.
 * 
 * Updated for Java 21 compatibility using JUnit Jupiter.
 */
public class EscapeHelperTest
{
  private EscapeHelper underTest;

  @BeforeEach
  public void setup() {
    underTest = new EscapeHelper();
  }

  @Test
  @DisplayName("Should strip Java EL syntax from string")
  public void stripJavaEl() {
    String test = "${badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  @DisplayName("Should strip Java EL syntax with multiple dollar signs")
  public void stripJavaElWithMultipleDollarSigns() {
    String test = "$$$$${badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  @DisplayName("Should strip Java EL syntax with bugged interpolator")
  public void stripJavaElWithBuggedInterpolator() {
    String test = "$\\A{badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  @DisplayName("Should properly encode URI segments")
  public void uriSegmentsEncoding() {
    assertThat(underTest.uriSegments("foo/bar+baz"), is("foo/bar+baz"));
    assertThat(underTest.uriSegments("foo/bar%baz"), is("foo/bar%25baz"));
    assertThat(underTest.uriSegments("foo/bar baz"), is("foo/bar%20baz"));
    assertThat(underTest.uriSegments("foo:path/bar:baz"), is("foo%3Apath/bar%3Abaz"));
  }
  
  @Test
  @DisplayName("Should handle Java 21 string template syntax")
  public void handleStringTemplateSyntax() {
    String templateSyntax = "\\{variable}";
    String result = underTest.stripJavaEl(templateSyntax);
    assertThat(result, is("{variable}"));
    
    // Test with string template-like syntax
    String stringTemplateLike = "text \\{expression}";
    result = underTest.stripJavaEl(stringTemplateLike);
    assertThat(result, is("text {expression}"));
  }
}