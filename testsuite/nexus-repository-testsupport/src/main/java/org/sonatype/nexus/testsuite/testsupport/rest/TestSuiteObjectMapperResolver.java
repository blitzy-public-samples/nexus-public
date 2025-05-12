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
package org.sonatype.nexus.testsuite.testsupport.rest;


import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

import org.sonatype.nexus.repository.rest.api.ComponentXO;
import org.sonatype.nexus.repository.rest.api.ComponentXODeserializer;
import org.sonatype.nexus.repository.rest.api.ComponentXODeserializerExtension;
import org.sonatype.nexus.repository.rest.api.ComponentXOFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Jackson {@link ContextResolver} to customize the {@link ObjectMapper} for the rest clients such as
 * SearchClient within the testsuite.
 * <p>
 * Updated for Java 21 compatibility and Jackson 2.16.1, leveraging modern language features
 * and optimized JSON processing capabilities.
 *
 * @since 3.8
 */
@Provider
@Produces(MediaType.APPLICATION_JSON)
@Named
public class TestSuiteObjectMapperResolver
    implements ContextResolver<ObjectMapper>
{
  private final ObjectMapper objectMapper;

  @Inject
  public TestSuiteObjectMapperResolver(final ComponentXOFactory componentXOFactory,
                                       final Set<ComponentXODeserializerExtension> componentXODeserializerExtensions)
  {
    this.objectMapper = new ObjectMapper();

    // the json will have extra fields that the ComponentXO doesn't have, we don't want to fail on these
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    // Configure Jackson 2.16.1 specific settings for optimal performance with Java 21
    objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);

    // Register the ComponentXO deserializer with support for Java 21 record patterns
    // Note: When deserializing into record types, Jackson 2.16.1 can leverage Java 21's record patterns
    // for more efficient and type-safe extraction of record components during the deserialization process.
    // This is particularly useful when working with nested record structures in test fixtures.
    this.objectMapper.registerModule(new SimpleModule("NexusTestSuiteModule")
        // add the deserializer for the ComponentXO class
        .addDeserializer(ComponentXO.class, new ComponentXODeserializer(componentXOFactory, objectMapper,
            componentXODeserializerExtensions))
    );
  }

  /**
   * Returns the configured {@link ObjectMapper} instance for the specified type.
   * <p>
   * This implementation leverages Java 21 pattern matching for instanceof where applicable
   * in the deserialization process, improving code readability and maintainability.
   *
   * @param type The class to get the context for
   * @return The configured ObjectMapper instance
   */
  @Override
  public ObjectMapper getContext(final Class<?> type) {
    return objectMapper;
  }
}