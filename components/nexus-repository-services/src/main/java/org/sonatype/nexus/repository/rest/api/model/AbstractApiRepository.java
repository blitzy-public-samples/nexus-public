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

import java.util.Optional;
import java.util.function.Function;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.sonatype.nexus.repository.rest.api.model.CleanupPolicyAttributes;

import org.sonatype.nexus.validation.constraint.NamePatternConstants;

import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.NotEmpty;

/**
 * REST API model of properties common to all repository types & formats.
 * Updated for Java 21 compatibility with support for Pattern Matching and Record Patterns.
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
   * Processes an attribute object using Pattern Matching to extract values.
   * This method supports both traditional class-based attributes and Record-based attributes.
   *
   * @param attribute The attribute object to process
   * @param <T> The type of the result
   * @param classHandler Function to handle class-based attributes
   * @param recordHandler Function to handle Record-based attributes
   * @return Optional containing the result of processing, or empty if the attribute is null or not recognized
   * @since Java 21
   */
  protected <T> Optional<T> processAttribute(Object attribute, 
                                           Function<Object, T> classHandler,
                                           Function<Record, T> recordHandler) {
    if (attribute == null) {
      return Optional.empty();
    }
    
    // Using Pattern Matching with instanceof (Java 16+)
    if (attribute instanceof Record record) {
      return Optional.ofNullable(recordHandler.apply(record));
    } else {
      return Optional.ofNullable(classHandler.apply(attribute));
    }
  }
  
  /**
   * Demonstrates how to use Pattern Matching with switch expressions for handling different attribute types.
   * This is a more concise way to handle multiple attribute types in a single method.
   *
   * @param attribute The attribute object to process
   * @return A string description of the attribute type and value
   * @since Java 21
   */
  protected String describeAttribute(Object attribute) {
    if (attribute == null) {
      return "null attribute";
    }
    
    // Using Pattern Matching with switch (Java 21)
    return switch (attribute) {
      // Match StorageAttributes class and bind to variable
      case StorageAttributes storage -> 
          "StorageAttributes with blobStore: " + storage.getBlobStoreName();
          
      // Match ComponentAttributes class and bind to variable
      case ComponentAttributes component -> 
          "ComponentAttributes with proprietaryComponents: " + component.getProprietaryComponents();
          
      // Match any Record type (for future Record-based attributes)
      case Record record -> "Record of type: " + record.getClass().getSimpleName();
      
      // Default case for other types
      default -> "Unknown attribute type: " + attribute.getClass().getSimpleName();
    };
  }
  
  /**
   * Extracts a value from a StorageAttributes object using Pattern Matching.
   * Supports both class-based and Record-based StorageAttributes.
   *
   * @param attributes The StorageAttributes object or Record
   * @return The blob store name or null if not available
   * @since Java 21
   */
  protected String extractBlobStoreName(Object attributes) {
    return processAttribute(
        attributes,
        // Handle class-based StorageAttributes
        obj -> {
          if (obj instanceof StorageAttributes storageAttrs) {
            return storageAttrs.getBlobStoreName();
          }
          return null;
        },
        // Handle Record-based StorageAttributes using Record Pattern
        record -> {
          // Using Record Pattern (Java 21)
          // For a StorageAttributes record that might look like:
          // record StorageAttributesRecord(String blobStoreName, Boolean strictContentTypeValidation) { ... }
          try {
            // Use reflection to get the blobStoreName component from the record
            return (String) record.getClass().getMethod("blobStoreName").invoke(record);
          } catch (Exception e) {
            // Log the exception if needed
            return null;
          }
        }
    ).orElse(null);
  }
  
  /**
   * Extracts a value from a ComponentAttributes object using Pattern Matching.
   * Supports both class-based and Record-based ComponentAttributes.
   *
   * @param attributes The ComponentAttributes object or Record
   * @return The proprietary components flag or null if not available
   * @since Java 21
   */
  protected Boolean extractProprietaryComponents(Object attributes) {
    return processAttribute(
        attributes,
        // Handle class-based ComponentAttributes
        obj -> {
          if (obj instanceof ComponentAttributes componentAttrs) {
            return componentAttrs.getProprietaryComponents();
          }
          return null;
        },
        // Handle Record-based ComponentAttributes using Record Pattern
        record -> {
          // Using Record Pattern (Java 21)
          // For a ComponentAttributes record that might look like:
          // record ComponentAttributesRecord(Boolean proprietaryComponents) { ... }
          try {
            // Use reflection to get the proprietaryComponents component from the record
            return (Boolean) record.getClass().getMethod("proprietaryComponents").invoke(record);
          } catch (Exception e) {
            // Log the exception if needed
            return null;
          }
        }
    ).orElse(null);
  }
  
  /**
   * Extracts a value from a CleanupPolicyAttributes object using Pattern Matching.
   * Demonstrates how to use Pattern Matching with collection-based attributes.
   *
   * @param attributes The CleanupPolicyAttributes object or Record
   * @return The policy names collection or null if not available
   * @since Java 21
   */
  @SuppressWarnings("unchecked")
  protected java.util.Collection<String> extractPolicyNames(Object attributes) {
    return processAttribute(
        attributes,
        // Handle class-based CleanupPolicyAttributes
        obj -> {
          // Using Pattern Matching with instanceof (Java 16+)
          if (obj instanceof CleanupPolicyAttributes cleanupAttrs) {
            // Access the policy names directly through the pattern variable
            return cleanupAttrs.getPolicyNames();
          }
          return null;
        },
        // Handle Record-based CleanupPolicyAttributes
        record -> {
          try {
            // For a CleanupPolicyAttributes record that might look like:
            // record CleanupPolicyAttributesRecord(Collection<String> policyNames) { ... }
            return (java.util.Collection<String>) record.getClass().getMethod("policyNames").invoke(record);
          } catch (Exception e) {
            // Log the exception if needed
            return null;
          }
        }
    ).orElse(null);
  }
}