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
package org.sonatype.nexus.internal.datastore.task;

import static java.lang.StringTemplate.STR;

/**
 * Exception thrown when there is insufficient storage space available for an operation.
 */
public class InsufficientStorageException
    extends RuntimeException
{
  /**
   * Constructs a new exception with the specified message.
   *
   * @param message the detail message
   */
  public InsufficientStorageException(final String message) { super(message); }

  /**
   * Constructs a new exception with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public InsufficientStorageException(final String message, final Throwable cause) { super(message, cause); }
  
  /**
   * Creates a formatted exception message using String Templates.
   *
   * @param location the storage location
   * @param available the available space in bytes
   * @param required the required space in bytes
   * @return a formatted exception message
   */
  public static String formatMessage(String location, long available, long required) {
    return STR."Insufficient storage at \{location}: \{available} bytes available, \{required} bytes required";
  }
  
  /**
   * Creates a new exception with a formatted message using String Templates.
   *
   * @param location the storage location
   * @param available the available space in bytes
   * @param required the required space in bytes
   * @return a new exception with a formatted message
   */
  public static InsufficientStorageException create(String location, long available, long required) {
    return new InsufficientStorageException(formatMessage(location, available, required));
  }
  
  /**
   * Classifies the cause of this exception using pattern matching for switch.
   * 
   * @return a classification of the exception cause
   */
  public String classifyCause() {
    return switch (getCause()) {
      case null -> "No underlying cause";
      case SecurityException e -> "Security violation: " + e.getMessage();
      case java.io.IOException e -> "I/O error: " + e.getMessage();
      case IllegalArgumentException e -> "Invalid argument: " + e.getMessage();
      case Throwable t when t.getMessage() != null -> "Other error: " + t.getMessage();
      case Throwable t -> "Unknown error: " + t.getClass().getSimpleName();
    };
  }
  
  /**
   * Gets the error type based on the cause using pattern matching.
   * 
   * @return the error type
   */
  public ErrorType getErrorType() {
    return switch (getCause()) {
      case null -> ErrorType.UNKNOWN;
      case SecurityException e -> ErrorType.SECURITY;
      case java.io.IOException e -> ErrorType.IO;
      case IllegalArgumentException e -> ErrorType.VALIDATION;
      case Throwable t when t instanceof RuntimeException -> ErrorType.RUNTIME;
      case Throwable t -> ErrorType.SYSTEM;
    };
  }
  
  /**
   * Enumeration of error types for classification.
   */
  public enum ErrorType {
    UNKNOWN,
    SECURITY,
    IO,
    VALIDATION,
    RUNTIME,
    SYSTEM
  }
}