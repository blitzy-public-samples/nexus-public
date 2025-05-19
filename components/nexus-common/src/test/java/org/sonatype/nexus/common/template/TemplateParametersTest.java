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
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import com.google.common.collect.Maps;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.DisplayName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link TemplateParameters}
 * 
 * These tests validate the functionality of the TemplateParameters class for creating
 * and manipulating parameter maps used in template processing. The tests ensure compatibility
 * with Java 21 features while maintaining backward compatibility with existing code.
 */
@Category(Java21TestGroup.class)
public class TemplateParametersTest
    extends TestSupport
{
  /**
   * Verifies that a new TemplateParameters instance creates an empty parameter map.
   */
  @Test
  @DisplayName("Empty parameters map is created correctly")
  public void empty() { // Kept for backward compatibility
    Map<String, Object> params = new TemplateParameters().get();
    log(params);

    assertNotNull(params);
    assertThat(params, notNullValue());
    assertThat(params.size(), is(0));
    assertEquals(0, params.size(), "Parameter map should be empty");
  }

  /**
   * Verifies that TemplateParameters correctly handles different value types.
   */
  @Test
  @DisplayName("Mixed parameter types are stored correctly")
  public void mixedTypes() { // Kept for backward compatibility
    Map<String, Object> params = new TemplateParameters()
        .set("a", "1")
        .set("b", 2)
        .get();
    log(params);

    assertNotNull(params);
    assertThat(params, notNullValue());
    assertThat(params.size(), is(2));
    assertEquals(2, params.size(), "Parameter map should contain 2 entries");
    
    assertThat(params.get("a"), is(equalTo((Object) "1")));
    assertEquals("1", params.get("a"), "String parameter should be stored correctly");
    
    assertThat(params.get("b"), is(equalTo((Object) 2)));
    assertEquals(2, params.get("b"), "Integer parameter should be stored correctly");
  }

  /**
   * Verifies that TemplateParameters correctly imports all entries from another map.
   */
  @Test
  @DisplayName("Bulk parameter import works correctly")
  public void setAll() { // Kept for backward compatibility
    Map<String, Object> other = Maps.newHashMap();
    other.put("a", "1");
    other.put("b", 2);

    Map<String, Object> params = new TemplateParameters()
        .setAll(other)
        .get();
    log(params);

    assertNotNull(params);
    assertThat(params, notNullValue());
    assertThat(params.size(), is(2));
    assertEquals(2, params.size(), "Parameter map should contain 2 entries");
    
    assertThat(params.get("a"), is(equalTo((Object) "1")));
    assertEquals("1", params.get("a"), "String parameter should be imported correctly");
    
    assertThat(params.get("b"), is(equalTo((Object) 2)));
    assertEquals(2, params.get("b"), "Integer parameter should be imported correctly");
  }
}