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

import java.util.concurrent.ExecutionException;

/**
 * Exception thrown when a serialized transaction access conflict occurs in the database.
 * This typically happens when multiple transactions attempt to modify the same data concurrently
 * and the database cannot serialize the access due to transaction isolation constraints.
 * 
 * Enhanced for Java 21 to provide better diagnostics in Virtual Thread environments.
 */
public class SerializedAccessException
    extends DataAccessException
{
  // Updated serialVersionUID for Java 21 compatibility
  private static final long serialVersionUID = 2L;

  public static final String SQL_STATE = "40001";
  
  private static final String DEFAULT_MESSAGE = "Serialized access conflict";

  /**
   * Constructs a new serialized access exception with the default message and the specified cause.
   *
   * @param cause the cause of this exception
   */
  public SerializedAccessException(final Throwable cause) {
    super(DEFAULT_MESSAGE, cause);
  }
  
  /**
   * Constructs a new serialized access exception with a custom message and the specified cause.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public SerializedAccessException(final String message, final Throwable cause) {
    super(message, cause);
  }
  
  /**
   * Unwraps the exception chain to find the root cause, handling Virtual Thread specific
   * exception chains that may include ExecutionException wrappers.
   *
   * @return the root cause of this exception
   */
  @Override
  public Throwable getRootCause() {
    Throwable rootCause = this;
    while (rootCause.getCause() != null) {
      rootCause = rootCause.getCause();
      // Special handling for ExecutionException which is common in Virtual Thread operations
      if (rootCause instanceof ExecutionException && rootCause.getCause() != null) {
        rootCause = rootCause.getCause();
      }
    }
    return rootCause;
  }
  
  /**
   * Creates a more descriptive message for Virtual Thread environments, including information
   * about the concurrent operation context if available.
   *
   * @return enhanced error message with concurrency context
   */
  @Override
  public String getMessage() {
    String baseMessage = super.getMessage();
    Thread currentThread = Thread.currentThread();
    
    if (currentThread.isVirtual()) {
      return baseMessage + " (detected in Virtual Thread " + currentThread.getName() + ")";
    }
    
    return baseMessage;
  }
}
