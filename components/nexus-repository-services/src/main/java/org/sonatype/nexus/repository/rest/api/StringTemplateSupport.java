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
package org.sonatype.nexus.repository.rest.api;

import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;

import javax.ws.rs.core.Response.Status;

import org.sonatype.nexus.common.log.LoggerLevel;
import org.sonatype.nexus.rest.ValidationErrorXO;

/**
 * Utility class providing support for Java 21 String Templates in the REST API layer.
 * String Templates improve readability, performance, and security of string formatting operations.
 *
 * @since 3.60
 */
public final class StringTemplateSupport
{
  /**
   * Private constructor to prevent instantiation of utility class.
   */
  private StringTemplateSupport() {
    // empty
  }

  /**
   * Creates a formatted error message using String Templates.
   *
   * @param message The error message
   * @return The formatted error message
   */
  public static String formatErrorMessage(final String message) {
    return STR."\"\{message}\"";
  }

  /**
   * Creates a formatted error message with a specific error ID using String Templates.
   *
   * @param id The error identifier
   * @param message The error message
   * @return The formatted error message
   */
  public static String formatErrorMessage(final String id, final String message) {
    return STR."\"[\{id}] \{message}\"";
  }

  /**
   * Creates a formatted validation error message using String Templates.
   *
   * @param field The field that failed validation
   * @param message The validation error message
   * @return The formatted validation error message
   */
  public static String formatValidationError(final String field, final String message) {
    return STR."\"Field '\{field}' validation failed: \{message}\"";
  }

  /**
   * Creates a formatted HTTP error response message using String Templates.
   *
   * @param status The HTTP status code
   * @param message The error message
   * @return The formatted HTTP error message
   */
  public static String formatHttpError(final int status, final String message) {
    return STR."\"HTTP \{status}: \{message}\"";
  }
  
  /**
   * Creates a formatted HTTP error response message using String Templates.
   *
   * @param status The HTTP status
   * @param message The error message
   * @return The formatted HTTP error message
   */
  public static String formatHttpError(final Status status, final String message) {
    return STR."\"HTTP \{status.getStatusCode()}: \{message}\"";
  }

  /**
   * Creates a formatted repository not found error message using String Templates.
   *
   * @param repositoryName The name of the repository that was not found
   * @return The formatted repository not found error message
   */
  public static String formatRepositoryNotFound(final String repositoryName) {
    return STR."\"Repository '\{repositoryName}' not found\"";
  }

  /**
   * Creates a formatted log message using String Templates.
   *
   * @param level The log level
   * @param message The log message
   * @return The formatted log message
   */
  public static String formatLogMessage(final LoggerLevel level, final String message) {
    return STR."[\{level}] \{message}";
  }

  /**
   * Creates a formatted log message with context using String Templates.
   *
   * @param level The log level
   * @param context The context information
   * @param message The log message
   * @return The formatted log message with context
   */
  public static String formatLogMessage(final LoggerLevel level, final String context, final String message) {
    return STR."[\{level}] [\{context}] \{message}";
  }

  /**
   * Creates a formatted exception message with suppressed exceptions using String Templates.
   *
   * @param exception The exception to format
   * @return The formatted exception message including suppressed exceptions
   */
  public static String formatExceptionMessage(final Exception exception) {
    if (exception == null) {
      return "\"Unknown error\"";
    }
    
    StringJoiner stringJoiner = new StringJoiner("\n", "\"", "\"");
    stringJoiner.add(exception.getMessage());
    for (Throwable t : exception.getSuppressed()) {
      stringJoiner.add(t.getMessage());
    }
    return stringJoiner.toString();
  }

  /**
   * Creates a formatted message for multiple validation errors using String Templates.
   *
   * @param errors The collection of validation errors
   * @return The formatted validation errors message
   */
  public static String formatValidationErrors(final Collection<ValidationErrorXO> errors) {
    if (errors == null || errors.isEmpty()) {
      return "\"(No validation errors)\"";
    }
    
    StringJoiner joiner = new StringJoiner(", ", "\"", "\"");
    for (ValidationErrorXO error : errors) {
      joiner.add(error.getMessage());
    }
    return joiner.toString();
  }

  /**
   * Sanitizes sensitive data in a map for logging or error messages using String Templates.
   * Keys containing sensitive information (password, secret, token, etc.) will have their values masked.
   *
   * @param data The map containing potentially sensitive data
   * @return A sanitized string representation of the map
   */
  public static String sanitizeSensitiveData(final Map<String, Object> data) {
    if (data == null || data.isEmpty()) {
      return "{}";
    }
    
    StringJoiner joiner = new StringJoiner(", ", "{", "}");
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      
      // Mask sensitive values
      if (key != null && (key.toLowerCase().contains("password") || 
          key.toLowerCase().contains("secret") || 
          key.toLowerCase().contains("token") || 
          key.toLowerCase().contains("key"))) {
        joiner.add(STR."\{key}=*****");
      } else {
        joiner.add(STR."\{key}=\{value}");
      }
    }
    return joiner.toString();
  }

  /**
   * Creates a formatted message for API disabled in High Availability mode using String Templates.
   *
   * @param format The repository format that is disabled
   * @return The formatted API disabled message
   */
  public static String formatApiDisabled(final String format) {
    return STR."\"Format \{format} is disabled in High Availability\"";
  }

  /**
   * Creates a formatted message for repository creation failure using String Templates.
   *
   * @param repositoryName The name of the repository that failed to be created
   * @param reason The reason for the failure
   * @return The formatted repository creation failure message
   */
  public static String formatRepositoryCreationFailure(final String repositoryName, final String reason) {
    return STR."\"Failed to create repository '\{repositoryName}': \{reason}\"";
  }

  /**
   * Creates a formatted message for repository update failure using String Templates.
   *
   * @param repositoryName The name of the repository that failed to be updated
   * @param reason The reason for the failure
   * @return The formatted repository update failure message
   */
  public static String formatRepositoryUpdateFailure(final String repositoryName, final String reason) {
    return STR."\"Failed to update repository '\{repositoryName}': \{reason}\"";
  }

  /**
   * Creates a formatted message for support ZIP generation using String Templates.
   *
   * @param filename The name of the generated ZIP file
   * @param size The size of the ZIP file in bytes
   * @return The formatted support ZIP generation message
   */
  public static String formatSupportZipGeneration(final String filename, final long size) {
    return STR."\"Generated support ZIP '\{filename}' (\{size} bytes)\"";
  }
  
  /**
   * Creates a formatted debug log message with context data using String Templates.
   * This method is optimized for performance in debug logging scenarios where string
   * concatenation would otherwise be expensive.
   *
   * @param message The log message
   * @param context The context object (can be any object with a meaningful toString())
   * @return The formatted debug log message
   */
  public static String formatDebugLog(final String message, final Object context) {
    return STR."\{message}: \{context}";
  }
  
  /**
   * Creates a formatted debug log message with multiple context values using String Templates.
   * This method is optimized for performance in debug logging scenarios where string
   * concatenation would otherwise be expensive.
   *
   * @param message The log message
   * @param context1 The first context object
   * @param context2 The second context object
   * @return The formatted debug log message
   */
  public static String formatDebugLog(final String message, final Object context1, final Object context2) {
    return STR."\{message}: \{context1}, \{context2}";
  }
}