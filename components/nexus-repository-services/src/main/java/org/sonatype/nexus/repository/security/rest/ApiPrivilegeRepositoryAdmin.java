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

/**
 * Repository admin privilege API model.
 * 
 * @since 3.19
 */
public class ApiPrivilegeRepositoryAdmin
    extends ApiPrivilegeWithRepository
{
  /**
   * Default constructor for Jackson deserialization.
   * 
   * Using {@link JsonCreator} to explicitly mark this constructor for Jackson,
   * ensuring compatibility with Jackson 2.16.1 deserialization behavior.
   */
  @JsonCreator
  protected ApiPrivilegeRepositoryAdmin() {
    super(RepositoryAdminPrivilegeDescriptor.TYPE);
  }

  /**
   * Constructs a new instance with the specified properties.
   * 
   * @param name the privilege name
   * @param description the privilege description
   * @param readOnly whether the privilege is read-only
   * @param format the repository format
   * @param repository the repository name
   * @param actions the collection of privilege actions
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
   * Constructs a new instance from an existing Privilege.
   * 
   * @param privilege the privilege to copy properties from
   */
  public ApiPrivilegeRepositoryAdmin(final Privilege privilege) {
    super(privilege);
  }
  
  /**
   * Pattern matching example for handling Privilege objects.
   * This demonstrates how Java 21 pattern matching can be used to process different types.
   * 
   * @param obj the object to check
   * @return true if the object is a compatible privilege, false otherwise
   */
  public static boolean isCompatiblePrivilege(Object obj) {
    // Using Java 21 pattern matching to check and extract type information in one step
    return obj instanceof Privilege privilege && 
           RepositoryAdminPrivilegeDescriptor.TYPE.equals(privilege.getType());
  }
}
