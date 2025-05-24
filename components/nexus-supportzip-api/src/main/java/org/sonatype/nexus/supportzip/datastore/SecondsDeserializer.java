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
package org.sonatype.nexus.supportzip.datastore;

import java.io.IOException;

import org.sonatype.goodies.common.Time;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * {@link Time} in seconds deserializer.
 *
 * @since 3.29
 */
public class SecondsDeserializer
    extends StdDeserializer<Time>
{
  private static final long serialVersionUID = -5331061510739363902L;

  public SecondsDeserializer() {
    super(Time.class);
  }

  @Override
  public Time deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
    // Ensure the parser is not null
    if (parser == null) {
      return null;
    }

    try {
      // Handle null values explicitly
      if (parser.currentToken() == null || parser.currentToken().isStructEnd() || parser.currentToken().isScalarValue() && parser.getValueAsString() == null) {
        return null;
      }

      // Try to read the value as Long first
      Long seconds = parser.readValueAs(Long.class);
      if (seconds != null) {
        return Time.seconds(seconds);
      }
      return null;
    } catch (Exception e) {
      // Provide more detailed error message with enhanced type information
      throw new IOException("Failed to deserialize Time value: " + 
          (parser.getCurrentToken() != null ? "Unexpected token " + parser.getCurrentToken() : "No current token") +
          ". Expected a Long value.", e);
    }
  }
}