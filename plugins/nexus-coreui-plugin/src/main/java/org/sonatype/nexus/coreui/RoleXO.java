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
import javax.validation.constraints.NotEmpty;

import org.sonatype.nexus.security.privilege.PrivilegesExist;
import org.sonatype.nexus.security.role.RoleNotContainSelf;
import org.sonatype.nexus.security.role.RolesExist;
import org.sonatype.nexus.security.role.UniqueRoleId;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

/**
 * Role exchange object.
 *
 * @since 3.0
 */
@RoleNotContainSelf(id = "getId", roleIds = "getRoles")
public record RoleXO(
    @NotEmpty
    @UniqueRoleId(groups = Create.class)
    String id,

    @NotEmpty(groups = Update.class)
    String version,

    String source,

    @NotEmpty
    String name,

    String description,

    Boolean readOnly,

    @PrivilegesExist(groups = {Create.class, Update.class})
    Set<String> privileges,

    @RolesExist(groups = {Create.class, Update.class})
    Set<String> roles
) {
  /**
   * Custom toString implementation to maintain compatibility with the original class.
   */
  @Override
  public String toString() {
    return "RoleXO{" +
        "id='" + id + '\'' +
        ", version='" + version + '\'' +
        ", source='" + source + '\'' +
        ", name='" + name + '\'' +
        ", description='" + description + '\'' +
        ", readOnly=" + readOnly +
        ", privileges=" + privileges +
        ", roles=" + roles +
        '}';
  }
}