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
package org.sonatype.nexus.repository.json;

import java.io.IOException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.fasterxml.jackson.core.JsonToken.END_ARRAY;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Category(Java21TestGroup.class)
@ExtendWith(MockitoExtension.class)
public class UntypedObjectDeserializerSerializerTest
    extends TestSupport
{
  private final static String FIELD_NAME = "author";
  
  private final static String FIELD_VALUE = "shakespeare";

  @Mock
  private JsonGenerator generator;

  @Mock
  private JsonParser parser;

  @Mock
  private DeserializationContext context;

  private UntypedObjectDeserializerSerializer underTest;

  @BeforeEach
  public void setUp() throws IOException {
    underTest = new UntypedObjectDeserializerSerializer(generator);

    when(parser.getCurrentName()).thenReturn(FIELD_NAME);
    when(context.handleUnexpectedToken(eq(Object.class), eq(parser))).thenReturn(FIELD_VALUE);
  }

  @Test
  public void mappingObjectsSerializesDuringDeserializationAndReturnsNull() throws IOException {
    assertThat(underTest.mapObject(parser, context), nullValue());

    verify(generator).writeStartObject();
    verify(generator).writeEndObject();
  }

  @Test
  public void mappingArraySerializesDuringDeserializationAndReturnsNull() throws IOException {
    when(parser.nextToken()).thenReturn(END_ARRAY);

    assertThat(underTest.mapArray(parser, context), nullValue());

    verify(generator).writeStartArray();
    verify(generator).writeEndArray();
  }

  @Test
  public void serializesDuringDeserialization() throws IOException {
    Object deserializedValue = underTest.deserialize(parser, context);

    assertThat(deserializedValue, equalTo(FIELD_VALUE));
    verify(generator).writeFieldName(eq(FIELD_NAME));
    verify(generator).writeObject(eq(FIELD_VALUE));
  }

  @Test
  public void serializesDuringDefaultDeserialize() throws IOException {
    Object deserializedValue = underTest.defaultDeserialize(FIELD_NAME, parser, context);

    assertThat(deserializedValue, equalTo(FIELD_VALUE));
    verify(generator).writeFieldName(eq(FIELD_NAME));
    verify(generator).writeObject(eq(FIELD_VALUE));
  }

  @Test
  public void usesDefaultDeserialize() throws IOException {
    Object deserializedValue = underTest.defaultValueDeserialize(parser, context);

    assertThat(deserializedValue, equalTo(FIELD_VALUE));
    verify(generator, never()).writeFieldName(any(String.class));
    verify(generator, never()).writeObject(any(String.class));
  }
}