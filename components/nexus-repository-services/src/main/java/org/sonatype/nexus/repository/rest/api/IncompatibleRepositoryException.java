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
 * Exception thrown when a repository is incompatible with a requested operation.
 * Uses Java 21 String Templates for improved message clarity and formatting.
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
   * Constructs a new exception with a formatted message using String Templates.
   *
   * @param repositoryName the name of the incompatible repository
   * @param reason the reason for incompatibility
   * @return a new exception with a formatted message
   */
  public static IncompatibleRepositoryException create(String repositoryName, String reason) {
    return new IncompatibleRepositoryException(
        StringTemplateSupport.formatRepositoryIncompatibility(repositoryName, reason));
  }

  /**
   * Constructs a new exception with a formatted message using String Templates.
   *
   * @param messageTemplate the message template
   * @param args the arguments to be interpolated into the template
   * @return a new exception with a formatted message
   */
  public static IncompatibleRepositoryException withTemplate(String messageTemplate, Object... args) {
    return new IncompatibleRepositoryException(
        StringTemplateSupport.formatErrorMessage(messageTemplate, args));
  }
}