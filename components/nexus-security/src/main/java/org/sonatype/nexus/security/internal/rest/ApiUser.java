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
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;

import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;

/**
 * REST API representation of a user.
 * Enhanced with Java 21 pattern matching for improved data handling and type safety.
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
   * Constructor with all fields for creating a new ApiUser
   * 
   * @param userId User ID
   * @param firstName First name
   * @param lastName Last name
   * @param emailAddress Email address
   * @param source Source
   * @param status User status
   * @param readOnly Whether user is read-only
   * @param roles User roles
   * @param externalRoles External roles
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
    this.userId = Objects.requireNonNull(userId, "userId cannot be null");
    this.firstName = Objects.requireNonNull(firstName, "firstName cannot be null");
    this.lastName = Objects.requireNonNull(lastName, "lastName cannot be null");
    this.emailAddress = Objects.requireNonNull(emailAddress, "emailAddress cannot be null");
    this.source = Objects.requireNonNull(source, "source cannot be null");
    this.status = Objects.requireNonNull(status, "status cannot be null");
    this.readOnly = readOnly;
    this.roles = Objects.requireNonNull(roles, "roles cannot be null");
    this.externalRoles = externalRoles;
  }
  
  /**
   * Factory method to create an ApiUser from a User object using pattern matching
   * 
   * @param user The User object to convert
   * @return A new ApiUser instance
   */
  public static ApiUser fromUser(User user) {
    if (user instanceof User u) {
      ApiUser apiUser = new ApiUser();
      apiUser.setUserId(u.getUserId());
      apiUser.setFirstName(u.getFirstName());
      apiUser.setLastName(u.getLastName());
      apiUser.setEmailAddress(u.getEmailAddress());
      apiUser.setSource(u.getSource());
      apiUser.setStatus(ApiUserStatus.fromStatus(u.getStatus()));
      apiUser.setReadOnly(u.isReadOnly());
      
      // Extract roles using pattern matching
      Set<String> defaultRoles = new HashSet<>();
      Set<String> extRoles = new HashSet<>();
      
      if (u.getRoles() != null) {
        for (RoleIdentifier role : u.getRoles()) {
          if (role instanceof RoleIdentifier(var source, var roleId)) {
            if (UserManager.DEFAULT_SOURCE.equals(source)) {
              defaultRoles.add(roleId);
            } else if (u.getSource().equals(source)) {
              extRoles.add(roleId);
            }
          }
        }
      }
      
      apiUser.setRoles(defaultRoles);
      if (!extRoles.isEmpty()) {
        apiUser.setExternalRoles(extRoles);
      }
      
      return apiUser;
    }
    throw new IllegalArgumentException("Input must be a User instance");
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
   * Validates that this ApiUser has all required fields using pattern matching
   * 
   * @return true if all required fields are present and valid
   */
  public boolean isValid() {
    return this instanceof ApiUser(var id, var first, var last, var email, var src, var stat, var ro, var r, var _) 
        && id != null && !id.isBlank()
        && first != null && !first.isEmpty()
        && last != null && !last.isEmpty()
        && email != null && !email.isEmpty()
        && src != null && !src.isBlank()
        && stat != null
        && r != null && !r.isEmpty();
  }

  /**
   * Converts this ApiUser to a User object using pattern matching for more robust type handling
   * 
   * @return A new User instance populated with data from this ApiUser
   */
  User toUser() {
    // Create a new User instance and populate it with data from this ApiUser
    User user = new User();
    
    // Using pattern matching to ensure this ApiUser has all required fields
    if (this instanceof ApiUser(var id, var first, var last, var email, var src, var stat, var ro, var r, var extR)) {
      user.setUserId(id);
      user.setFirstName(first);
      user.setLastName(last);
      user.setEmailAddress(email);
      user.setSource(src);
      user.setStatus(stat.getStatus());
      user.setReadOnly(ro);
      user.setVersion(1);
      
      // Process roles using pattern matching for more robust handling
      Set<RoleIdentifier> roleIdentifiers = new HashSet<>();
      
      // Process regular roles
      if (r != null) {
        r.stream()
            .filter(Objects::nonNull)
            .map(role -> new RoleIdentifier(UserManager.DEFAULT_SOURCE, role))
            .forEach(roleIdentifiers::add);
      }
      
      // Process external roles if present
      if (extR != null) {
        extR.stream()
            .filter(Objects::nonNull)
            .map(role -> new RoleIdentifier(src, role))
            .forEach(roleIdentifiers::add);
      }
      
      user.setRoles(roleIdentifiers);
      return user;
    }
    
    // Fallback in case pattern matching fails (should never happen with valid data)
    user.setUserId(userId);
    user.setFirstName(firstName);
    user.setLastName(lastName);
    user.setEmailAddress(emailAddress);
    user.setSource(source);
    user.setStatus(status.getStatus());
    user.setReadOnly(readOnly);
    user.setVersion(1);

    Set<RoleIdentifier> roleIdentifiers = new HashSet<>();
    if (roles != null) {
      roles.stream()
          .filter(Objects::nonNull)
          .map(r -> new RoleIdentifier(UserManager.DEFAULT_SOURCE, r))
          .forEach(roleIdentifiers::add);
    }
    
    if (externalRoles != null) {
      externalRoles.stream()
          .filter(Objects::nonNull)
          .map(r -> new RoleIdentifier(source, r))
          .forEach(roleIdentifiers::add);
    }
    
    user.setRoles(roleIdentifiers);
    return user;
  }
}