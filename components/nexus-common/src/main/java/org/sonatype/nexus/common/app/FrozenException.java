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
 * Thrown when something attempts to write while the application is frozen.
 *
 * <p>
 * When creating instances of this exception, consider using Java 21 String Templates for more
 * readable error messages. For example:
 * <pre>
 * throw new FrozenException(STR."Application is frozen: \{reason}");
 * </pre>
 * instead of:
 * <pre>
 * throw new FrozenException("Application is frozen: " + reason);
 * </pre>
 * </p>
 *
 * @since 3.21
 */
public class FrozenException
    extends NotWritableException
{
  private static final long serialVersionUID = -5328665935242655134L;

  /**
   * Constructs a new exception with the specified detail message.
   *
   * @param message the detail message
   */
  public FrozenException(final String message) {
    super(message);
  }

  /**
   * Constructs a new exception with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public FrozenException(final String message, final Throwable cause) {
    super(message, cause);
  }
}