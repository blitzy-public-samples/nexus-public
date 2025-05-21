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
package org.sonatype.nexus.security.authz;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.role.NoSuchRoleException;
import org.sonatype.nexus.security.role.Role;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link AuthorizationManager}.
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationManagerTest
    extends AbstractSecurityTest
{
  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return AuthorizationManagerTestSecurity.securityModel();
  }

  AuthorizationManager getAuthorizationManager() throws Exception {
    return this.lookup(AuthorizationManager.class);
  }

  SecurityConfigurationManager getConfigurationManager() throws Exception {
    return lookup(SecurityConfigurationManager.class);
  }

  // ROLES

  @Test
  void listRoles() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();
    Set<Role> roles = authzManager.listRoles();

    Map<String, Role> roleMap = this.toRoleMap(roles);
    Assertions.assertTrue(roleMap.containsKey("role1"));
    Assertions.assertTrue(roleMap.containsKey("role2"));
    Assertions.assertTrue(roleMap.containsKey("role3"));
    Assertions.assertEquals(3, roles.size());

    Role role3 = roleMap.get("role3");

    Assertions.assertEquals("role3", role3.getRoleId());
    Assertions.assertEquals("RoleThree", role3.getName());
    Assertions.assertEquals("Role Three", role3.getDescription());
    Assertions.assertTrue(role3.getPrivileges().contains("1"));
    Assertions.assertTrue(role3.getPrivileges().contains("4"));
    Assertions.assertEquals(2, role3.getPrivileges().size());
  }

  @Test
  void getRole() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();

    Role role1 = authzManager.getRole("role1");

    Assertions.assertEquals("role1", role1.getRoleId());
    Assertions.assertEquals("RoleOne", role1.getName());
    Assertions.assertEquals("Role One", role1.getDescription());
    Assertions.assertTrue(role1.getPrivileges().contains("1"));
    Assertions.assertTrue(role1.getPrivileges().contains("2"));
    Assertions.assertEquals(2, role1.getPrivileges().size());
  }

  @Test
  void addRole() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();

    Role role = new Role();
    role.setRoleId("new-role");
    role.setName("new-name");
    role.setDescription("new-description");
    role.addPrivilege("2");
    role.addPrivilege("4");

    authzManager.addRole(role);

    CRole secRole = this.getConfigurationManager().readRole(role.getRoleId());

    Assertions.assertEquals(role.getRoleId(), secRole.getId());
    Assertions.assertEquals(role.getName(), secRole.getName());
    Assertions.assertEquals(role.getDescription(), secRole.getDescription());
    Assertions.assertTrue(secRole.getPrivileges().contains("2"));
    Assertions.assertTrue(secRole.getPrivileges().contains("4"));
    Assertions.assertEquals(2, secRole.getPrivileges().size());
  }

  @Test
  void updateRole() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();

    Role role2 = authzManager.getRole("role2");
    role2.setDescription("new description");
    role2.setName("new name");

    Set<String> permissions = new HashSet<String>();
    permissions.add("2");
    role2.setPrivileges(permissions);

    authzManager.updateRole(role2);

    CRole secRole = this.getConfigurationManager().readRole(role2.getRoleId());

    Assertions.assertEquals(role2.getRoleId(), secRole.getId());
    Assertions.assertEquals(role2.getName(), secRole.getName());
    Assertions.assertEquals(role2.getDescription(), secRole.getDescription());
    Assertions.assertTrue(secRole.getPrivileges().contains("2"));
    Assertions.assertEquals(1, secRole.getPrivileges().size());
  }

  @Test
  void deleteRole() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();
    
    // Test deleting an invalid role name
    NoSuchRoleException exception = Assertions.assertThrows(NoSuchRoleException.class, () -> {
      authzManager.deleteRole("INVALID-ROLENAME");
    }, "Expected NoSuchRoleException");

    // This one will work
    authzManager.deleteRole("role2");

    // This one should fail
    Assertions.assertThrows(NoSuchRoleException.class, () -> {
      authzManager.deleteRole("role2");
    }, "Expected NoSuchRoleException");

    Assertions.assertThrows(NoSuchRoleException.class, () -> {
      authzManager.getRole("role2");
    }, "Expected NoSuchRoleException");

    Assertions.assertThrows(NoSuchRoleException.class, () -> {
      this.getConfigurationManager().readRole("role2");
    }, "Expected NoSuchRoleException");
  }

  private Map<String, Role> toRoleMap(Set<Role> roles) {
    Map<String, Role> roleMap = new HashMap<String, Role>();

    for (Role role : roles) {
      roleMap.put(role.getRoleId(), role);
    }

    return roleMap;
  }

  // Privileges

  @Test
  void listPrivileges() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();
    Set<Privilege> privileges = authzManager.listPrivileges();

    Map<String, Privilege> roleMap = this.toPrivilegeMap(privileges);
    Assertions.assertTrue(roleMap.containsKey("1"));
    Assertions.assertTrue(roleMap.containsKey("2"));
    Assertions.assertTrue(roleMap.containsKey("3"));
    Assertions.assertTrue(roleMap.containsKey("4"));
    Assertions.assertEquals(4, privileges.size());

    Privilege priv3 = roleMap.get("3");

    Assertions.assertEquals("3", priv3.getId());
    Assertions.assertEquals("3-name", priv3.getName());
    Assertions.assertEquals("Privilege Three", priv3.getDescription());
    Assertions.assertEquals("method", priv3.getType());
    Assertions.assertEquals("read", priv3.getPrivilegeProperty("method"));
    Assertions.assertEquals("/some/path/", priv3.getPrivilegeProperty("permission"));
  }

  @Test
  void getPrivilege() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();

    Privilege priv3 = authzManager.getPrivilege("3");

    Assertions.assertEquals("3", priv3.getId());
    Assertions.assertEquals("3-name", priv3.getName());
    Assertions.assertEquals("Privilege Three", priv3.getDescription());
    Assertions.assertEquals("method", priv3.getType());
    Assertions.assertEquals("read", priv3.getPrivilegeProperty("method"));
    Assertions.assertEquals("/some/path/", priv3.getPrivilegeProperty("permission"));
  }

  @Test
  void getPrivilegeByName() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();

    Privilege priv3 = authzManager.getPrivilegeByName("3-name");

    Assertions.assertEquals("3", priv3.getId());
    Assertions.assertEquals("3-name", priv3.getName());
    Assertions.assertEquals("Privilege Three", priv3.getDescription());
    Assertions.assertEquals("method", priv3.getType());
    Assertions.assertEquals("read", priv3.getPrivilegeProperty("method"));
    Assertions.assertEquals("/some/path/", priv3.getPrivilegeProperty("permission"));
  }

  @Test
  void addPrivilege() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();

    Privilege privilege = new Privilege();
    privilege.addProperty("foo1", "bar2");
    privilege.addProperty("bar1", "foo2");
    privilege.setId("new-priv");
    privilege.setName("new-name");
    privilege.setDescription("new-description");
    privilege.setReadOnly(true);
    privilege.setType("TEST");

    authzManager.addPrivilege(privilege);

    CPrivilege secPriv = this.getConfigurationManager().readPrivilege(privilege.getId());

    Assertions.assertEquals(privilege.getId(), secPriv.getId());
    Assertions.assertEquals(privilege.getName(), secPriv.getName());
    Assertions.assertEquals(privilege.getDescription(), secPriv.getDescription());
    Assertions.assertEquals(privilege.getType(), secPriv.getType());
    Assertions.assertEquals(privilege.getProperties().size(), secPriv.getProperties().size());

    Assertions.assertEquals("bar2", secPriv.getProperty("foo1"));
    Assertions.assertEquals("foo2", secPriv.getProperty("bar1"));
  }

  @Test
  void updatePrivilege() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();

    Privilege priv2 = authzManager.getPrivilege("2");
    priv2.setDescription("new description");

    authzManager.updatePrivilege(priv2);

    CPrivilege secPriv = this.getConfigurationManager().readPrivilege(priv2.getId());

    Assertions.assertEquals(priv2.getId(), secPriv.getId());
    Assertions.assertEquals(priv2.getName(), secPriv.getName());
    Assertions.assertEquals(priv2.getDescription(), secPriv.getDescription());
    Assertions.assertEquals(priv2.getType(), secPriv.getType());

    Assertions.assertEquals("read", secPriv.getProperty("method"));
    Assertions.assertEquals("/some/path/", secPriv.getProperty("permission"));
    Assertions.assertEquals(2, secPriv.getProperties().size());
  }

  @Test
  void updatePrivilegeByName() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();

    Privilege privilege = authzManager.getPrivilegeByName("3-name");
    privilege.setDescription("updated");

    authzManager.updatePrivilegeByName(privilege);

    CPrivilege persistenPrivilege = this.getConfigurationManager().readPrivilegeByName(privilege.getName());

    Assertions.assertEquals(privilege.getId(), persistenPrivilege.getId());
    Assertions.assertEquals(privilege.getName(), persistenPrivilege.getName());
    Assertions.assertEquals(privilege.getDescription(), persistenPrivilege.getDescription());
    Assertions.assertEquals(privilege.getType(), persistenPrivilege.getType());

    Assertions.assertEquals("read", persistenPrivilege.getProperty("method"));
    Assertions.assertEquals("/some/path/", persistenPrivilege.getProperty("permission"));
    Assertions.assertEquals(2, persistenPrivilege.getProperties().size());
  }

  @Test
  void deletePrivilegeUser() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();
    
    // Test deleting an invalid privilege name
    Assertions.assertThrows(NoSuchPrivilegeException.class, () -> {
      authzManager.deletePrivilege("INVALID-PRIVILEGENAME");
    }, "Expected NoSuchPrivilegeException");

    // This one will work
    authzManager.deletePrivilege("2");

    // This one should fail
    Assertions.assertThrows(NoSuchPrivilegeException.class, () -> {
      authzManager.deletePrivilege("2");
    }, "Expected NoSuchPrivilegeException");

    Assertions.assertThrows(NoSuchPrivilegeException.class, () -> {
      authzManager.getPrivilege("2");
    }, "Expected NoSuchPrivilegeException");

    Assertions.assertThrows(NoSuchPrivilegeException.class, () -> {
      this.getConfigurationManager().readPrivilege("2");
    }, "Expected NoSuchPrivilegeException");
  }

  @Test
  void deletePrivilegeByName() throws Exception {
    AuthorizationManager authzManager = this.getAuthorizationManager();

    authzManager.deletePrivilegeByName("3-name");

    Assertions.assertThrows(NoSuchPrivilegeException.class, () -> {
      this.getConfigurationManager().readPrivilegeByName("3-name");
    }, "Expected NoSuchPrivilegeException");
  }

  private Map<String, Privilege> toPrivilegeMap(Set<Privilege> privileges) {
    Map<String, Privilege> roleMap = new HashMap<String, Privilege>();

    for (Privilege privilege : privileges) {
      roleMap.put(privilege.getId(), privilege);
    }

    return roleMap;
  }
}