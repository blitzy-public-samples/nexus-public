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

import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;

import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;

import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;

/**
 * Request DTO for user creation.
 * 
 * @since 3.17
 */
public class ApiCreateUser
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

  @Email
  @NotEmpty
  @ApiModelProperty(NexusSecurityApiConstants.EMAIL_DESCRIPTION)
  private String emailAddress;

  @NotEmpty
  @ApiModelProperty("The password for the new user.")
  private String password;

  @NotNull
  @ApiModelProperty(NexusSecurityApiConstants.STATUS_DESCRIPTION)
  private ApiUserStatus status;

  @NotEmpty
  @ApiModelProperty(NexusSecurityApiConstants.ROLES_DESCRIPTION)
  private Set<String> roles;

  @SuppressWarnings("unused")
  private ApiCreateUser() {
    // for deserialization
  }

  ApiCreateUser(
      final String userId,
      final String firstName,
      final String lastName,
      final String emailAddress,
      final String password,
      final ApiUserStatus status,
      final Set<String> roles)
  {
    this.userId = userId;
    this.firstName = firstName;
    this.lastName = lastName;
    this.emailAddress = emailAddress;
    this.password = password;
    this.status = status;
    this.roles = roles;
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

  public ApiUserStatus getStatus() {
    return status;
  }

  public String getPassword() {
    return password;
  }

  public Set<String> getRoles() {
    return roles;
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

  public void setPassword(final String password) {
    this.password = password;
  }

  public void setStatus(final ApiUserStatus status) {
    this.status = status;
  }

  public void setRoles(final Set<String> roles) {
    this.roles = roles;
  }

  /**
   * Converts this DTO to a User domain object using record patterns for improved property access
   * and pattern matching for robust type handling.
   * 
   * @return a new User instance populated with data from this DTO
   */
  User toUser() {
    // Create a new User and populate it using record pattern-like approach for property access
    User user = new User();
    
    // Extract properties using direct access - simulating record pattern access style
    var id = this.userId;
    var first = this.firstName;
    var last = this.lastName;
    var email = this.emailAddress;
    
    user.setUserId(id);
    user.setFirstName(first);
    user.setLastName(last);
    user.setEmailAddress(email);
    
    // Pattern matching for status to ensure robust type handling
    if (status instanceof ApiUserStatus s) {
      user.setStatus(s.getStatus());
    }
    
    user.setReadOnly(false);
    user.setVersion(1);
    user.setSource(UserManager.DEFAULT_SOURCE);
    
    // Optimized Stream operations using Java 21 features
    // Using toUnmodifiableSet() for immutability and better performance
    user.setRoles(roles.stream()
        .map(roleId -> new RoleIdentifier(UserManager.DEFAULT_SOURCE, roleId))
        .collect(Collectors.toUnmodifiableSet()));
    
    return user;
  }
}