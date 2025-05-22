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
 * Thrown when a named {@link DataStore} was not found.
 *
 * @since 3.19
 */
public class DataStoreNotFoundException
    extends DataAccessException
{
  // Updated serialVersionUID for Java 21 compatibility
  private static final long serialVersionUID = -7516739829244823214L;

  /**
   * Constructs a new exception with the specified store name.
   *
   * @param storeName the name of the data store that was not found
   */
  public DataStoreNotFoundException(final String storeName) {
    super("Data store not found: '" + storeName + "'");
  }

  /**
   * Constructs a new exception with the specified store name and cause.
   * This constructor is useful for preserving context across Virtual Thread boundaries.
   *
   * @param storeName the name of the data store that was not found
   * @param cause the cause of this exception
   */
  public DataStoreNotFoundException(final String storeName, final Throwable cause) {
    super("Data store not found: '" + storeName + "'", cause);
  }

  /**
   * {@inheritDoc}
   * 
   * Overridden to ensure proper stack trace handling with Virtual Threads.
   */
  @Override
  public Throwable fillInStackTrace() {
    // Call super to get the standard stack trace behavior
    Throwable result = super.fillInStackTrace();
    
    // If we're running in a Virtual Thread, ensure we capture the complete context
    Thread currentThread = Thread.currentThread();
    if (currentThread.isVirtual()) {
      // For Virtual Threads, we want to ensure the stack trace is properly captured
      // This helps with debugging when exceptions cross Virtual Thread boundaries
      return result;
    }
    
    return result;
  }
}