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
package org.sonatype.nexus.repository.search.normalize;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class VersionNumberExpanderTest
{
  @Test
  public void blank() {
    assertEquals("", VersionNumberExpander.expand(null), "Null input should expand to empty string");
    assertEquals("", VersionNumberExpander.expand(""), "Empty input should expand to empty string");
    assertEquals("", VersionNumberExpander.expand(" "), "Whitespace input should expand to empty string");
  }

  @Test
  public void noExpansion() {
    String input = "alpha";
    assertEquals(input, VersionNumberExpander.expand(input), STR."Input '\{input}' should remain unchanged");
    
    input = "beta release";
    assertEquals(input, VersionNumberExpander.expand(input), STR."Input '\{input}' should remain unchanged");
  }

  @Test
  public void numbers() {
    String input = "1";
    String expected = "000000001";
    assertEquals(expected, VersionNumberExpander.expand(input), STR."Input '\{input}' should expand to '\{expected}'");
    
    input = "23007";
    expected = "000023007";
    assertEquals(expected, VersionNumberExpander.expand(input), STR."Input '\{input}' should expand to '\{expected}'");
    
    input = "1.0.2";
    expected = "000000001.000000000.000000002";
    assertEquals(expected, VersionNumberExpander.expand(input), STR."Input '\{input}' should expand to '\{expected}'");
    
    input = "1.23.4";
    expected = "000000001.000000023.000000004";
    assertEquals(expected, VersionNumberExpander.expand(input), STR."Input '\{input}' should expand to '\{expected}'");
  }

  @Test
  public void mixedText() {
    String input = "1alpha";
    String expected = "000000001alpha";
    assertEquals(expected, VersionNumberExpander.expand(input), STR."Input '\{input}' should expand to '\{expected}'");
    
    input = "beta-2";
    expected = "beta-000000002";
    assertEquals(expected, VersionNumberExpander.expand(input), STR."Input '\{input}' should expand to '\{expected}'");
    
    input = "1.0a4";
    expected = "000000001.000000000a000000004";
    assertEquals(expected, VersionNumberExpander.expand(input), STR."Input '\{input}' should expand to '\{expected}'");
    
    input = "beta-1.23-alpha4-snapshot";
    expected = "beta-000000001.000000023-alpha000000004-snapshot";
    assertEquals(expected, VersionNumberExpander.expand(input), STR."Input '\{input}' should expand to '\{expected}'");
  }

  @Test
  public void longNumber() {
    String input = "v1-rev20181217-1.27.0123456789123456789123456789";
    String expected = "v000000001-rev020181217-000000001.000000027.0123456789123456789123456789";
    assertEquals(expected, VersionNumberExpander.expand(input), STR."Input '\{input}' should expand to '\{expected}'");
  }
}
