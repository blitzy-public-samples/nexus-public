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
import javax.validation.constraints.NotBlank;

import org.sonatype.nexus.security.realm.RealmExists;
import org.sonatype.nexus.security.role.RolesExist;
import org.sonatype.nexus.security.user.UserExists;
import org.sonatype.nexus.validation.group.Create;

/**
 * User role mappings exchange object.
 *
 * @since 3.0
 */
public record UserRoleMappingsXO(
    @NotBlank
    @UserExists(groups = Create.class)
    String userId,
    
    @NotBlank
    @RealmExists(groups = Create.class)
    String realm,
    
    @RolesExist
    Set<String> roles
) {
  /**
   * Constructor with validation for Java 21 Record Pattern support.
   * 
   * @param userId the user identifier
   * @param realm the security realm
   * @param roles the set of roles
   */
  public UserRoleMappingsXO {
    // Validation happens automatically through annotations
    // This constructor can be used for additional validation if needed in the future
  }
}