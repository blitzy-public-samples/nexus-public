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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.emptyMap;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.common.decorator.DecoratorUtils.getDecoratedEntity;

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

    assertThat(result, notNullValue());
    assertThat(result.getId(), equalTo("theid"));
    FooComponentXO fooComponentXO = getDecoratedEntity(result, FooComponentXO.class);
    assertThat(fooComponentXO, notNullValue());
    assertThat(fooComponentXO.getFoo(), equalTo("fiz"));
    BarComponentXO barComponentXO = getDecoratedEntity(result, BarComponentXO.class);
    assertThat(barComponentXO, notNullValue());
    assertThat(barComponentXO.getBar(), equalTo("biz"));
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
  
  /**
   * Record used to demonstrate record pattern matching with ComponentXO objects.
   */
  private record ComponentWrapper(ComponentXO component, String metadata) {}
  
  /**
   * Test to demonstrate record pattern usage with ComponentXO objects.
   * This test shows how Java 21's record patterns can be used to extract and work with
   * ComponentXO objects that are wrapped in records.
   */
  @Test
  public void testRecordPatternWithComponentXO() throws IOException {
    // Create a ComponentXO with nested decorators
    ComponentXO componentXO = new FooComponentXO(new BarComponentXO(new DefaultComponentXO()));
    when(componentXOFactory.createComponentXO()).thenReturn(componentXO);
    
    // Set up the JSON and deserialize
    String json = "{\"id\": \"record-pattern-test\", \"foo\": \"foo-value\", \"bar\": \"bar-value\"}";
    JsonParser jsonParser = jsonFactory.createParser(json);
    jsonParser.setCodec(objectMapper);
    ComponentXO result = underTest.deserialize(jsonParser, deserializationContext);
    
    // Wrap the result in our test record
    ComponentWrapper wrapper = new ComponentWrapper(result, "test-metadata");
    
    // Use record pattern to extract the component and metadata in one step
    if (wrapper instanceof ComponentWrapper(ComponentXO comp, String meta)) {
      // Verify the extracted component properties
      assertThat(comp.getId(), equalTo("record-pattern-test"));
      
      // Verify the metadata from the record
      assertThat(meta, equalTo("test-metadata"));
      
      // We can still use the decorated entity approach with the extracted component
      FooComponentXO extractedFoo = getDecoratedEntity(comp, FooComponentXO.class);
      assertThat(extractedFoo, notNullValue());
      assertThat(extractedFoo.getFoo(), equalTo("foo-value"));
      
      BarComponentXO extractedBar = getDecoratedEntity(comp, BarComponentXO.class);
      assertThat(extractedBar, notNullValue());
      assertThat(extractedBar.getBar(), equalTo("bar-value"));
    }
    else {
      // This should never happen if record patterns are working correctly
      throw new AssertionError("Record pattern matching failed");
    }
  }
}