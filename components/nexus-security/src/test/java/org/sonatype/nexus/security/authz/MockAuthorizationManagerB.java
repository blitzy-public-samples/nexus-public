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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.sonatype.nexus.security.internal.DefaultSecuritySystemTest;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.role.NoSuchRoleException;
import org.sonatype.nexus.security.role.Role;

/**
 * Mock authorization manager implementation for testing.
 * <p>
 * This implementation is thread-safe for use in concurrent tests including virtual thread tests.
 * It is also compatible with JUnit 5 test expectations.
 *
 * @see DefaultSecuritySystemTest
 */
public class MockAuthorizationManagerB
    extends AbstractReadOnlyAuthorizationManager
{
  // Thread-safe cache of roles
  private final Map<String, Role> roleCache = new ConcurrentHashMap<>();
  
  // Thread-safe set of privileges
  private final Set<Privilege> privileges = ConcurrentHashMap.newKeySet();
  
  // Volatile flag to ensure visibility across threads
  private volatile boolean initialized = false;

  /**
   * Initialize the mock data if not already done.
   */
  private synchronized void ensureInitialized() {
    if (!initialized) {
      // Create and cache roles
      Role role1 = new Role();
      role1.setSource(this.getSource());
      role1.setName("Role 1");
      role1.setRoleId("test-role1");
      role1.addPrivilege("from-role1:read");
      role1.addPrivilege("from-role1:delete");
      roleCache.put(role1.getRoleId(), role1);

      Role role2 = new Role();
      role2.setSource(this.getSource());
      role2.setName("Role 2");
      role2.setRoleId("test-role2");
      role2.addPrivilege("from-role2:read");
      role2.addPrivilege("from-role2:delete");
      roleCache.put(role2.getRoleId(), role2);
      
      initialized = true;
    }
  }

  @Override
  public String getSource() {
    return "sourceB";
  }

  @Override
  public Set<Role> listRoles() {
    ensureInitialized();
    return Collections.unmodifiableSet(Set.copyOf(roleCache.values()));
  }

  @Override
  public Privilege getPrivilege(String privilegeId) throws NoSuchPrivilegeException {
    // In a real implementation, we would look up the privilege
    // For testing purposes, we throw the expected exception for non-existent privileges
    throw new NoSuchPrivilegeException(privilegeId);
  }

  @Override
  public Privilege getPrivilegeByName(final String privilegeName) throws NoSuchPrivilegeException {
    // Consistent with getPrivilege implementation
    throw new NoSuchPrivilegeException(privilegeName);
  }

  @Override
  public List<Privilege> getPrivileges(final Set<String> privilegeIds) {
    // Return empty list instead of null for JUnit 5 compatibility
    return Collections.emptyList();
  }

  @Override
  public Role getRole(String roleId) throws NoSuchRoleException {
    ensureInitialized();
    Role role = roleCache.get(roleId);
    if (role == null) {
      throw new NoSuchRoleException(roleId);
    }
    return role;
  }

  @Override
  public Set<Privilege> listPrivileges() {
    // Return empty set instead of null for JUnit 5 compatibility
    return Collections.emptySet();
  }
}