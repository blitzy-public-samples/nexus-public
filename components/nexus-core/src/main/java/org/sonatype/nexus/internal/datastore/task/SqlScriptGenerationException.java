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

/**
 * Exception thrown when SQL script generation fails.
 * 
 * This exception supports Java 21 pattern matching for switch expressions and
 * uses String Templates for improved message formatting.
 */
public class SqlScriptGenerationException
    extends RuntimeException
{
  private final ErrorCategory category;

  /**
   * Error categories for SQL script generation exceptions.
   * Used for pattern matching in switch expressions.
   */
  public enum ErrorCategory {
    SYNTAX_ERROR,
    VALIDATION_ERROR,
    IO_ERROR,
    UNKNOWN_ERROR
  }

  /**
   * Creates a new exception with the specified message.
   * 
   * @param message the error message
   */
  public SqlScriptGenerationException(final String message) { 
    super(message);
    this.category = ErrorCategory.UNKNOWN_ERROR;
  }

  /**
   * Creates a new exception with the specified message and error category.
   * 
   * @param message the error message
   * @param category the error category for pattern matching
   */
  public SqlScriptGenerationException(final String message, final ErrorCategory category) { 
    super(message);
    this.category = category;
  }

  /**
   * Creates a new exception with the specified message and cause.
   * 
   * @param message the error message
   * @param cause the cause of this exception
   */
  public SqlScriptGenerationException(final String message, final Throwable cause) { 
    super(message, cause);
    this.category = ErrorCategory.UNKNOWN_ERROR;
  }

  /**
   * Creates a new exception with the specified message, cause, and error category.
   * 
   * @param message the error message
   * @param cause the cause of this exception
   * @param category the error category for pattern matching
   */
  public SqlScriptGenerationException(final String message, final Throwable cause, final ErrorCategory category) { 
    super(message, cause);
    this.category = category;
  }

  /**
   * Creates a new syntax error exception with the specified message.
   * Uses String Templates for message formatting.
   * 
   * @param scriptName the name of the script with syntax errors
   * @param details additional error details
   * @return a new exception with formatted message
   */
  public static SqlScriptGenerationException syntaxError(String scriptName, String details) {
    return new SqlScriptGenerationException(
        STR."Syntax error in SQL script \{scriptName}: \{details}",
        ErrorCategory.SYNTAX_ERROR);
  }

  /**
   * Creates a new validation error exception with the specified message.
   * Uses String Templates for message formatting.
   * 
   * @param scriptName the name of the script that failed validation
   * @param details additional error details
   * @return a new exception with formatted message
   */
  public static SqlScriptGenerationException validationError(String scriptName, String details) {
    return new SqlScriptGenerationException(
        STR."Validation error in SQL script \{scriptName}: \{details}",
        ErrorCategory.VALIDATION_ERROR);
  }

  /**
   * Creates a new IO error exception with the specified message.
   * Uses String Templates for message formatting.
   * 
   * @param scriptName the name of the script with IO errors
   * @param details additional error details
   * @return a new exception with formatted message
   */
  public static SqlScriptGenerationException ioError(String scriptName, String details) {
    return new SqlScriptGenerationException(
        STR."IO error processing SQL script \{scriptName}: \{details}",
        ErrorCategory.IO_ERROR);
  }

  /**
   * Gets the error category of this exception.
   * 
   * @return the error category
   */
  public ErrorCategory getCategory() {
    return category;
  }
}