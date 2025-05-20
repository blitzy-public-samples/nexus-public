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

import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.NotEmpty;

/**
 * REST API model of properties common to all repository types & formats.
 * 
 * This class has been updated to be compatible with Java 21 and supports pattern matching
 * with Record-based attribute classes.
 *
 * @since 3.20
 */
public abstract class AbstractApiRepository
{
  @ApiModelProperty(value = "A unique identifier for this repository", example = "internal")
  @Pattern(regexp = NamePatternConstants.REGEX, message = NamePatternConstants.MESSAGE)
  @NotEmpty
  protected String name;

  @ApiModelProperty(value = "Component format held in this repository", example = "npm")
  @NotEmpty
  protected String format;

  @ApiModelProperty(value = "Controls if deployments of and updates to artifacts are allowed",
      allowableValues = "hosted,proxy,group", example = "hosted")
  @NotEmpty
  protected String type;

  @ApiModelProperty(value = "URL to the repository")
  protected String url;

  @ApiModelProperty(value = "Whether this repository accepts incoming requests", example = "true")
  @NotNull
  protected Boolean online;

  public AbstractApiRepository(
      final String name,
      final String format,
      final String type,
      final String url,
      final Boolean online)
  {
    this.name = name;
    this.format = format;
    this.type = type;
    this.url = url;
    this.online = online;
  }

  public String getName() {
    return name;
  }

  public String getFormat() {
    return format;
  }

  public String getType() {
    return type;
  }

  public Boolean getOnline() {
    return online;
  }

  public String getUrl() {
    return url;
  }
  
  /**
   * Record definition for repository attributes to support pattern matching.
   * This record encapsulates common repository attributes for use with Java 21 pattern matching.
   */
  public record RepositoryAttributes(String name, String format, String type, String url, Boolean online) {
    /**
     * Creates a RepositoryAttributes record from an AbstractApiRepository instance.
     */
    public static RepositoryAttributes from(AbstractApiRepository repository) {
      return new RepositoryAttributes(
          repository.getName(),
          repository.getFormat(),
          repository.getType(),
          repository.getUrl(),
          repository.getOnline());
    }
  }
  
  /**
   * Record definition for storage attributes to support pattern matching.
   */
  public record StorageAttributesRecord(String blobStoreName, Boolean strictContentTypeValidation) {}
  
  /**
   * Record definition for hosted storage attributes to support pattern matching.
   */
  public record HostedStorageAttributesRecord(String blobStoreName, Boolean strictContentTypeValidation, String writePolicy) {}
  
  /**
   * Record definition for cleanup policy attributes to support pattern matching.
   */
  public record CleanupPolicyAttributesRecord(String[] policyNames) {}
  
  /**
   * Processes repository attributes using pattern matching.
   * This method demonstrates how to use Java 21 pattern matching with repository attributes.
   *
   * @param attributes The attributes object to process
   * @return A string representation of the processed attributes
   */
  public String processAttributes(Object attributes) {
    return switch (attributes) {
      case RepositoryAttributes(String name, String format, String type, var url, Boolean online) ->
          String.format("Repository: %s (%s/%s) - %s", name, format, type, online ? "online" : "offline");
          
      case StorageAttributesRecord(String blobStoreName, Boolean strictValidation) ->
          String.format("Storage: %s (strict validation: %s)", blobStoreName, strictValidation);
          
      case HostedStorageAttributesRecord(String blobStoreName, Boolean strictValidation, String writePolicy) ->
          String.format("Hosted Storage: %s (strict validation: %s, write policy: %s)", 
              blobStoreName, strictValidation, writePolicy);
              
      case CleanupPolicyAttributesRecord(String[] policyNames) ->
          String.format("Cleanup Policies: %s", String.join(", ", policyNames));
          
      default -> "Unknown attribute type";
    };
  }
  
  /**
   * Extracts specific attribute values using pattern matching.
   * This method demonstrates how to extract values from nested record patterns.
   *
   * @param repositoryConfig The repository configuration object
   * @return An optional string containing the extracted value
   */
  public <T> Optional<T> extractAttributeValue(Object repositoryConfig, Function<Object, T> extractor) {
    if (repositoryConfig instanceof Map<?, ?> map && map.get("attributes") instanceof Map<?, ?> attributes) {
      return Optional.ofNullable(extractor.apply(attributes));
    }
    return Optional.empty();
  }
  
  /**
   * Processes a repository configuration using nested pattern matching.
   * This method demonstrates how to use nested pattern matching with record-based attributes.
   *
   * @param config The configuration object to process
   * @return A string representation of the processed configuration
   */
  public String processRepositoryConfig(Object config) {
    return switch (config) {
      // Pattern matching with nested records
      case Map<?, ?> map when map.get("repository") instanceof RepositoryAttributes(var name, var format, var type, var url, var online) ->
          String.format("Repository config for %s (%s/%s)", name, format, type);
          
      // Pattern matching with type test patterns
      case Map<?, ?> map when map.get("storage") instanceof StorageAttributesRecord storage ->
          String.format("Storage config for %s", storage.blobStoreName());
          
      // Pattern matching with guards
      case Map<?, ?> map when map.get("cleanup") instanceof CleanupPolicyAttributesRecord(var policies) && policies.length > 0 ->
          String.format("Cleanup config with %d policies", policies.length);
          
      default -> "Unknown configuration";
    };
  }
  
  /**
   * Converts this AbstractApiRepository to a RepositoryAttributes record.
   * This method facilitates using pattern matching with this repository instance.
   *
   * @return A RepositoryAttributes record representing this repository
   */
  public RepositoryAttributes toAttributes() {
    return RepositoryAttributes.from(this);
  }
}
