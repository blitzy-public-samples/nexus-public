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
package org.sonatype.nexus.repository.rest.api.model;

import javax.validation.ValidationException;

/**
 * Interface defining the contract for repository-related REST API requests.
 * Updated for Java 21 compatibility with default methods.
 *
 * @since 3.24
 */
public interface RepositoryApiRequest
{
  /**
   * Get the repository name.
   *
   * @return the repository name
   */
  String getName();

  /**
   * Get the repository format (e.g., maven2, npm, docker).
   *
   * @return the repository format
   */
  String getFormat();

  /**
   * Get the repository type (e.g., hosted, proxy, group).
   *
   * @return the repository type
   */
  String getType();

  /**
   * Get the repository online status.
   *
   * @return true if the repository is online, false otherwise
   */
  Boolean getOnline();
  
  /**
   * Checks if the repository is online.
   * 
   * @return true if the repository is online, false otherwise
   * @since 3.60
   */
  default boolean isOnline() {
    Boolean online = getOnline();
    return online != null && online;
  }
  
  /**
   * Validates the repository configuration.
   * Implementations can override this method to provide custom validation logic.
   *
   * @throws ValidationException if the repository configuration is invalid
   * @since 3.60
   */
  default void validate() throws ValidationException {
    if (getName() == null || getName().isEmpty()) {
      throw new ValidationException("Repository name cannot be null or empty");
    }
    if (getFormat() == null || getFormat().isEmpty()) {
      throw new ValidationException("Repository format cannot be null or empty");
    }
    if (getType() == null || getType().isEmpty()) {
      throw new ValidationException("Repository type cannot be null or empty");
    }
    if (getOnline() == null) {
      throw new ValidationException("Repository online status cannot be null");
    }
  }
  
  /**
   * Returns a formatted string representation of the repository configuration using Java 21 String Templates.
   * 
   * @return a formatted string representation of the repository configuration
   * @since 3.60
   */
  default String toFormattedString() {
    return STR."Repository[name=\{getName()}, format=\{getFormat()}, type=\{getType()}, online=\{getOnline()}]"; 
  }
}
