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

import org.sonatype.nexus.repository.security.RepositoryContentSelectorPrivilegeDescriptor;
import org.sonatype.nexus.security.internal.rest.NexusSecurityApiConstants;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Repository content selector privilege API model.
 * 
 * @since 3.19
 */
public class ApiPrivilegeRepositoryContentSelector
    extends ApiPrivilegeWithRepository
{
  public static final String CSEL_KEY = "contentSelector";

  @NotBlank
  @JsonProperty
  @ApiModelProperty(NexusSecurityApiConstants.PRIVILEGE_CONTENT_SELECTOR_DESCRIPTION)
  private String contentSelector;

  /**
   * Default constructor for deserialization
   */
  private ApiPrivilegeRepositoryContentSelector() {
    super(RepositoryContentSelectorPrivilegeDescriptor.TYPE);
  }

  /**
   * Constructor for creating a new privilege
   */
  public ApiPrivilegeRepositoryContentSelector(final String name,
                                               final String description,
                                               final boolean readOnly,
                                               final String format,
                                               final String repository,
                                               final String contentSelector,
                                               final Collection<PrivilegeAction> actions)
  {
    super(RepositoryContentSelectorPrivilegeDescriptor.TYPE, name, description, readOnly, format, repository, actions);
    this.contentSelector = contentSelector;
  }

  /**
   * Constructor for creating from an existing privilege
   */
  public ApiPrivilegeRepositoryContentSelector(final Privilege privilege) {
    super(privilege);
    // Use pattern matching to extract property
    if (privilege instanceof Privilege p) {
      contentSelector = p.getPrivilegeProperty(CSEL_KEY);
    }
  }

  /**
   * Sets the content selector
   */
  public void setContentSelector(final String contentSelector) {
    this.contentSelector = contentSelector;
  }

  /**
   * Gets the content selector
   */
  public String getContentSelector() {
    return contentSelector;
  }

  @Override
  protected Privilege doAsPrivilege(final Privilege privilege) {
    // Use pattern matching to simplify type checking and method chaining
    if (privilege instanceof Privilege p) {
      super.doAsPrivilege(p);
      p.addProperty(CSEL_KEY, contentSelector);
      return p;
    }
    return privilege;
  }
}
