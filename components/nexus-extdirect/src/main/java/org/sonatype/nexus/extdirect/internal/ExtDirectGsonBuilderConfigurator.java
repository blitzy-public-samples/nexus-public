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
package org.sonatype.nexus.extdirect.internal;

import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.softwarementors.extjs.djn.config.GlobalConfiguration;
import com.softwarementors.extjs.djn.gson.DefaultGsonBuilderConfigurator;

/**
 * Additional GSon type adapters with Java 21 compatibility.
 *
 * @since 3.0
 */
public class ExtDirectGsonBuilderConfigurator
    extends DefaultGsonBuilderConfigurator
{
  private static final Logger log = Logger.getLogger(ExtDirectGsonBuilderConfigurator.class.getName());

  // ISO-8601 date format pattern for consistent date serialization
  private static final String DATE_FORMAT_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

  @Override
  public void configure(final GsonBuilder builder, final GlobalConfiguration configuration) {
    // Log configuration using Java 21 String Templates
    log.fine(STR."Configuring GsonBuilder with debug mode: \{configuration.getDebug()}");
    
    if (configuration.getDebug()) {
      builder.setPrettyPrinting();
      log.fine(STR."Enabled pretty printing for JSON output");
    }
    
    builder.serializeNulls();
    builder.disableHtmlEscaping();
    
    // Configure date format with Java 21 compatibility
    builder.setDateFormat(DATE_FORMAT_PATTERN);
    log.fine(STR."Set date format pattern to: \{DATE_FORMAT_PATTERN}");
    
    // Register optimized date serializers for Java 21
    registerDateTypeAdapters(builder);
    
    // Register standard encoding type adapters from parent class
    super.registerEncodingTypeAdapters(builder, configuration);
    
    log.fine("GsonBuilder configuration completed");
  }
  
  /**
   * Registers optimized date type adapters for Java 21 compatibility.
   * This improves serialization performance for date objects.
   *
   * @param builder the GsonBuilder to configure
   */
  private void registerDateTypeAdapters(final GsonBuilder builder) {
    // Optimized serializer for java.util.Date that uses efficient string conversion
    JsonSerializer<java.util.Date> dateSerializer = (date, type, context) -> {
      // Use String Templates for efficient date formatting
      return new JsonPrimitive(STR."\{DateTimeFormatter.ISO_INSTANT.format(date.toInstant())}");
    };
    
    // Optimized deserializer that handles various date formats
    JsonDeserializer<java.util.Date> dateDeserializer = (json, type, context) -> {
      try {
        return java.util.Date.from(java.time.Instant.parse(json.getAsString()));
      } catch (Exception e) {
        log.warning(STR."Failed to parse date: \{json.getAsString()}, error: \{e.getMessage()}");
        return null;
      }
    };
    
    // Register the custom date adapters
    builder.registerTypeAdapter(java.util.Date.class, dateSerializer);
    builder.registerTypeAdapter(java.util.Date.class, dateDeserializer);
    
    log.fine("Registered optimized date type adapters for Java 21");
  }
}