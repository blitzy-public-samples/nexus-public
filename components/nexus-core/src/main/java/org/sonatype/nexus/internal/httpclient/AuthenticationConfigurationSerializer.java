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
package org.sonatype.nexus.internal.httpclient;

import java.io.IOException;

import org.sonatype.nexus.httpclient.config.AuthenticationConfiguration;
import org.sonatype.nexus.httpclient.config.BearerTokenAuthenticationConfiguration;
import org.sonatype.nexus.httpclient.config.NtlmAuthenticationConfiguration;
import org.sonatype.nexus.httpclient.config.UsernameAuthenticationConfiguration;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * {@link AuthenticationConfiguration} serializer.
 * <p>
 * Encrypts sensitive data.
 *
 * @since 3.0
 */
public class AuthenticationConfigurationSerializer
    extends StdSerializer<AuthenticationConfiguration>
{
  public AuthenticationConfigurationSerializer() {
    super(AuthenticationConfiguration.class);
  }

  @Override
  public void serialize(final AuthenticationConfiguration value,
                        final JsonGenerator jgen,
                        final SerializerProvider provider)
      throws IOException
  {
    serialize(value, jgen);
    jgen.writeEndObject();
  }

  @Override
  public void serializeWithType(final AuthenticationConfiguration value,
                                final JsonGenerator jgen,
                                final SerializerProvider provider,
                                final TypeSerializer typeSer)
      throws IOException
  {
    serialize(value, jgen);
    
    // Use pattern matching with switch expression to determine the type
    String typeValue = switch (value) {
      case UsernameAuthenticationConfiguration ignored -> UsernameAuthenticationConfiguration.TYPE;
      case NtlmAuthenticationConfiguration ignored -> NtlmAuthenticationConfiguration.TYPE;
      case BearerTokenAuthenticationConfiguration ignored -> BearerTokenAuthenticationConfiguration.TYPE;
      case default -> throw new JsonGenerationException("Unsupported type: " + value.getClass().getName(), jgen);
    };
    
    jgen.writeStringField(typeSer.getPropertyName(), typeValue);
    jgen.writeEndObject();
  }

  private void serialize(final AuthenticationConfiguration value, final JsonGenerator jgen) throws IOException {
    jgen.writeStartObject();
    jgen.writeStringField("type", value.getType());
    jgen.writeBooleanField("preemptive", value.isPreemptive());
    
    // Use pattern matching with switch expression to handle different authentication types
    switch (value) {
      case UsernameAuthenticationConfiguration upc -> {
        jgen.writeStringField("username", upc.getUsername());
        if (upc.getPassword() != null) {
          jgen.writeStringField("password", upc.getPassword().getId());
        }
      }
      case NtlmAuthenticationConfiguration ntc -> {
        jgen.writeStringField("username", ntc.getUsername());
        if (ntc.getPassword() != null) {
          jgen.writeStringField("password", ntc.getPassword().getId());
        }
        jgen.writeStringField("domain", ntc.getDomain());
        jgen.writeStringField("host", ntc.getHost());
      }
      case BearerTokenAuthenticationConfiguration btac -> {
        jgen.writeStringField(BearerTokenAuthenticationConfiguration.TYPE, btac.getBearerToken());
      }
      case default -> throw new JsonGenerationException("Unsupported type: " + value.getClass().getName(), jgen);
    }
  }
}
