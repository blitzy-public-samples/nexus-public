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

import org.sonatype.nexus.security.internal.rest.NexusSecurityApiConstants;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.rest.ApiPrivilegeWithActions;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Abstract base class for API privileges that include repository-specific properties.
 * 
 * @since 3.19
 */
public abstract class ApiPrivilegeWithRepository
    extends ApiPrivilegeWithActions
{
  public static final String FORMAT_KEY = "format";

  public static final String REPOSITORY_KEY = "repository";

  @NotBlank
  @Schema(description = NexusSecurityApiConstants.PRIVILEGE_REPOSITORY_FORMAT_DESCRIPTION)
  private String format;

  @NotBlank
  @Schema(description = NexusSecurityApiConstants.PRIVILEGE_REPOSITORY_DESCRIPTION)
  private String repository;

  /**
   * Default constructor for Jackson deserialization.
   * 
   * @param privilegeType the type of privilege
   */
  public ApiPrivilegeWithRepository(final String privilegeType) {
    super(privilegeType);
  }

  /**
   * Constructs a new instance with the specified properties.
   * 
   * @param type the privilege type
   * @param name the privilege name
   * @param description the privilege description
   * @param readOnly whether the privilege is read-only
   * @param format the repository format
   * @param repository the repository name
   * @param actions the collection of privilege actions
   */
  public ApiPrivilegeWithRepository(final String type,
                                    final String name,
                                    final String description,
                                    final boolean readOnly,
                                    final String format,
                                    final String repository,
                                    final Collection<PrivilegeAction> actions)
  {
    super(type, name, description, readOnly, actions);
    this.format = format;
    this.repository = repository;
  }

  /**
   * Constructs a new instance from an existing Privilege.
   * 
   * @param privilege the privilege to copy properties from
   */
  public ApiPrivilegeWithRepository(final Privilege privilege) {
    super(privilege);
    format = privilege.getPrivilegeProperty(FORMAT_KEY);
    repository = privilege.getPrivilegeProperty(REPOSITORY_KEY);
  }

  /**
   * Sets the repository name.
   * 
   * @param repository the repository name
   */
  public void setRepository(final String repository) {
    this.repository = repository;
  }

  /**
   * Sets the repository format.
   * 
   * @param format the repository format
   */
  public void setFormat(final String format) {
    this.format = format;
  }

  /**
   * Gets the repository name.
   * 
   * @return the repository name
   */
  public String getRepository() {
    return repository;
  }

  /**
   * Gets the repository format.
   * 
   * @return the repository format
   */
  public String getFormat() {
    return format;
  }

  /**
   * Adds repository-specific properties to the privilege.
   * 
   * @param privilege the privilege to add properties to
   * @return the updated privilege
   */
  @Override
  protected Privilege doAsPrivilege(final Privilege privilege) {
    super.doAsPrivilege(privilege);
    privilege.addProperty(FORMAT_KEY, getFormat());
    privilege.addProperty(REPOSITORY_KEY, getRepository());

    return privilege;
  }

  /**
   * Converts actions to a string representation using BREAD format.
   * 
   * @return the string representation of actions
   */
  @Override
  protected String doAsActionString() {
    return toBreadActionString();
  }
}