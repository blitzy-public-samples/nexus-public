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
 * Implemented as a record for Java 21 compatibility.
 *
 * @since 3.0
 */
public record UserAccountXO(
    @NotEmpty
    String userId,
    
    @NotEmpty
    String firstName,
    
    @NotEmpty
    String lastName,
    
    @Email
    @NotEmpty
    String email,
    
    Boolean external
) {
  /**
   * Returns the user ID.
   * Provided for backward compatibility with code expecting JavaBean conventions.
   *
   * @return the user ID
   */
  public String getUserId() {
    return userId();
  }

  /**
   * Returns the first name.
   * Provided for backward compatibility with code expecting JavaBean conventions.
   *
   * @return the first name
   */
  public String getFirstName() {
    return firstName();
  }

  /**
   * Returns the last name.
   * Provided for backward compatibility with code expecting JavaBean conventions.
   *
   * @return the last name
   */
  public String getLastName() {
    return lastName();
  }

  /**
   * Returns the email address.
   * Provided for backward compatibility with code expecting JavaBean conventions.
   *
   * @return the email address
   */
  public String getEmail() {
    return email();
  }

  /**
   * Returns whether the user is external.
   * Provided for backward compatibility with code expecting JavaBean conventions.
   *
   * @return whether the user is external
   */
  public Boolean getExternal() {
    return external();
  }
}