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
package org.sonatype.nexus.common.app;

/**
 * Thrown when the application is unable to accept writes.
 *
 * This may be because the application is frozen or the underlying storage is read-only.
 *
 * <p>
 * When creating instances of this exception, consider using Java 21 String Templates for more
 * readable error messages. For example:
 * <pre>
 * throw new NotWritableException(STR."Repository \{repoName} is read-only");
 * </pre>
 * instead of:
 * <pre>
 * throw new NotWritableException("Repository " + repoName + " is read-only");
 * </pre>
 * </p>
 *
 * @since 3.21
 */
public class NotWritableException
    extends IllegalStateException
{
  private static final long serialVersionUID = 50364435356863049L;

  /**
   * Constructs a new exception with the specified detail message.
   *
   * @param message the detail message
   */
  public NotWritableException(final String message) {
    super(message);
  }

  /**
   * Constructs a new exception with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public NotWritableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}