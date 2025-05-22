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
import java.util.Objects;

import org.sonatype.goodies.common.Time;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * {@link Time} in seconds deserializer.
 * Updated for compatibility with Jackson 2.16.1 and Java 21.
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
    if (parser == null) {
      return null;
    }
    
    // Handle case where token is explicitly null or if we're at the end of input
    JsonToken token = parser.getCurrentToken();
    if (token == JsonToken.VALUE_NULL || token == null) {
      return null;
    }
    
    try {
      Long seconds = parser.readValueAs(Long.class);
      if (seconds == null) {
        return null;
      }
      
      return Time.seconds(seconds);
    } catch (Exception e) {
      // Improved error handling for Java 21's enhanced type checking
      context.reportInputMismatch(Time.class, 
          "Cannot deserialize value of type 'Time' from token " + token + ": expected numeric value");
      return null;
    }
  }
}