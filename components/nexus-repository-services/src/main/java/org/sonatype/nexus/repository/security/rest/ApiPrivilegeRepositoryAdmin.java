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
package org.sonatype.nexus.repository.security.rest;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.sonatype.nexus.repository.security.RepositoryAdminPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Repository admin privilege API representation that leverages Java 21 pattern matching features.
 *
 * @since 3.19
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiPrivilegeRepositoryAdmin
    extends ApiPrivilegeWithRepository
{
  /**
   * For Jackson deserialization
   */
  @JsonCreator
  private ApiPrivilegeRepositoryAdmin() {
    super(RepositoryAdminPrivilegeDescriptor.TYPE);
  }

  /**
   * Constructor with all fields
   */
  public ApiPrivilegeRepositoryAdmin(final String name,
                                     final String description,
                                     final boolean readOnly,
                                     final String format,
                                     final String repository,
                                     final Collection<PrivilegeAction> actions)
  {
    super(RepositoryAdminPrivilegeDescriptor.TYPE, name, description, readOnly, format, repository, actions);
  }

  /**
   * Constructor from a Privilege object using pattern matching
   */
  public ApiPrivilegeRepositoryAdmin(final Privilege privilege) {
    super(privilege);
  }
  
  /**
   * Converts a Privilege to an ApiPrivilegeRepositoryAdmin if it matches the expected type,
   * otherwise returns null.
   */
  public static ApiPrivilegeRepositoryAdmin from(final Object obj) {
    return switch (obj) {
      case Privilege p when RepositoryAdminPrivilegeDescriptor.TYPE.equals(p.getType()) -> 
          new ApiPrivilegeRepositoryAdmin(p);
      default -> null;
    };
  }
  
  /**
   * Extracts repository admin privilege properties using record patterns.
   * Returns an Optional containing the format and repository if both are present,
   * otherwise returns an empty Optional.
   */
  public static Optional<Map.Entry<String, String>> extractProperties(final Object obj) {
    return switch (obj) {
      case Privilege p when RepositoryAdminPrivilegeDescriptor.TYPE.equals(p.getType()) -> {
        var format = p.getPrivilegeProperty(FORMAT_KEY);
        var repository = p.getPrivilegeProperty(REPOSITORY_KEY);
        if (format != null && repository != null) {
          yield Optional.of(Map.entry(format, repository));
        }
        yield Optional.empty();
      }
      default -> Optional.empty();
    };
  }
  
  @Override
  protected Privilege doAsPrivilege(final Privilege privilege) {
    // Use pattern matching to simplify type checking and method chaining
    if (privilege instanceof Privilege p) {
      super.doAsPrivilege(p);
      return p;
    }
    return privilege;
  }
}
