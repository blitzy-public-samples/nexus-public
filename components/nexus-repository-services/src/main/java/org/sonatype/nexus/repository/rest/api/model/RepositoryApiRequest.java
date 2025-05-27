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

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Repository API request interface defining the contract for repository creation and update operations.
 * Updated for Java 21 compatibility with default methods for enhanced functionality.
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
   * Get the repository format.
   *
   * @return the repository format
   */
  String getFormat();

  /**
   * Get the repository type.
   *
   * @return the repository type
   */
  String getType();

  /**
   * Get the repository online status.
   *
   * @return the repository online status
   */
  Boolean getOnline();
  
  /**
   * Default method to check if the repository is online.
   * Provides a convenient way to check the online status without null checks.
   *
   * @return true if the repository is online, false otherwise
   */
  @JsonIgnore
  default boolean isOnline() {
    return Optional.ofNullable(getOnline()).orElse(false);
  }
  
  /**
   * Default method to get a map of basic repository properties.
   * Useful for logging, debugging, and simple data transfer.
   *
   * @return a map containing the basic repository properties
   */
  @JsonIgnore
  default Map<String, Object> getBasicProperties() {
    return Map.of(
        "name", getName(),
        "format", getFormat(),
        "type", getType(),
        "online", isOnline()
    );
  }
  
  /**
   * Default method to create a string representation of the repository request.
   * Uses Java 21 String Templates for more readable output.
   *
   * @return a string representation of the repository request
   */
  @JsonIgnore
  default String toSummaryString() {
    return STR."Repository[name=\{getName()}, format=\{getFormat()}, type=\{getType()}, online=\{getOnline()}]";
  }
}
