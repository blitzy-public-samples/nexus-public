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

import org.sonatype.nexus.repository.security.RepositoryAdminPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Repository admin privilege API model.
 * 
 * @since 3.19
 */
@Schema(description = "Repository admin privilege")
public class ApiPrivilegeRepositoryAdmin
    extends ApiPrivilegeWithRepository
{
  /**
   * Default constructor for Jackson deserialization.
   */
  @JsonCreator
  protected ApiPrivilegeRepositoryAdmin() {
    super(RepositoryAdminPrivilegeDescriptor.TYPE);
  }

  /**
   * Constructor for creating a new repository admin privilege.
   *
   * @param name the privilege name
   * @param description the privilege description
   * @param readOnly whether the privilege is read-only
   * @param format the repository format
   * @param repository the repository name
   * @param actions the allowed actions
   */
  public ApiPrivilegeRepositoryAdmin(
      @JsonProperty("name") final String name,
      @JsonProperty("description") final String description,
      @JsonProperty("readOnly") final boolean readOnly,
      @JsonProperty("format") final String format,
      @JsonProperty("repository") final String repository,
      @JsonProperty("actions") final Collection<PrivilegeAction> actions)
  {
    super(RepositoryAdminPrivilegeDescriptor.TYPE, name, description, readOnly, format, repository, actions);
  }

  /**
   * Constructor for creating from an existing privilege.
   *
   * @param privilege the privilege to create from
   */
  public ApiPrivilegeRepositoryAdmin(final Privilege privilege) {
    super(privilege);
  }
  
  /**
   * Pattern matching example for handling different privilege types.
   * This demonstrates how Java 21 pattern matching could be used with this class.
   *
   * @param obj the object to check
   * @return true if the object is a compatible privilege type
   */
  public boolean isCompatiblePrivilege(Object obj) {
    return switch (obj) {
      case ApiPrivilegeRepositoryAdmin admin -> true;
      case ApiPrivilegeWithRepository repo when repo.getType().equals(RepositoryAdminPrivilegeDescriptor.TYPE) -> true;
      case Privilege p when p.getType().equals(RepositoryAdminPrivilegeDescriptor.TYPE) -> true;
      default -> false;
    };
  }
}