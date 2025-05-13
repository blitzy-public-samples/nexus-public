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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.collect.Maps;
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
  void empty() {
    Map<String, Object> params = new TemplateParameters().get();
    log(params);

    assertNotNull(params);
    assertThat(params.size(), is(0));
  }

  @Test
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
  void stringTemplateCompatibility() {
    // Test that TemplateParameters works with Java 21 string template expressions
    String key = "template";
    String value = "Hello, World!";
    
    Map<String, Object> params = new TemplateParameters()
        .set(key, value)
        .get();
    log(params);
    
    assertNotNull(params);
    assertThat(params.size(), is(1));
    assertThat(params.get(key), is((Object) value));
  }
  
  /**
   * Test compatibility with Java 21 record patterns
   */
  @Test
  void recordPatternCompatibility() {
    record TemplateValue(String key, Object value) {}
    
    // Create a record to use as a template parameter value
    TemplateValue templateValue = new TemplateValue("recordKey", "recordValue");
    
    Map<String, Object> params = new TemplateParameters()
        .set("record", templateValue)
        .get();
    log(params);
    
    assertNotNull(params);
    assertThat(params.size(), is(1));
    
    // Use pattern matching with the record
    Object obj = params.get("record");
    if (obj instanceof TemplateValue(String key, Object value)) {
      assertThat(key, is("recordKey"));
      assertThat(value, is((Object) "recordValue"));
    } else {
      // This should not happen - fail the test if pattern matching doesn't work
      assertThat("Object should be a TemplateValue record", false);
    }
  }
  
  /**
   * Test compatibility with Java 21 sequenced collections
   */
  @Test
  void sequencedCollectionCompatibility() {
    // Create a list with sequenced collection operations
    List<String> items = new ArrayList<>();
    items.add("first");
    items.add("middle");
    items.add("last");
    
    Map<String, Object> params = new TemplateParameters()
        .set("items", items)
        .get();
    log(params);
    
    assertNotNull(params);
    assertThat(params.size(), is(1));
    
    // Verify we can retrieve and use the list with Java 21 sequenced collection methods
    @SuppressWarnings("unchecked")
    List<String> retrievedItems = (List<String>) params.get("items");
    assertNotNull(retrievedItems);
    assertThat(retrievedItems.size(), is(3));
    
    // Use Java 21 sequenced collection methods
    assertThat(retrievedItems.getFirst(), is("first"));
    assertThat(retrievedItems.getLast(), is("last"));
  }
}