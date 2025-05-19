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

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sonatype.nexus.virtualthread.Java21TestGroup;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;

@Category(Java21TestGroup.class)
public class EscapeHelperTest
{
  EscapeHelper underTest;

  @Before
  public void setup() {
    underTest = new EscapeHelper();
  }

  @Test
  public void testStripJavaEl() {
    String test = "${badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  public void testStripJavaEl_multiple_dollar_signs() {
    String test = "$$$$${badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  public void testStripJavaEl_bugged_interpolator() {
    String test = "$\\A{badstuffinhere}";
    String result = underTest.stripJavaEl(test);
    assertThat(result, is("{badstuffinhere}"));
  }

  @Test
  public void testUriSegmentsEncoding() {
    assertThat(underTest.uriSegments("foo/bar+baz"), is("foo/bar+baz"));
    assertThat(underTest.uriSegments("foo/bar%baz"), is("foo/bar%25baz"));
    assertThat(underTest.uriSegments("foo/bar baz"), is("foo/bar%20baz"));
    assertThat(underTest.uriSegments("foo:path/bar:baz"), is("foo%3Apath/bar%3Abaz"));
  }
  
  @Test
  public void testStringTemplateIntegration() {
    // Test StringTemplate integration with uri method
    String input = "test space+special&chars";
    String expected = "test%20space%2Bspecial%26chars";
    String result = underTest.uri(input);
    
    // Using both JUnit 4 and Hamcrest 2.2 assertion styles
    assertEquals(expected, result);
    assertThat(result, equalTo(expected));
    
    // Test with direct StringTemplate usage
    String directTemplate = STR."Escaped: \{underTest.uri(input)}";
    assertThat(directTemplate, is("Escaped: " + expected));
  }
  
  @Test
  public void testStringTemplateWithUriSegments() {
    String path = "path/with space/and:colon";
    String expected = "path/with%20space/and%3Acolon";
    String result = underTest.uriSegments(path);
    
    assertThat(result, is(expected));
    
    // Test with nested StringTemplate usage
    String template = STR."Segments: \{underTest.uriSegments(path)}";
    assertThat(template, is("Segments: " + expected));
  }
}
