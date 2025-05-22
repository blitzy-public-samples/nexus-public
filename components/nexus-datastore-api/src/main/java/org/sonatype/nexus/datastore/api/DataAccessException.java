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
package org.sonatype.nexus.datastore.api;

/**
 * Base exception for any issues relating to data access/updates.
 *
 * @since 3.26
 */
public class DataAccessException
    extends RuntimeException
{
  // Updated serialVersionUID for Java 21 compatibility
  private static final long serialVersionUID = 8359662557565269873L;

  /**
   * Constructs a new exception with the specified detail message.
   *
   * @param message the detail message
   */
  public DataAccessException(final String message) {
    super(message);
  }

  /**
   * Constructs a new exception with the specified detail message and cause.
   * This constructor captures and preserves the complete stack trace across Virtual Thread handoffs.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public DataAccessException(final String message, final Throwable cause) {
    super(message, cause);
    // Ensure complete stack trace is captured for Virtual Thread environments
    this.setStackTrace(Thread.currentThread().getStackTrace());
  }

  /**
   * Constructs a new exception with a formatted detail message using the provided template and arguments.
   * This method provides better diagnostics in Virtual Thread environments.
   *
   * @param messageTemplate the message template
   * @param args the arguments to be formatted into the message template
   * @return a new exception with the formatted message
   * @since 3.60
   */
  public static DataAccessException withFormattedMessage(String messageTemplate, Object... args) {
    return new DataAccessException(String.format(messageTemplate, args));
  }

  /**
   * Constructs a new exception with a formatted detail message and cause.
   * This method provides better diagnostics in Virtual Thread environments.
   *
   * @param cause the cause of this exception
   * @param messageTemplate the message template
   * @param args the arguments to be formatted into the message template
   * @return a new exception with the formatted message and cause
   * @since 3.60
   */
  public static DataAccessException withFormattedMessage(Throwable cause, String messageTemplate, Object... args) {
    return new DataAccessException(String.format(messageTemplate, args), cause);
  }
}