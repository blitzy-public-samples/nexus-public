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

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for working with Java 21's String Templates in the REST API layer.
 * Provides standardized formatting for error messages, log messages, and validation messages
 * across the repository services.
 * 
 * This class leverages Java 21's String Templates feature to improve readability, performance,
 * and security of string formatting operations throughout the codebase.
 *
 * @since 3.60
 */
public final class StringTemplateSupport
{
  private static final Logger log = LoggerFactory.getLogger(StringTemplateSupport.class);
  
  /**
   * Common sensitive data keys that should be masked in logs and support ZIPs.
   */
  public static final List<String> DEFAULT_SENSITIVE_KEYS = List.of(
      "password", "secret", "token", "apiKey", "accessKey", "secretKey", "credential", "certificate",
      "key", "auth", "authorization", "jwt", "passphrase", "privateKey");
  
  /**
   * Common HTTP status messages for consistent error reporting.
   */
  public static final Map<Status, String> HTTP_STATUS_MESSAGES = Map.of(
      Status.NOT_FOUND, "Resource not found",
      Status.BAD_REQUEST, "Invalid request",
      Status.FORBIDDEN, "Access denied",
      Status.UNAUTHORIZED, "Authentication required",
      Status.INTERNAL_SERVER_ERROR, "Internal server error",
      Status.CONFLICT, "Resource conflict",
      Status.METHOD_NOT_ALLOWED, "Method not allowed");
  
  private StringTemplateSupport() {
    // Utility class, no instances
  }

  /**
   * Creates a formatted error message for REST API responses.
   * Wraps the message in quotes for consistent JSON formatting.
   *
   * @param message the error message template
   * @param args the arguments to include in the message
   * @return a formatted error message string suitable for REST API responses
   */
  public static String formatErrorMessage(String message, Object... args) {
    if (args == null || args.length == 0) {
      return STR."\"\{message}\"";
    }
    
    // Use String Templates to format the message with the provided arguments
    return switch (args.length) {
      case 1 -> STR."\"\{message.formatted(args[0])}\"";
      case 2 -> STR."\"\{message.formatted(args[0], args[1])}\"";
      case 3 -> STR."\"\{message.formatted(args[0], args[1], args[2])}\"";
      default -> STR."\"\{String.format(message, args)}\"";
    };
  }
  
  /**
   * Creates a formatted error message for REST API responses with HTTP status.
   *
   * @param status the HTTP status code
   * @param message the error message template
   * @param args the arguments to include in the message
   * @return a formatted error message string with status information
   */
  public static String formatErrorMessage(Status status, String message, Object... args) {
    String formattedMessage = formatErrorMessage(message, args);
    return STR."\{status.getStatusCode()}: \{formattedMessage}";
  }

  /**
   * Creates a formatted validation error message.
   *
   * @param field the field that failed validation
   * @param message the validation error message
   * @return a formatted validation error message
   */
  public static String formatValidationError(String field, String message) {
    return STR."\{field}: \{message}";
  }
  
  /**
   * Creates a formatted validation error message with multiple fields.
   *
   * @param validationErrors a map of field names to error messages
   * @return a formatted validation error message combining all field errors
   */
  public static String formatValidationErrors(Map<String, String> validationErrors) {
    if (validationErrors == null || validationErrors.isEmpty()) {
      return "Validation failed";
    }
    
    StringJoiner joiner = new StringJoiner(", ");
    validationErrors.forEach((field, message) -> {
      joiner.add(STR."\{field}: \{message}");
    });
    
    return STR."Validation failed: \{joiner}";
  }

  /**
   * Creates a formatted log message with context information.
   * This method is optimized for efficient log parsing by using a consistent format.
   *
   * @param message the log message template
   * @param context the context information to include
   * @return a formatted log message string
   */
  public static String formatLogMessage(String message, Map<String, Object> context) {
    if (context == null || context.isEmpty()) {
      return message;
    }
    
    StringBuilder contextStr = new StringBuilder();
    context.forEach((key, value) -> {
      if (contextStr.length() > 0) {
        contextStr.append(", ");
      }
      // Sanitize any sensitive values before logging
      String valueStr = DEFAULT_SENSITIVE_KEYS.contains(key.toLowerCase()) ? "*****" : String.valueOf(value);
      contextStr.append(STR."\{key}=\{valueStr}");
    });
    
    return STR."\{message} [\{contextStr}]";
  }
  
  /**
   * Creates a formatted log message for repository operations with context.
   *
   * @param operation the operation being performed
   * @param repositoryName the name of the repository
   * @param context additional context information
   * @return a formatted log message for repository operations
   */
  public static String formatRepositoryLogMessage(String operation, String repositoryName, Map<String, Object> context) {
    String baseMessage = STR."Repository operation: \{operation} on '\{repositoryName}'";
    return formatLogMessage(baseMessage, context);
  }

  /**
   * Creates a formatted repository not found message.
   * This is a standardized message format for consistent error reporting.
   *
   * @param repositoryName the name of the repository that was not found
   * @return a formatted repository not found message
   */
  public static String formatRepositoryNotFound(String repositoryName) {
    return STR."\"Repository '\{repositoryName}' not found\"";
  }
  
  /**
   * Creates a formatted repository not found message with format and type information.
   *
   * @param repositoryName the name of the repository that was not found
   * @param format the repository format
   * @param type the repository type
   * @return a formatted repository not found message with format and type
   */
  public static String formatRepositoryNotFound(String repositoryName, String format, String type) {
    return STR."\"Repository '\{repositoryName}' with format '\{format}' and type '\{type}' not found\"";
  }

  /**
   * Creates a formatted exception message that includes suppressed exceptions.
   * This method is particularly useful for REST API error responses where multiple
   * errors need to be reported together.
   *
   * @param exception the exception to format
   * @return a formatted exception message string
   */
  public static String formatExceptionMessage(Throwable exception) {
    if (exception == null) {
      return "\"Unknown error\"";
    }
    
    StringJoiner joiner = new StringJoiner("\n", "\"", "\"");
    joiner.add(exception.getMessage());
    
    Throwable[] suppressed = exception.getSuppressed();
    if (suppressed != null && suppressed.length > 0) {
      for (Throwable t : suppressed) {
        joiner.add(t.getMessage());
      }
    }
    
    return joiner.toString();
  }
  
  /**
   * Creates a formatted exception message with context information.
   *
   * @param exception the exception to format
   * @param context additional context information
   * @return a formatted exception message with context
   */
  public static String formatExceptionMessage(Throwable exception, Map<String, Object> context) {
    String exceptionMessage = formatExceptionMessage(exception);
    if (context == null || context.isEmpty()) {
      return exceptionMessage;
    }
    
    StringBuilder contextStr = new StringBuilder();
    context.forEach((key, value) -> {
      if (contextStr.length() > 0) {
        contextStr.append(", ");
      }
      // Sanitize any sensitive values
      String valueStr = DEFAULT_SENSITIVE_KEYS.contains(key.toLowerCase()) ? "*****" : String.valueOf(value);
      contextStr.append(STR."\{key}=\{valueStr}");
    });
    
    // Remove the closing quote, add context, and close the quote again
    return STR."\{exceptionMessage.substring(0, exceptionMessage.length() - 1)} [\{contextStr}]\"";
  }

  /**
   * Sanitizes sensitive data in a string for use in logs or support ZIPs.
   * Replaces sensitive values with asterisks to prevent exposure of confidential information.
   * This method is particularly important for Support ZIP generation where configuration
   * data may contain credentials or other sensitive information.
   *
   * @param input the input string that may contain sensitive data
   * @param sensitiveKeys list of keys that indicate sensitive data
   * @return sanitized string with sensitive data masked
   */
  public static String sanitizeSensitiveData(String input, List<String> sensitiveKeys) {
    if (input == null || input.isEmpty() || sensitiveKeys == null || sensitiveKeys.isEmpty()) {
      return input;
    }
    
    String result = input;
    for (String key : sensitiveKeys) {
      // Look for patterns like "key":"value" or key=value
      String jsonPattern = STR."\"\{key}\":\"([^\"]+)\"";
      String propPattern = STR."\{key}=([^,\s]+)";
      String xmlPattern = STR."<\{key}>([^<]+)</\{key}>";
      String yamlPattern = STR."\{key}:\s+([^\n]+)";
      
      // Replace with masked values
      result = result.replaceAll(jsonPattern, STR."\"\{key}\":\"*****\"");
      result = result.replaceAll(propPattern, STR."\{key}=*****");
      result = result.replaceAll(xmlPattern, STR."<\{key}>*****</\{key}>");
      result = result.replaceAll(yamlPattern, STR."\{key}: *****");
    }
    
    return result;
  }
  
  /**
   * Sanitizes sensitive data using the default list of sensitive keys.
   *
   * @param input the input string that may contain sensitive data
   * @return sanitized string with sensitive data masked
   */
  public static String sanitizeSensitiveData(String input) {
    return sanitizeSensitiveData(input, DEFAULT_SENSITIVE_KEYS);
  }

  /**
   * Creates a formatted HTTP response message.
   *
   * @param status the HTTP status code
   * @param message the response message
   * @return a formatted HTTP response message
   */
  public static String formatHttpResponse(int status, String message) {
    return STR."\{status}: \{message}";
  }
  
  /**
   * Creates a formatted HTTP response message using Status enum.
   *
   * @param status the HTTP status
   * @param message the response message
   * @return a formatted HTTP response message
   */
  public static String formatHttpResponse(Status status, String message) {
    return STR."\{status.getStatusCode()}: \{message}";
  }
  
  /**
   * Creates a WebApplicationMessageException message with proper formatting.
   * 
   * @param status the HTTP status
   * @param message the error message
   * @return a formatted message for WebApplicationMessageException
   */
  public static String formatWebApplicationMessage(Status status, String message) {
    return STR."\"\{message}\"";
  }
  
  /**
   * Creates a WebApplicationMessageException message with proper formatting and media type.
   * 
   * @param status the HTTP status
   * @param message the error message
   * @param mediaType the media type
   * @return a formatted message for WebApplicationMessageException with media type
   */
  public static String formatWebApplicationMessage(Status status, String message, MediaType mediaType) {
    return formatWebApplicationMessage(status, message);
  }

  /**
   * Creates a formatted message for API documentation.
   * This method standardizes the format of API documentation strings.
   *
   * @param apiName the name of the API
   * @param description the API description
   * @return a formatted API documentation message
   */
  public static String formatApiDocumentation(String apiName, String description) {
    return STR."\{apiName}: \{description}";
  }
  
  /**
   * Creates a formatted message for API documentation with parameters.
   *
   * @param apiName the name of the API
   * @param description the API description
   * @param parameters the API parameters as key-value pairs
   * @return a formatted API documentation message with parameters
   */
  public static String formatApiDocumentation(String apiName, String description, Map<String, String> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return formatApiDocumentation(apiName, description);
    }
    
    StringBuilder paramsStr = new StringBuilder("Parameters:\n");
    parameters.forEach((name, desc) -> {
      paramsStr.append(STR."  - \{name}: \{desc}\n");
    });
    
    return STR."\{apiName}: \{description}\n\{paramsStr}";
  }

  /**
   * Creates a formatted message for repository operations.
   * This method standardizes the format of repository operation messages.
   *
   * @param operation the operation being performed
   * @param repositoryName the name of the repository
   * @param format the repository format
   * @return a formatted repository operation message
   */
  public static String formatRepositoryOperation(String operation, String repositoryName, String format) {
    return STR."\{operation} repository '\{repositoryName}' (format: \{format})";
  }
  
  /**
   * Creates a formatted message for repository operations with type.
   *
   * @param operation the operation being performed
   * @param repositoryName the name of the repository
   * @param format the repository format
   * @param type the repository type
   * @return a formatted repository operation message with type
   */
  public static String formatRepositoryOperation(String operation, String repositoryName, String format, String type) {
    return STR."\{operation} repository '\{repositoryName}' (format: \{format}, type: \{type})";
  }
  
  /**
   * Creates a formatted message for BlobStore operations.
   *
   * @param operation the operation being performed
   * @param blobStoreName the name of the BlobStore
   * @param type the BlobStore type
   * @return a formatted BlobStore operation message
   */
  public static String formatBlobStoreOperation(String operation, String blobStoreName, String type) {
    return STR."\{operation} BlobStore '\{blobStoreName}' (type: \{type})";
  }
  
  /**
   * Creates a formatted message for component operations.
   *
   * @param operation the operation being performed
   * @param componentId the component identifier
   * @param repositoryName the repository name
   * @return a formatted component operation message
   */
  public static String formatComponentOperation(String operation, String componentId, String repositoryName) {
    return STR."\{operation} component '\{componentId}' in repository '\{repositoryName}'";
  }
  
  /**
   * Formats a log message using String Templates with improved readability and parsing efficiency.
   * This method is designed to be used with SLF4J loggers for consistent log formatting.
   *
   * @param logger the SLF4J logger to use
   * @param level the log level ("debug", "info", "warn", "error")
   * @param message the message template
   * @param args the arguments to include in the message
   */
  public static void log(Logger logger, String level, String message, Object... args) {
    if (logger == null) {
      return;
    }
    
    String formattedMessage;
    if (args == null || args.length == 0) {
      formattedMessage = message;
    } else {
      formattedMessage = String.format(message, args);
    }
    
    switch (level.toLowerCase()) {
      case "debug" -> {
        if (logger.isDebugEnabled()) {
          logger.debug(formattedMessage);
        }
      }
      case "info" -> {
        if (logger.isInfoEnabled()) {
          logger.info(formattedMessage);
        }
      }
      case "warn" -> {
        if (logger.isWarnEnabled()) {
          logger.warn(formattedMessage);
        }
      }
      case "error" -> {
        if (logger.isErrorEnabled()) {
          logger.error(formattedMessage);
        }
      }
      default -> {
        if (logger.isInfoEnabled()) {
          logger.info(formattedMessage);
        }
      }
    }
  }
}