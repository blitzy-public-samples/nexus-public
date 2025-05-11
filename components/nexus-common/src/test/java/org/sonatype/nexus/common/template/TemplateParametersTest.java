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

import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.collect.Maps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link TemplateParameters}
 */
public class TemplateParametersTest
    extends TestSupport
{
  @Test
  @DisplayName("Empty parameters should return empty map")
  void empty() {
    Map<String, Object> params = new TemplateParameters().get();
    log(params);

    assertNotNull(params);
    assertThat(params.size(), is(0));
  }

  @Test
  @DisplayName("Parameters with mixed types should be stored correctly")
  void mixedTypes() {
    Map<String, Object> params = new TemplateParameters()
        .set("a", "1")
        .set("b", 2)
        .get();
    log(params);

    assertNotNull(params);
    assertThat(params.size(), is(2));
    assertThat(params.get("a"), is((Object) "1"));
    assertThat(params.get("b"), is((Object) 2));
  }

  @Test
  @DisplayName("Setting all parameters from another map should work correctly")
  void setAll() {
    Map<String, Object> other = Maps.newHashMap();
    other.put("a", "1");
    other.put("b", 2);

    Map<String, Object> params = new TemplateParameters()
        .setAll(other)
        .get();
    log(params);

    assertNotNull(params);
    assertThat(params.size(), is(2));
    assertThat(params.get("a"), is((Object) "1"));
    assertThat(params.get("b"), is((Object) 2));
  }
  
  @Test
  @DisplayName("String templates should work with TemplateParameters")
  void stringTemplates() {
    Map<String, Object> params = new TemplateParameters()
        .set("name", "World")
        .set("count", 42)
        .get();
    log(params);
    
    // Using Java 21 string templates to format a message with parameters
    String message = STR."Hello \{params.get("name")}! Count: \{params.get("count")}";
    
    assertNotNull(message);
    assertThat(message, is("Hello World! Count: 42"));
  }
}
