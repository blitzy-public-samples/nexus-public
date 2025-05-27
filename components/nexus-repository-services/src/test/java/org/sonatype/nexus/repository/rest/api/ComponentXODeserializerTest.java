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
import org.sonatype.nexus.java21.Java21TestGroup;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.common.decorator.DecoratorUtils.getDecoratedEntity;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
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

    assertNotNull(result);
    assertEquals("theid", result.getId());
    FooComponentXO fooComponentXO = getDecoratedEntity(result, FooComponentXO.class);
    assertNotNull(fooComponentXO);
    assertEquals("fiz", fooComponentXO.getFoo());
    BarComponentXO barComponentXO = getDecoratedEntity(result, BarComponentXO.class);
    assertNotNull(barComponentXO);
    assertEquals("biz", barComponentXO.getBar());
  }

  @Test
  public void deserializeWithRecordPattern() throws IOException {
    ComponentXO componentXO = new FooComponentXO(new BarComponentXO(new DefaultComponentXO()));
    when(componentXOFactory.createComponentXO()).thenReturn(componentXO);

    String json = "{\"id\": \"record-pattern-id\", \"foo\": \"record-foo\", \"bar\": \"record-bar\"}";
    JsonParser jsonParser = jsonFactory.createParser(json);
    jsonParser.setCodec(objectMapper);
    ComponentXO result = underTest.deserialize(jsonParser, deserializationContext);

    assertNotNull(result);
    
    // Using record pattern matching to extract and validate component values
    if (result instanceof FooComponentXO(BarComponentXO(var baseComponent), var fooValue)) {
      assertEquals("record-pattern-id", baseComponent.getId());
      assertEquals("record-foo", fooValue);
      
      // Further pattern matching on the BarComponentXO
      if (baseComponent instanceof BarComponentXO(var coreComponent, var barValue)) {
        assertEquals("record-pattern-id", coreComponent.getId());
        assertEquals("record-bar", barValue);
      }
    } else {
      // This should not happen if the pattern matching works correctly
      throw new AssertionError("Record pattern matching failed");
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
      FooComponentXO fooComponentXO = getDecoratedEntity(componentXO, FooComponentXO.class);
      if (fooComponentXO == null) {
        return componentXO;
      }

      JsonNode data = jsonNode.get("foo");
      fooComponentXO.setFoo(data.asText());
      return componentXO;
    }
  }

  private class BarComponentXODeserializerExtension
      implements ComponentXODeserializerExtension
  {
    @Override
    public ComponentXO updateComponentXO(final ComponentXO componentXO, final JsonNode jsonNode) {
      BarComponentXO barComponentXO = getDecoratedEntity(componentXO, BarComponentXO.class);
      if (barComponentXO == null) {
        return componentXO;
      }

      JsonNode data = jsonNode.get("bar");
      barComponentXO.setBar(data.asText());
      return componentXO;
    }
  }
}