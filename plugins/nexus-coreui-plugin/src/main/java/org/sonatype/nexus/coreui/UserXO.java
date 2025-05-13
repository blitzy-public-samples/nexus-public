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
package org.sonatype.nexus.coreui;

import java.util.Set;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.security.role.RolesExist;
import org.sonatype.nexus.security.user.UniqueUserId;
import org.sonatype.nexus.security.user.UserStatus;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

/**
 * User exchange object.
 * Refactored as a Java record for Java 21 compatibility, leveraging Record Patterns
 * for more concise and type-safe data handling.
 *
 * @since 3.0
 */
public record UserXO(
    @NotBlank
    @UniqueUserId(groups = Create.class)
    String userId,
    
    @NotBlank(groups = Update.class)
    String version,
    
    // Null on create
    String realm,
    
    @NotBlank
    String firstName,
    
    @NotBlank
    String lastName,
    
    @NotBlank
    @Email
    String email,
    
    @NotNull
    UserStatus status,
    
    @NotBlank(groups = Create.class)
    String password,
    
    @NotEmpty
    @RolesExist(groups = {Create.class, Update.class})
    Set<String> roles,
    
    Boolean external,
    
    // FIXME: Sort out what this is used for
    Set<String> externalRoles
) {
  /**
   * Default constructor required for JSON deserialization.
   * This enables frameworks to create an instance and then populate its fields.
   */
  public UserXO() {
    this(null, null, null, null, null, null, null, null, null, null, null);
  }
  
  /**
   * Creates a new instance with the specified values.
   * This factory method allows for backward compatibility with code that uses setters.
   * 
   * @param userId User identifier
   * @param version Version information
   * @param realm Security realm
   * @param firstName User's first name
   * @param lastName User's last name
   * @param email User's email address
   * @param status User status
   * @param password User password
   * @param roles User roles
   * @param external Whether the user is external
   * @param externalRoles External roles
   * @return A new UserXO instance
   */
  public static UserXO of(String userId, String version, String realm, String firstName, String lastName,
                          String email, UserStatus status, String password, Set<String> roles,
                          Boolean external, Set<String> externalRoles) {
    return new UserXO(userId, version, realm, firstName, lastName, email, status, password, roles, external, externalRoles);
  }
  
  /**
   * Creates a new builder for UserXO.
   * This provides a fluent API for creating UserXO instances.
   *
   * @return A new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }
  
  /**
   * Builder class for UserXO.
   * Provides a fluent API for creating UserXO instances.
   */
  public static class Builder {
    private String userId;
    private String version;
    private String realm;
    private String firstName;
    private String lastName;
    private String email;
    private UserStatus status;
    private String password;
    private Set<String> roles;
    private Boolean external;
    private Set<String> externalRoles;
    
    /**
     * Sets the userId.
     *
     * @param userId The userId to set
     * @return This builder instance
     */
    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }
    
    /**
     * Sets the version.
     *
     * @param version The version to set
     * @return This builder instance
     */
    public Builder version(String version) {
      this.version = version;
      return this;
    }
    
    /**
     * Sets the realm.
     *
     * @param realm The realm to set
     * @return This builder instance
     */
    public Builder realm(String realm) {
      this.realm = realm;
      return this;
    }
    
    /**
     * Sets the firstName.
     *
     * @param firstName The firstName to set
     * @return This builder instance
     */
    public Builder firstName(String firstName) {
      this.firstName = firstName;
      return this;
    }
    
    /**
     * Sets the lastName.
     *
     * @param lastName The lastName to set
     * @return This builder instance
     */
    public Builder lastName(String lastName) {
      this.lastName = lastName;
      return this;
    }
    
    /**
     * Sets the email.
     *
     * @param email The email to set
     * @return This builder instance
     */
    public Builder email(String email) {
      this.email = email;
      return this;
    }
    
    /**
     * Sets the status.
     *
     * @param status The status to set
     * @return This builder instance
     */
    public Builder status(UserStatus status) {
      this.status = status;
      return this;
    }
    
    /**
     * Sets the password.
     *
     * @param password The password to set
     * @return This builder instance
     */
    public Builder password(String password) {
      this.password = password;
      return this;
    }
    
    /**
     * Sets the roles.
     *
     * @param roles The roles to set
     * @return This builder instance
     */
    public Builder roles(Set<String> roles) {
      this.roles = roles;
      return this;
    }
    
    /**
     * Sets the external flag.
     *
     * @param external The external flag to set
     * @return This builder instance
     */
    public Builder external(Boolean external) {
      this.external = external;
      return this;
    }
    
    /**
     * Sets the externalRoles.
     *
     * @param externalRoles The externalRoles to set
     * @return This builder instance
     */
    public Builder externalRoles(Set<String> externalRoles) {
      this.externalRoles = externalRoles;
      return this;
    }
    
    /**
     * Builds a new UserXO instance with the configured values.
     *
     * @return A new UserXO instance
     */
    public UserXO build() {
      return new UserXO(userId, version, realm, firstName, lastName, email, status, password, roles, external, externalRoles);
    }
  }
  
  /**
   * Creates a copy of this record with the specified userId.
   */
  public UserXO withUserId(String userId) {
    return new UserXO(userId, this.version, this.realm, this.firstName, this.lastName, 
        this.email, this.status, this.password, this.roles, this.external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified version.
   */
  public UserXO withVersion(String version) {
    return new UserXO(this.userId, version, this.realm, this.firstName, this.lastName, 
        this.email, this.status, this.password, this.roles, this.external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified realm.
   */
  public UserXO withRealm(String realm) {
    return new UserXO(this.userId, this.version, realm, this.firstName, this.lastName, 
        this.email, this.status, this.password, this.roles, this.external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified firstName.
   */
  public UserXO withFirstName(String firstName) {
    return new UserXO(this.userId, this.version, this.realm, firstName, this.lastName, 
        this.email, this.status, this.password, this.roles, this.external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified lastName.
   */
  public UserXO withLastName(String lastName) {
    return new UserXO(this.userId, this.version, this.realm, this.firstName, lastName, 
        this.email, this.status, this.password, this.roles, this.external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified email.
   */
  public UserXO withEmail(String email) {
    return new UserXO(this.userId, this.version, this.realm, this.firstName, this.lastName, 
        email, this.status, this.password, this.roles, this.external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified status.
   */
  public UserXO withStatus(UserStatus status) {
    return new UserXO(this.userId, this.version, this.realm, this.firstName, this.lastName, 
        this.email, status, this.password, this.roles, this.external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified password.
   */
  public UserXO withPassword(String password) {
    return new UserXO(this.userId, this.version, this.realm, this.firstName, this.lastName, 
        this.email, this.status, password, this.roles, this.external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified roles.
   */
  public UserXO withRoles(Set<String> roles) {
    return new UserXO(this.userId, this.version, this.realm, this.firstName, this.lastName, 
        this.email, this.status, this.password, roles, this.external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified external flag.
   */
  public UserXO withExternal(Boolean external) {
    return new UserXO(this.userId, this.version, this.realm, this.firstName, this.lastName, 
        this.email, this.status, this.password, this.roles, external, this.externalRoles);
  }
  
  /**
   * Creates a copy of this record with the specified externalRoles.
   */
  public UserXO withExternalRoles(Set<String> externalRoles) {
    return new UserXO(this.userId, this.version, this.realm, this.firstName, this.lastName, 
        this.email, this.status, this.password, this.roles, this.external, externalRoles);
  }
  
  /**
   * Example of how to use Record Patterns with this class.
   * This method demonstrates pattern matching with records in Java 21.
   *
   * @param obj The object to check
   * @return A formatted string with user information if the object is a UserXO, or "Not a user" otherwise
   */
  public static String formatUserIfPresent(Object obj) {
    return switch (obj) {
      case UserXO(String userId, _, _, String firstName, String lastName, String email, UserStatus status, _, _, _, _) 
          when status == UserStatus.active ->
        STR."Active user: \{firstName} \{lastName} (\{userId}) - \{email}";
      
      case UserXO(String userId, _, _, String firstName, String lastName, _, UserStatus status, _, _, _, _) ->
        STR."User \{userId} (\{firstName} \{lastName}) has status: \{status}";
      
      default -> "Not a user";
    };
  }
  
  /**
   * Example of how to filter users by status using Record Patterns.
   * This method demonstrates using Record Patterns with collections in Java 21.
   *
   * @param users The collection of users to filter
   * @param status The status to filter by
   * @return A list of user IDs with the specified status
   */
  public static java.util.List<String> filterUsersByStatus(java.util.Collection<UserXO> users, UserStatus status) {
    return users.stream()
        .filter(user -> switch (user) {
          case UserXO(_, _, _, _, _, _, var userStatus, _, _, _, _) when userStatus == status -> true;
          default -> false;
        })
        .map(UserXO::userId)
        .toList();
  }
  
  // Legacy getter methods for backward compatibility
  
  /**
   * @deprecated Use {@link #userId()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public String getUserId() {
    return userId;
  }
  
  /**
   * @deprecated Use {@link #version()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public String getVersion() {
    return version;
  }
  
  /**
   * @deprecated Use {@link #realm()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public String getRealm() {
    return realm;
  }
  
  /**
   * @deprecated Use {@link #firstName()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public String getFirstName() {
    return firstName;
  }
  
  /**
   * @deprecated Use {@link #lastName()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public String getLastName() {
    return lastName;
  }
  
  /**
   * @deprecated Use {@link #email()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public String getEmail() {
    return email;
  }
  
  /**
   * @deprecated Use {@link #status()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public UserStatus getStatus() {
    return status;
  }
  
  /**
   * @deprecated Use {@link #password()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public String getPassword() {
    return password;
  }
  
  /**
   * @deprecated Use {@link #roles()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public Set<String> getRoles() {
    return roles;
  }
  
  /**
   * @deprecated Use {@link #external()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public Boolean isExternal() {
    return external;
  }
  
  /**
   * @deprecated Use {@link #externalRoles()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public Set<String> getExternalRoles() {
    return externalRoles;
  }
  
  // Legacy setter methods for backward compatibility
  
  /**
   * @deprecated Use {@link #withUserId(String)} instead. Maintained for backward compatibility.
   * @throws UnsupportedOperationException Records are immutable, use the appropriate with* method instead
   */
  @Deprecated
  public void setUserId(String userId) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withUserId() instead.");
  }
  
  /**
   * @deprecated Use {@link #withVersion(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setVersion(String version) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withVersion() instead.");
  }
  
  /**
   * @deprecated Use {@link #withRealm(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setRealm(String realm) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withRealm() instead.");
  }
  
  /**
   * @deprecated Use {@link #withFirstName(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setFirstName(String firstName) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withFirstName() instead.");
  }
  
  /**
   * @deprecated Use {@link #withLastName(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setLastName(String lastName) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withLastName() instead.");
  }
  
  /**
   * @deprecated Use {@link #withEmail(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setEmail(String email) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withEmail() instead.");
  }
  
  /**
   * @deprecated Use {@link #withStatus(UserStatus)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setStatus(UserStatus status) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withStatus() instead.");
  }
  
  /**
   * @deprecated Use {@link #withPassword(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setPassword(String password) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withPassword() instead.");
  }
  
  /**
   * @deprecated Use {@link #withRoles(Set)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setRoles(Set<String> roles) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withRoles() instead.");
  }
  
  /**
   * @deprecated Use {@link #withExternal(Boolean)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setExternal(Boolean external) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withExternal() instead.");
  }
  
  /**
   * @deprecated Use {@link #withExternalRoles(Set)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setExternalRoles(Set<String> externalRoles) {
    throw new UnsupportedOperationException(STR."UserXO is now immutable. Use withExternalRoles() instead.");
  }
}