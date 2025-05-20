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

package org.sonatype.nexus.security.internal.rest;

import java.util.HashSet;
import java.util.Set;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * REST API representation of a user.
 *
 * @since 3.17
 */
public class ApiUser
{
  @NotBlank
  @ApiModelProperty(NexusSecurityApiConstants.USER_ID_DESCRIPTION)
  private String userId;

  @NotEmpty
  @ApiModelProperty(NexusSecurityApiConstants.FIRST_NAME_DESCRIPTION)
  private String firstName;

  @NotEmpty
  @ApiModelProperty(NexusSecurityApiConstants.LAST_NAME_DESCRIPTION)
  private String lastName;

  @NotEmpty
  @Email
  @ApiModelProperty(NexusSecurityApiConstants.EMAIL_DESCRIPTION)
  private String emailAddress;

  @NotBlank
  @ApiModelProperty(NexusSecurityApiConstants.SOURCE_DESCRIPTION)
  private String source;

  @NotNull
  @ApiModelProperty(NexusSecurityApiConstants.STATUS_DESCRIPTION)
  private ApiUserStatus status;

  @ApiModelProperty("Indicates whether the user's properties could be modified by the Nexus Repository Manager. "
      + "When false only roles are considered during update.")
  private boolean readOnly;

  @NotEmpty
  @ApiModelProperty(NexusSecurityApiConstants.ROLES_DESCRIPTION)
  private Set<String> roles;

  @ApiModelProperty("The roles which the user has been assigned in an external source, "
      + "e.g. LDAP group. These cannot be changed within the Nexus Repository Manager.")
  private Set<String> externalRoles;

  /**
   * Default constructor for deserialization
   */
  @SuppressWarnings("unused")
  private ApiUser() {
    // deserialization
  }

  /**
   * Constructs a new ApiUser with all required fields
   * 
   * @param userId the user ID
   * @param firstName the user's first name
   * @param lastName the user's last name
   * @param emailAddress the user's email address
   * @param source the source of the user
   * @param status the user's status
   * @param readOnly whether the user is read-only
   * @param roles the user's roles
   * @param externalRoles the user's external roles
   */
  ApiUser(
      final String userId,
      final String firstName,
      final String lastName,
      final String emailAddress,
      final String source,
      final ApiUserStatus status,
      final boolean readOnly,
      final Set<String> roles,
      final Set<String> externalRoles) // NOSONAR
  {
    this.userId = userId;
    this.firstName = firstName;
    this.lastName = lastName;
    this.emailAddress = emailAddress;
    this.source = source;
    this.status = status;
    this.readOnly = readOnly;
    this.roles = roles;
    this.externalRoles = externalRoles;
  }

  public String getUserId() {
    return userId;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public String getEmailAddress() {
    return emailAddress;
  }

  public String getSource() {
    return source;
  }

  public ApiUserStatus getStatus() {
    return status;
  }

  public boolean isReadOnly() {
    return readOnly;
  }

  public Set<String> getRoles() {
    return roles;
  }

  @Nullable
  public Set<String> getExternalRoles() {
    return externalRoles;
  }

  public void setExternalRoles(final Set<String> externalRoles) {
    this.externalRoles = externalRoles;
  }

  public void setUserId(final String userId) {
    this.userId = userId;
  }

  public void setFirstName(final String firstName) {
    this.firstName = firstName;
  }

  public void setLastName(final String lastName) {
    this.lastName = lastName;
  }

  public void setEmailAddress(final String emailAddress) {
    this.emailAddress = emailAddress;
  }

  public void setSource(final String source) {
    this.source = source;
  }

  public void setStatus(final ApiUserStatus status) {
    this.status = status;
  }

  public void setReadOnly(final boolean readOnly) {
    this.readOnly = readOnly;
  }

  public void setRoles(final Set<String> roles) {
    this.roles = roles;
  }

  /**
   * Converts this ApiUser to a User entity
   * 
   * @return the converted User entity
   */
  User toUser() {
    User user = new User();
    user.setUserId(userId);
    user.setFirstName(firstName);
    user.setLastName(lastName);
    user.setEmailAddress(emailAddress);
    user.setSource(source);
    
    // Use pattern matching for status conversion
    user.setStatus(switch (status) {
      case null -> null;
      case ApiUserStatus s -> s.getStatus();
    });
    
    user.setReadOnly(readOnly);
    user.setVersion(1);

    Set<RoleIdentifier> roleIdentifiers = new HashSet<>();
    
    // Use pattern matching for role handling with enhanced for loop
    for (var role : roles) {
      roleIdentifiers.add(new RoleIdentifier(UserManager.DEFAULT_SOURCE, role));
    }
    
    // Use pattern matching for external roles with enhanced for loop if not null
    if (externalRoles != null) {
      for (var role : externalRoles) {
        roleIdentifiers.add(new RoleIdentifier(source, role));
      }
    }
    
    user.setRoles(roleIdentifiers);
    return user;
  }
}
