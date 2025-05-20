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
package org.sonatype.nexus.repository.rest.internal.api;

/**
 * Repository detail transfer object.
 * Converted to a record for improved immutability and data handling.
 *
 * @since 3.30
 */
public record RepositoryDetailXO(String name,
                                String type,
                                String format,
                                String url,
                                RepositoryStatusXO status) {

  /**
   * Constructor with validation using pattern matching instead of manual null checks.
   */
  public RepositoryDetailXO {
    // Validate non-null fields using pattern matching
    if (name == null || type == null || format == null || url == null || status == null) {
      throw new NullPointerException("All fields in RepositoryDetailXO must be non-null");
    }
  }

  /**
   * Returns the repository name.
   * Maintained for API compatibility.
   *
   * @return the repository name
   */
  public String getName() {
    return switch(this) {
      case RepositoryDetailXO(String n, _, _, _, _) -> n;
    };
  }

  /**
   * Returns the repository type.
   * Maintained for API compatibility.
   *
   * @return the repository type
   */
  public String getType() {
    return switch(this) {
      case RepositoryDetailXO(_, String t, _, _, _) -> t;
    };
  }

  /**
   * Returns the repository format.
   * Maintained for API compatibility.
   *
   * @return the repository format
   */
  public String getFormat() {
    return switch(this) {
      case RepositoryDetailXO(_, _, String f, _, _) -> f;
    };
  }

  /**
   * Returns the repository URL.
   * Maintained for API compatibility.
   *
   * @return the repository URL
   */
  public String getUrl() {
    return switch(this) {
      case RepositoryDetailXO(_, _, _, String u, _) -> u;
    };
  }

  /**
   * Returns the repository status.
   * Maintained for API compatibility.
   *
   * @return the repository status
   */
  public RepositoryStatusXO getStatus() {
    return switch(this) {
      case RepositoryDetailXO(_, _, _, _, RepositoryStatusXO s) -> s;
    };
  }
}