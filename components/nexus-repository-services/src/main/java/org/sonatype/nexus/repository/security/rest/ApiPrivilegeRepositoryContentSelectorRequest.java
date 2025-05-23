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
import java.util.Objects;

import org.sonatype.nexus.repository.security.RepositoryContentSelectorPrivilegeDescriptor;
import org.sonatype.nexus.security.internal.rest.NexusSecurityApiConstants;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Repository Content Selector Privilege Request model.
 * 
 * @since 3.19
 */
public class ApiPrivilegeRepositoryContentSelectorRequest
    extends ApiPrivilegeWithRepositoryRequest
{
  public static final String CSEL_KEY = "contentSelector";

  @NotBlank
  @ApiModelProperty(NexusSecurityApiConstants.PRIVILEGE_CONTENT_SELECTOR_DESCRIPTION)
  private String contentSelector;

  /**
   * Default constructor for deserialization
   */
  @JsonCreator
  private ApiPrivilegeRepositoryContentSelectorRequest() {
    super();
  }

  /**
   * Constructor for creating a new request with all fields
   */
  public ApiPrivilegeRepositoryContentSelectorRequest(final String name,
                                                      final String description,
                                                      final String format,
                                                      final String repository,
                                                      final String contentSelector,
                                                      final Collection<PrivilegeAction> actions)
  {
    super(name, description, format, repository, actions);
    this.contentSelector = Objects.requireNonNull(contentSelector, "Content selector cannot be null");
  }

  /**
   * Constructor that creates a request from an existing privilege using Record Pattern matching
   */
  public ApiPrivilegeRepositoryContentSelectorRequest(final Privilege privilege) {
    super(privilege);
    
    // Use pattern matching to extract properties from the privilege
    if (privilege != null && privilege.getProperties() instanceof Map<String, String> properties) {
      this.contentSelector = properties.get(CSEL_KEY);
    } else {
      this.contentSelector = privilege != null ? privilege.getPrivilegeProperty(CSEL_KEY) : null;
    }
  }

  /**
   * Sets the content selector value
   */
  @JsonProperty
  public void setContentSelector(final String contentSelector) {
    this.contentSelector = contentSelector;
  }

  /**
   * Gets the content selector value
   */
  public String getContentSelector() {
    return contentSelector;
  }

  /**
   * Converts this request to a Privilege domain object using pattern matching for validation
   */
  @Override
  protected Privilege doAsPrivilege(final Privilege privilege) {
    super.doAsPrivilege(privilege);
    
    // Set the privilege type and add content selector property
    privilege.setType(RepositoryContentSelectorPrivilegeDescriptor.TYPE);
    
    // Use pattern matching to validate contentSelector before adding it to the privilege
    switch (contentSelector) {
      case String selector when selector != null && !selector.isBlank() -> 
        privilege.addProperty(CSEL_KEY, selector);
      case null -> 
        throw new IllegalArgumentException("Content selector cannot be null");
      default -> 
        throw new IllegalArgumentException("Content selector cannot be blank");
    }
    
    return privilege;
  }
}
