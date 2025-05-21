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
package org.sonatype.nexus.security.config;

import org.sonatype.nexus.security.config.memory.MemoryCPrivilege.MemoryCPrivilegeBuilder;
import org.sonatype.nexus.security.config.memory.MemoryCRole;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.config.memory.MemoryCUserRoleMapping;

/**
 * Test security configuration utility class for DefaultSecurityConfigurationCleaner tests.
 * 
 * @since 3.0
 */
public final class DefaultSecurityConfigurationCleanerTestSecurity
{
  // Prevent instantiation of utility class
  private DefaultSecurityConfigurationCleanerTestSecurity() {
  }

  /**
   * Creates a test security model with predefined users, roles, privileges, and mappings.
   *
   * @return a populated {@link MemorySecurityConfiguration} for testing
   */
  public static MemorySecurityConfiguration securityModel() {
    // Common configuration for all privileges
    final String methodType = "method";
    final String readMethod = "read";
    final String permissionPath = "/some/path/";
    final String defaultSource = "default";
    final String activeStatus = "active";
    final String emailAddress = "email";
    
    // Create a standard privilege builder with common properties
    var createPrivilege = (String id) -> new MemoryCPrivilegeBuilder(id)
        .type(methodType)
        .name(id)
        .description("")
        .property("method", readMethod)
        .property("permission", permissionPath);
    
    // Create a standard user with common properties
    var createUser = (String id, String firstName, String lastName) -> new MemoryCUser()
        .withId(id)
        .withPassword(id) // Using ID as password for simplicity
        .withFirstName(firstName)
        .withLastName(lastName)
        .withStatus(activeStatus)
        .withEmail(emailAddress);
    
    // Create a standard user-role mapping
    var createUserRoleMapping = (String userId, String... roles) -> new MemoryCUserRoleMapping()
        .withUserId(userId)
        .withSource(defaultSource)
        .withRoles(roles);
    
    // Create a standard role
    var createRole = (String id, String[] privileges, String[] roles) -> new MemoryCRole()
        .withId(id)
        .withName(id)
        .withDescription(id)
        .withPrivileges(privileges)
        .withRoles(roles);
    
    return new MemorySecurityConfiguration()
        .withUsers(
            createUser("user1", "first1", "last1"),
            createUser("user2", "first2", "last2"),
            createUser("user3", "first3", "last3"),
            createUser("user4", "first4", "last4"),
            createUser("user5", "first5", "last5"))
        .withUserRoleMappings(
            createUserRoleMapping("user1", "role1"),
            createUserRoleMapping("user2", "role1", "role2"),
            createUserRoleMapping("user3", "role1", "role2", "role3"),
            createUserRoleMapping("user4", "role1", "role2", "role3", "role4"),
            createUserRoleMapping("user5", "role1", "role2", "role3", "role4", "role5"))
        .withPrivileges(
            createPrivilege("priv1").build(),
            createPrivilege("priv2").build(),
            createPrivilege("priv3").build(),
            createPrivilege("priv4").build(),
            createPrivilege("priv5").build())
        .withRoles(
            createRole("role1", new String[]{"priv1"}, new String[]{"role2", "role3", "role4", "role5"}),
            createRole("role2", new String[]{"priv1", "priv2"}, new String[]{"role3", "role4", "role5"}),
            createRole("role3", new String[]{"priv1", "priv2", "priv3"}, new String[]{"role4", "role5"}),
            createRole("role4", new String[]{"priv1", "priv2", "priv3", "priv4"}, new String[]{"role5"}),
            createRole("role5", new String[]{"priv1", "priv2", "priv3", "priv4", "priv5"}, new String[]{}));
  }
}