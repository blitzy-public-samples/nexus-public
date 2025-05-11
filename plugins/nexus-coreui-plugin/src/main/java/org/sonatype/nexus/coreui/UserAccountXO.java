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

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;

/**
 * User account exchange object.
 * Refactored as a Java record for Java 21 compatibility.
 *
 * @since 3.0
 */
public record UserAccountXO(
    @NotEmpty String userId,
    @NotEmpty String firstName,
    @NotEmpty String lastName,
    @Email @NotEmpty String email,
    Boolean external
) {
  /**
   * Default constructor required for JSON deserialization.
   */
  public UserAccountXO() {
    this(null, null, null, null, null);
  }
  
  /**
   * Creates a new instance with the specified values.
   * This constructor allows for backward compatibility with code that uses setters.
   */
  public static UserAccountXO of(String userId, String firstName, String lastName, String email, Boolean external) {
    return new UserAccountXO(userId, firstName, lastName, email, external);
  }
  
  /**
   * Creates a copy of this record with the specified userId.
   */
  public UserAccountXO withUserId(String userId) {
    return new UserAccountXO(userId, this.firstName, this.lastName, this.email, this.external);
  }
  
  /**
   * Creates a copy of this record with the specified firstName.
   */
  public UserAccountXO withFirstName(String firstName) {
    return new UserAccountXO(this.userId, firstName, this.lastName, this.email, this.external);
  }
  
  /**
   * Creates a copy of this record with the specified lastName.
   */
  public UserAccountXO withLastName(String lastName) {
    return new UserAccountXO(this.userId, this.firstName, lastName, this.email, this.external);
  }
  
  /**
   * Creates a copy of this record with the specified email.
   */
  public UserAccountXO withEmail(String email) {
    return new UserAccountXO(this.userId, this.firstName, this.lastName, email, this.external);
  }
  
  /**
   * Creates a copy of this record with the specified external flag.
   */
  public UserAccountXO withExternal(Boolean external) {
    return new UserAccountXO(this.userId, this.firstName, this.lastName, this.email, external);
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
   * @deprecated Use {@link #external()} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public Boolean getExternal() {
    return external;
  }
  
  // Legacy setter methods for backward compatibility
  
  /**
   * @deprecated Use {@link #withUserId(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setUserId(String userId) {
    throw new UnsupportedOperationException("UserAccountXO is now immutable. Use withUserId() instead.");
  }
  
  /**
   * @deprecated Use {@link #withFirstName(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setFirstName(String firstName) {
    throw new UnsupportedOperationException("UserAccountXO is now immutable. Use withFirstName() instead.");
  }
  
  /**
   * @deprecated Use {@link #withLastName(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setLastName(String lastName) {
    throw new UnsupportedOperationException("UserAccountXO is now immutable. Use withLastName() instead.");
  }
  
  /**
   * @deprecated Use {@link #withEmail(String)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setEmail(String email) {
    throw new UnsupportedOperationException("UserAccountXO is now immutable. Use withEmail() instead.");
  }
  
  /**
   * @deprecated Use {@link #withExternal(Boolean)} instead. Maintained for backward compatibility.
   */
  @Deprecated
  public void setExternal(Boolean external) {
    throw new UnsupportedOperationException("UserAccountXO is now immutable. Use withExternal() instead.");
  }
}