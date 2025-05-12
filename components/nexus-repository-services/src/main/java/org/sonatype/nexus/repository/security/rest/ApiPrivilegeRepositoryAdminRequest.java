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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * REST API request model for repository admin privileges.
 *
 * @since 3.19
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiPrivilegeRepositoryAdminRequest
    extends ApiPrivilegeWithRepositoryRequest
{
  /**
   * Default constructor for Jackson deserialization.
   */
  @JsonCreator
  private ApiPrivilegeRepositoryAdminRequest() {
    super();
  }

  /**
   * Constructor for creating a new repository admin privilege request.
   *
   * @param name the privilege name
   * @param description the privilege description
   * @param format the repository format
   * @param repository the repository name
   * @param actions the collection of privilege actions
   */
  public ApiPrivilegeRepositoryAdminRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("description") final String description,
      @JsonProperty("format") final String format,
      @JsonProperty("repository") final String repository,
      @JsonProperty("actions") final Collection<PrivilegeAction> actions)
  {
    super(name, description, format, repository, actions);
  }

  /**
   * Constructor for creating a request from an existing privilege.
   * Uses pattern matching for type safety when handling the privilege object.
   *
   * @param privilege the privilege to create the request from
   */
  public ApiPrivilegeRepositoryAdminRequest(final Privilege privilege) {
    super(privilege);
  }

  /**
   * Converts this request into a Privilege domain object.
   * Sets the type to RepositoryAdminPrivilegeDescriptor.TYPE.
   *
   * @param privilege the privilege to configure
   * @return the configured privilege
   */
  @Override
  protected Privilege doAsPrivilege(final Privilege privilege) {
    super.doAsPrivilege(privilege);
    privilege.setType(RepositoryAdminPrivilegeDescriptor.TYPE);
    return privilege;
  }
}