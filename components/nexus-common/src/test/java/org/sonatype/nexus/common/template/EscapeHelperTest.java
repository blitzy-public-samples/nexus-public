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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class EscapeHelperTest
{
  EscapeHelper underTest;

  @BeforeEach
  void setup() {
    underTest = new EscapeHelper();
  }

  @Test
  @DisplayName("Strip Java EL expressions from string")
  void testStripJavaEl() {
    String test = "${badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  @DisplayName("Strip Java EL expressions with multiple dollar signs")
  void testStripJavaEl_multiple_dollar_signs() {
    String test = "$$$$${badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  @DisplayName("Strip Java EL expressions with bugged interpolator syntax")
  void testStripJavaEl_bugged_interpolator() {
    String test = "$\\A{badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  @DisplayName("Test URI segments encoding for various characters")
  void testUriSegmentsEncoding() {
    assertThat(underTest.uriSegments("foo/bar+baz"), is("foo/bar+baz"));
    assertThat(underTest.uriSegments("foo/bar%baz"), is("foo/bar%25baz"));
    assertThat(underTest.uriSegments("foo/bar baz"), is("foo/bar%20baz"));
    assertThat(underTest.uriSegments("foo:path/bar:baz"), is("foo%3Apath/bar%3Abaz"));
  }
  
  /**
   * Test to ensure that Java 21 string template expressions (\{...}) are not affected by stripJavaEl.
   * This is important as Java 21 introduces string templates which use a different syntax than Java EL.
   */
  @Test
  @DisplayName("Ensure Java 21 string template expressions are preserved")
  void testStripJavaEl_preservesJava21StringTemplates() {
    // Java 21 string template syntax example (represented as a regular string for the test)
    String javaTemplateExample = "\\{name} is \\{age} years old";
    String result = underTest.stripJavaEl(javaTemplateExample);
    
    // The method should not modify Java 21 string template expressions
    assertThat(result, is(javaTemplateExample));
  }
  
  /**
   * Test to ensure that stripJavaEl correctly handles a mix of Java EL expressions and
   * Java 21 string template syntax in the same string.
   */
  @Test
  @DisplayName("Handle mixed Java EL and Java 21 string template syntax")
  void testStripJavaEl_mixedWithJava21Syntax() {
    // String with both Java EL and Java 21 template syntax
    String mixedSyntax = "${javaEl} and \\{java21Template}";
    String result = underTest.stripJavaEl(mixedSyntax);
    
    // Only the Java EL expression should be modified
    assertThat(result, is("{javaEl} and \\{java21Template}"));
  }
}
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class EscapeHelperTest
{
  EscapeHelper underTest;

  @BeforeEach
  void setup() {
    underTest = new EscapeHelper();
  }

  @Test
  @DisplayName("Strip Java EL expressions from string")
  void testStripJavaEl() {
    String test = "${badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  @DisplayName("Strip Java EL expressions with multiple dollar signs")
  void testStripJavaEl_multiple_dollar_signs() {
    String test = "$$$$${badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  @DisplayName("Strip Java EL expressions with bugged interpolator syntax")
  void testStripJavaEl_bugged_interpolator() {
    String test = "$\\A{badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  @DisplayName("Test URI segments encoding for various characters")
  void testUriSegmentsEncoding() {
    assertThat(underTest.uriSegments("foo/bar+baz"), is("foo/bar+baz"));
    assertThat(underTest.uriSegments("foo/bar%baz"), is("foo/bar%25baz"));
    assertThat(underTest.uriSegments("foo/bar baz"), is("foo/bar%20baz"));
    assertThat(underTest.uriSegments("foo:path/bar:baz"), is("foo%3Apath/bar%3Abaz"));
  }
  
  /**
   * Test to ensure that Java 21 string template expressions (\{...}) are not affected by stripJavaEl.
   * This is important as Java 21 introduces string templates which use a different syntax than Java EL.
   */
  @Test
  @DisplayName("Ensure Java 21 string template expressions are preserved")
  void testStripJavaEl_preservesJava21StringTemplates() {
    // Java 21 string template syntax example (represented as a regular string for the test)
    String javaTemplateExample = "\\{name} is \\{age} years old";
    String result = underTest.stripJavaEl(javaTemplateExample);
    
    // The method should not modify Java 21 string template expressions
    assertThat(result, is(javaTemplateExample));
  }
  
  /**
   * Test to ensure that stripJavaEl correctly handles a mix of Java EL expressions and
   * Java 21 string template syntax in the same string.
   */
  @Test
  @DisplayName("Handle mixed Java EL and Java 21 string template syntax")
  void testStripJavaEl_mixedWithJava21Syntax() {
    // String with both Java EL and Java 21 template syntax
    String mixedSyntax = "${javaEl} and \\{java21Template}";
    String result = underTest.stripJavaEl(mixedSyntax);
    
    // Only the Java EL expression should be modified
    assertThat(result, is("{javaEl} and \\{java21Template}"));
  }
}