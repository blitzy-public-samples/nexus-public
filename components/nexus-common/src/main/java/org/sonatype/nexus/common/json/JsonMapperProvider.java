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
package org.sonatype.nexus.common.json;

import jakarta.annotation.Priority;
import jakarta.inject.Named;
import jakarta.inject.Provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

/**
 * Provider for the default configuration of {@link JsonMapper}. Marked with a sub-zero priority to prioritize
 * existing {@link ObjectMapper} providers.<br/>
 *
 * Reminder, if customization is required, use {@link JsonMapper#copy()} to create a local copy before customization.
 */
@Named("default")
@Priority(-1)
public class JsonMapperProvider
    implements Provider<JsonMapper>
{
  @Override
  public JsonMapper get() {
    return JsonMapper.builder()
        // Preserve existing functionality
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        
        // Enable Java 21 record support
        .configure(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS, true)
        .configure(MapperFeature.INFER_RECORD_CONSTRUCTOR, true)
        .configure(MapperFeature.INFER_CREATOR_FROM_CONSTRUCTOR_PROPERTIES, true)
        
        // Optimize serialization/deserialization for Java 21
        .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true)
        
        // Maintain existing modules with Java 21 compatibility
        .addModule(new JavaTimeModule())
        .addModule(new Jdk8Module())
        .addModule(new ParameterNamesModule())
        .build();
  }
}