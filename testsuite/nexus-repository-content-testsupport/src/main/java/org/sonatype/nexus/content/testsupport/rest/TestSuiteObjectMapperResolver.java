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
package org.sonatype.nexus.content.testsupport.rest;

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
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Jackson {@link ContextResolver} to customize the {@link ObjectMapper} for the rest clients such as
 * SearchClient within the testsuite.
 *
 * <p>Updated for Java 21 compatibility with Jakarta EE APIs and Jackson 2.16.1.</p>
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
  public TestSuiteObjectMapperResolver(
      final ComponentXOFactory componentXOFactory,
      final Set<ComponentXODeserializerExtension> componentXODeserializerExtensions)
  {
    // Use JsonMapper.builder() for Java 21 compatibility
    this.objectMapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    // Register the deserializer for the ComponentXO class
    this.objectMapper.registerModule(new SimpleModule()
        .addDeserializer(ComponentXO.class, new ComponentXODeserializer(componentXOFactory, objectMapper,
            componentXODeserializerExtensions)));
  }

  @Override
  public ObjectMapper getContext(final Class<?> type) {
    return objectMapper;
  }
}