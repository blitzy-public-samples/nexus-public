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

import static java.lang.StringTemplate.STR;

/**
 * Exception thrown when operations are attempted on incompatible repositories.
 *
 * @since 3.20
 */
public class IncompatibleRepositoryException extends Exception
{
  /**
   * Constructs a new exception with the specified detail message.
   *
   * @param message the detail message
   */
  public IncompatibleRepositoryException(final String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a formatted detail message using Java 21 String Templates.
   *
   * @param repositoryName the name of the repository
   * @param formatName the format name
   * @return a new exception with a formatted message
   */
  public static IncompatibleRepositoryException withRepositoryAndFormat(String repositoryName, String formatName) {
    return new IncompatibleRepositoryException(
        STR."Repository '\{repositoryName}' is not compatible with format '\{formatName}'.");
  }

  /**
   * Constructs a new exception with a formatted detail message using Java 21 String Templates.
   *
   * @param repositoryName the name of the repository
   * @param expectedType the expected repository type
   * @return a new exception with a formatted message
   */
  public static IncompatibleRepositoryException withRepositoryAndType(String repositoryName, String expectedType) {
    return new IncompatibleRepositoryException(
        STR."Repository '\{repositoryName}' is not of expected type '\{expectedType}'.");
  }
}