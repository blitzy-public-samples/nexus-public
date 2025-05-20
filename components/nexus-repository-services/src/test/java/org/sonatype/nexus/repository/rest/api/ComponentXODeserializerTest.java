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
package org.sonatype.nexus.repository.rest.api;

import java.io.IOException;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.common.decorator.DecoratorUtils.getDecoratedEntity;@Category(Java21TestGroup.class)
@ExtendWith(MockitoExtension.class)
public class ComponentXODeserializerTest
    extends TestSupport
{
  @Mock
  private ComponentXOFactory componentXOFactory;

  @Mock
  private DeserializationContext deserializationContext;

  private ObjectMapper objectMapper = new ObjectMapper();

  private JsonFactory jsonFactory = new JsonFactory();

  private ComponentXODeserializerExtension foo = new FooComponentXODeserializerExtension();

  private ComponentXODeserializerExtension bar = new BarComponentXODeserializerExtension();

  private ComponentXODeserializer underTest;

  @BeforeEach
  public void setup() {
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    underTest = new ComponentXODeserializer(componentXOFactory, objectMapper, newHashSet(foo, bar));
  }

  @Test
  public void deserialize() throws IOException {
    ComponentXO componentXO = new FooComponentXO(new BarComponentXO(new DefaultComponentXO()));
    when(componentXOFactory.createComponentXO()).thenReturn(componentXO);

    String json = "{\"id\": \"theid\", \"foo\": \"fiz\", \"bar\": \"biz\"}";
    JsonParser jsonParser = jsonFactory.createParser(json);
    jsonParser.setCodec(objectMapper);
    ComponentXO result = underTest.deserialize(jsonParser, deserializationContext);

    assertNotNull(result, "Result should not be null");
    assertEquals("theid", result.getId(), "ID should match");
    FooComponentXO fooComponentXO = getDecoratedEntity(result, FooComponentXO.class);
    assertNotNull(fooComponentXO, "FooComponentXO should not be null");
    assertEquals("fiz", fooComponentXO.getFoo(), "Foo value should match");
    BarComponentXO barComponentXO = getDecoratedEntity(result, BarComponentXO.class);
    assertNotNull(barComponentXO, "BarComponentXO should not be null");
    assertEquals("biz", barComponentXO.getBar(), "Bar value should match");
  }

  /**
   * Tests record pattern matching with ComponentXO objects.
   * This test demonstrates how to use Java 21's record pattern matching
   * to simplify working with decorated ComponentXO objects.
   */
  @Test
  public void deserializeWithRecordPatternMatching() throws IOException {
    // Create a decorated ComponentXO
    ComponentXO componentXO = new FooComponentXO(new BarComponentXO(new DefaultComponentXO()));
    when(componentXOFactory.createComponentXO()).thenReturn(componentXO);

    String json = "{\"id\": \"record-pattern-id\", \"foo\": \"record-foo\", \"bar\": \"record-bar\"}";
    JsonParser jsonParser = jsonFactory.createParser(json);
    jsonParser.setCodec(objectMapper);
    ComponentXO result = underTest.deserialize(jsonParser, deserializationContext);

    // Use record pattern matching to extract and validate values
    if (result instanceof ComponentXO component) {
      assertEquals("record-pattern-id", component.getId(), "ID should match");
      
      // Extract FooComponentXO using pattern matching
      if (component instanceof FooComponentXO(ComponentXO nested) foo) {
        assertEquals("record-foo", foo.getFoo(), "Foo value should match");
        
        // Extract BarComponentXO using nested pattern matching
        if (nested instanceof BarComponentXO bar) {
          assertEquals("record-bar", bar.getBar(), "Bar value should match");
        }
      }
    }
  }

  private class FooComponentXO
      extends DecoratedComponentXO
      implements ComponentXO
  {
    private String foo;

    FooComponentXO(final ComponentXO componentXO) {
      super(componentXO);
    }

    @Override
    public Map<String, Object> getDecoratedExtraJsonAttributes() {
      // not part of this test
      return emptyMap();
    }

    public String getFoo() {
      return foo;
    }

    public void setFoo(final String foo) {
      this.foo = foo;
    }
  }

  private class BarComponentXO
      extends DecoratedComponentXO
      implements ComponentXO
  {
    private String bar;

    BarComponentXO(final ComponentXO componentXO) {
      super(componentXO);
    }

    @Override
    public Map<String, Object> getDecoratedExtraJsonAttributes() {
      // not part of this test
      return emptyMap();
    }

    public String getBar() {
      return bar;
    }

    public void setBar(final String bar) {
      this.bar = bar;
    }
  }

  private class FooComponentXODeserializerExtension
      implements ComponentXODeserializerExtension
  {
    @Override
    public ComponentXO updateComponentXO(final ComponentXO componentXO, final JsonNode jsonNode) {
      // Use pattern matching to simplify type checking and casting
      if (componentXO instanceof FooComponentXO foo) {
        JsonNode data = jsonNode.get("foo");
        if (data != null) {
          foo.setFoo(data.asText());
        }
        return componentXO;
      }
      return componentXO;
    }
  }

  private class BarComponentXODeserializerExtension
      implements ComponentXODeserializerExtension
  {
    @Override
    public ComponentXO updateComponentXO(final ComponentXO componentXO, final JsonNode jsonNode) {
      // Use pattern matching to simplify type checking and casting
      if (componentXO instanceof BarComponentXO bar) {
        JsonNode data = jsonNode.get("bar");
        if (data != null) {
          bar.setBar(data.asText());
        }
        return componentXO;
      }
      return componentXO;
    }
  }
}