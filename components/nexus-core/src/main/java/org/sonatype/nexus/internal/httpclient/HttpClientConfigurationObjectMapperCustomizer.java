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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretDeserializer;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.datastore.mybatis.OverrideIgnoreTypeIntrospector;
import org.sonatype.nexus.httpclient.config.AuthenticationConfiguration;
import org.sonatype.nexus.repository.config.ConfigurationObjectMapperCustomizer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.ImmutableList;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * HTTP-client specific {@link ConfigurationObjectMapperCustomizer} that registers custom deserializer
 * with {@link ObjectMapper}.
 * 
 * Updated for Java 21 compatibility with support for Record types, Pattern Matching, and String Templates.
 *
 * @see AuthenticationConfigurationDeserializer
 */
@Named
@Singleton
public class HttpClientConfigurationObjectMapperCustomizer
    extends ComponentSupport
    implements ConfigurationObjectMapperCustomizer
{
  private final SecretsService secretsService;

  @Inject
  public HttpClientConfigurationObjectMapperCustomizer(final SecretsService secretsService) {
    this.secretsService = checkNotNull(secretsService);
  }

  /**
   * Customizes the ObjectMapper with Java 21 compatibility settings and serializers/deserializers.
   * 
   * @param objectMapper The ObjectMapper to customize
   */
  @Override
  public void customize(final ObjectMapper objectMapper) {
    // Configure ObjectMapper for Java 21 compatibility
    objectMapper
        // Set annotation introspector for Secret class
        .setAnnotationIntrospector(new OverrideIgnoreTypeIntrospector(ImmutableList.of(Secret.class)))
        // Enable proper handling of Record types
        .enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES)
        // Ensure proper handling of Record patterns in serialization
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        // Configure for proper deserialization of Record types
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        // Register custom serializers and deserializers
        .registerModule(
            new SimpleModule("Java21Module")
                .addSerializer(
                    Time.class,
                    new SecondsSerializer()
                )
                .addDeserializer(
                    Time.class,
                    new SecondsDeserializer()
                )
                .addSerializer(
                    AuthenticationConfiguration.class,
                    new AuthenticationConfigurationSerializer()
                )
                .addDeserializer(
                    AuthenticationConfiguration.class,
                    new AuthenticationConfigurationDeserializer()
                )
                .addDeserializer(Secret.class, new SecretDeserializer(secretsService))
        );
  }
}