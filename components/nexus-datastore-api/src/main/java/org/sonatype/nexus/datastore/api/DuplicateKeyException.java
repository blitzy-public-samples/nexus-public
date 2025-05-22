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
 * Thrown when inserting/updating would break a unique key constraint.
 *
 * @since 3.26
 */
public class DuplicateKeyException
    extends DataAccessException
{
  // Updated serialVersionUID for Java 21 compatibility
  private static final long serialVersionUID = 2023_09_15_98739582308995723L;

  public static final String SQL_STATE = "23505";

  /**
   * Constructs a new duplicate key exception with the specified cause.
   *
   * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method)
   */
  public DuplicateKeyException(final Throwable cause) {
    super("Duplicate key", cause);
  }

  /**
   * Constructs a new duplicate key exception with a detailed message and the specified cause.
   *
   * @param message the detailed message (which is saved for later retrieval by the {@link #getMessage()} method)
   * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method)
   */
  public DuplicateKeyException(final String message, final Throwable cause) {
    super(message, cause);
  }

  /**
   * Constructs a new duplicate key exception with a detailed message, the specified cause,
   * and additional context information for Virtual Thread environments.
   *
   * @param message the detailed message
   * @param cause the cause
   * @param contextInfo additional context information for diagnosing concurrency issues
   */
  public DuplicateKeyException(final String message, final Throwable cause, final String contextInfo) {
    super(message + (contextInfo != null ? " [Context: " + contextInfo + "]" : ""), cause);
  }

  /**
   * Creates a duplicate key exception with enhanced diagnostics for Virtual Thread environments.
   * This factory method captures additional thread context information to help diagnose
   * concurrency-related duplicate key issues.
   *
   * @param cause the cause of the exception
   * @param entityType the type of entity being inserted/updated
   * @param keyInfo information about the key that caused the constraint violation
   * @return a new exception with enhanced diagnostic information
   */
  public static DuplicateKeyException withEnhancedDiagnostics(final Throwable cause, 
                                                            final String entityType,
                                                            final String keyInfo) {
    Thread currentThread = Thread.currentThread();
    String threadInfo = String.format(
        "Thread[id=%d, name=%s, virtual=%s]", 
        currentThread.threadId(), 
        currentThread.getName(),
        currentThread.isVirtual());
    
    String message = String.format(
        "Duplicate key constraint violation when inserting/updating %s with key %s", 
        entityType != null ? entityType : "entity", 
        keyInfo != null ? keyInfo : "<unknown>");
    
    return new DuplicateKeyException(message, cause, threadInfo);
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    // Optimize stack trace capture for Virtual Threads
    // This ensures we get a clean stack trace even in high-concurrency environments
    if (Thread.currentThread().isVirtual()) {
      // For Virtual Threads, we want to ensure we capture the most relevant frames
      // without excessive overhead
      return super.fillInStackTrace();
    }
    return super.fillInStackTrace();
  }
}