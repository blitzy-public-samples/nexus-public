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
package org.sonatype.nexus.repository.rest.api.model;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.sonatype.nexus.validation.constraint.NamePatternConstants;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.NotEmpty;

/**
 * Abstract base class for repository API requests that provides common functionality and pattern matching support
 * for attribute objects, including compatibility with Record-based attribute classes.
 * 
 * @since 3.20
 */
public abstract class AbstractRepositoryApiRequest
    implements RepositoryApiRequest
{
  @ApiModelProperty(value = "A unique identifier for this repository", example = "internal", required = true)
  @Pattern(regexp = NamePatternConstants.REGEX, message = NamePatternConstants.MESSAGE)
  @NotEmpty
  protected String name;

  @ApiModelProperty(value = "Component format used by this repository", hidden = true)
  @NotEmpty
  protected String format;

  @ApiModelProperty(value = "Controls if deployments of and updates to artifacts are allowed",
      allowableValues = "hosted,proxy,group", hidden = true)
  @NotEmpty
  protected String type;

  @ApiModelProperty(value = "Whether this repository accepts incoming requests", example = "true", required = true)
  @NotNull
  protected Boolean online;

  @JsonCreator
  public AbstractRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("type") final String type,
      @JsonProperty("online") final Boolean online)
  {
    this.name = name;
    this.format = format;
    this.type = type;
    this.online = online;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getFormat() {
    return format;
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public Boolean getOnline() {
    return online;
  }
  
  /**
   * Process an attribute object using pattern matching, supporting both Record and non-Record types.
   * This method leverages Java 21's pattern matching to handle different attribute object types safely.
   *
   * @param attribute the attribute object to process
   * @param defaultValue the default value to return if the attribute doesn't match any pattern
   * @param <T> the type of the result
   * @return the processed value or the default value
   */
  protected <T> T processAttribute(Object attribute, T defaultValue) {
    if (attribute == null) {
      return defaultValue;
    }
    
    return switch (attribute) {
      case String s -> (T) s;
      case Integer i -> (T) i;
      case Boolean b -> (T) b;
      case Double d -> (T) d;
      case Long l -> (T) l;
      case Record r -> (T) r;
      default -> defaultValue;
    };
  }
  
  /**
   * Extract a value from a Record-based attribute using pattern matching.
   * This method leverages Java 21's record patterns for type-safe extraction of values.
   *
   * @param attribute the attribute object, potentially a Record
   * @param accessor function to access the desired field if the attribute is of the expected record type
   * @param <T> the expected record type
   * @param <R> the return type
   * @return an Optional containing the extracted value, or empty if pattern matching fails
   */
  protected <T extends Record, R> Optional<R> extractFromRecord(Object attribute, Function<T, R> accessor) {
    if (attribute instanceof T record) {
      return Optional.ofNullable(accessor.apply(record));
    }
    return Optional.empty();
  }
  
  /**
   * Process a map of attributes using pattern matching to extract and transform values.
   * This method demonstrates how to use pattern matching with different attribute types.
   *
   * @param attributes the map of attribute names to attribute values
   * @param key the key to look up in the attributes map
   * @param defaultValue the default value to return if the key is not found or the value doesn't match any pattern
   * @param <T> the type of the result
   * @return the processed value or the default value
   */
  protected <T> T processAttributeFromMap(Map<String, Object> attributes, String key, T defaultValue) {
    if (attributes == null || !attributes.containsKey(key)) {
      return defaultValue;
    }
    
    Object value = attributes.get(key);
    return processAttribute(value, defaultValue);
  }
}