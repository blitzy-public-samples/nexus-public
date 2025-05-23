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

import org.sonatype.nexus.common.text.StringTemplateSupport;

/**
 * Exception thrown when a repository cannot be found.
 *
 * @since 3.20
 */
public class RepositoryNotFoundException
    extends Exception
{
  private static final String DEFAULT_MESSAGE = "Repository not found";

  /**
   * Constructs a new exception with the default message.
   */
  public RepositoryNotFoundException() {
    super(DEFAULT_MESSAGE);
  }

  /**
   * Constructs a new exception with a message that includes the repository name.
   *
   * @param repositoryName the name of the repository that was not found
   */
  public RepositoryNotFoundException(final String repositoryName) {
    super(formatMessage(repositoryName));
  }

  /**
   * Formats the exception message using Java 21 String Templates.
   *
   * @param repositoryName the name of the repository that was not found
   * @return the formatted error message
   */
  private static String formatMessage(final String repositoryName) {
    if (repositoryName == null || repositoryName.isEmpty()) {
      return DEFAULT_MESSAGE;
    }
    
    // Use StringTemplateSupport for consistent message formatting across the application
    return StringTemplateSupport.formatErrorMessage("Repository '{0}' not found", repositoryName);
  }
}
