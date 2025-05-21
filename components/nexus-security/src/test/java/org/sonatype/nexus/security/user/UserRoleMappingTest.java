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
package org.sonatype.nexus.security.user;

import java.util.HashSet;
import java.util.Set;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.CUserRoleMapping;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.internal.SecurityConfigurationManagerImpl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UserRoleMappingTest
    extends AbstractSecurityTest
{
  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return UserRoleMappingTestSecurity.securityModel();
  }

  public SecurityConfigurationManager getConfigManager() throws Exception {
    return this.lookup(SecurityConfigurationManagerImpl.class);
  }

  @Test
  public void testGetUser() throws Exception {
    SecurityConfigurationManager config = this.getConfigManager();

    CUser user = config.readUser("test-user");
    Assertions.assertEquals("test-user", user.getId());
    Assertions.assertEquals("test-user@example.org", user.getEmail());
    Assertions.assertEquals("Test", user.getFirstName());
    Assertions.assertEquals("User", user.getLastName());
    Assertions.assertEquals("b2a0e378437817cebdf753d7dff3dd75483af9e0", user.getPassword());
    Assertions.assertEquals("active", user.getStatus());

    CUserRoleMapping mapping = config.readUserRoleMapping("test-user", "default");

    Assertions.assertTrue(mapping.getRoles().contains("role1"));
    Assertions.assertTrue(mapping.getRoles().contains("role2"));
    Assertions.assertEquals(2, mapping.getRoles().size());
  }

  @Test
  public void testGetUserWithEmptyRole() throws Exception {
    SecurityConfigurationManager config = this.getConfigManager();

    CUser user = config.readUser("test-user-with-empty-role");
    Assertions.assertEquals("test-user-with-empty-role", user.getId());
    Assertions.assertEquals("test-user-with-empty-role@example.org", user.getEmail());
    Assertions.assertEquals("Test", user.getFirstName());
    Assertions.assertEquals("User With Empty Role", user.getLastName());
    Assertions.assertEquals("b2a0e378437817cebdf753d7dff3dd75483af9e0", user.getPassword());
    Assertions.assertEquals("active", user.getStatus());

    CUserRoleMapping mapping = config.readUserRoleMapping("test-user-with-empty-role", "default");

    Assertions.assertTrue(mapping.getRoles().contains("empty-role"));
    Assertions.assertTrue(mapping.getRoles().contains("role1"));
    Assertions.assertTrue(mapping.getRoles().contains("role2"));
    Assertions.assertEquals(3, mapping.getRoles().size());

    // try to update empty role
    config.updateUserRoleMapping(mapping);

    // make sure we still have the role mappings
    mapping = config.readUserRoleMapping("test-user-with-empty-role", "default");

    Assertions.assertTrue(mapping.getRoles().contains("empty-role"));
    Assertions.assertTrue(mapping.getRoles().contains("role1"));
    Assertions.assertTrue(mapping.getRoles().contains("role2"));
    Assertions.assertEquals(3, mapping.getRoles().size());
  }

  @Test
  public void testUpdateUsersRoles() throws Exception {
    SecurityConfigurationManager config = this.getConfigManager();

    // make sure we have exactly 5 user role mappings
    Assertions.assertEquals(5, config.listUserRoleMappings().size());

    // get the test-user and add a role
    CUser user = config.readUser("test-user");

    CUserRoleMapping roleMapping = config.readUserRoleMapping("test-user", "default");
    Set<String> roles = roleMapping.getRoles();
    roles.add("role3");

    // update the user
    config.updateUser(user, new HashSet<String>(roles));

    // make sure we have exactly 5 user role mappings
    Assertions.assertEquals(5, config.listUserRoleMappings().size());
  }
}
