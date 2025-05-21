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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.role.NoSuchRoleException;
import org.sonatype.nexus.security.role.Role;

/**
 * Thread-safe mock implementation of {@link AuthorizationManager} for use in tests.
 * This implementation is designed to be compatible with JUnit 5 tests and safe for
 * concurrent access in virtual thread environments.
 */
public class MockAuthorizationManager
    extends AbstractReadOnlyAuthorizationManager
{
  /**
   * Immutable set of predefined roles for consistent and thread-safe access.
   */
  private static final Set<Role> ROLES;
  
  /**
   * Immutable map of role IDs to roles for efficient lookups.
   */
  private static final Map<String, Role> ROLE_MAP;
  
  static {
    // Initialize roles
    Map<String, Role> roleMap = new HashMap<>();
    
    Role role1 = new Role("mockrole1", "MockRole1", "Mock Role1", "Mock", true, null, null);
    Role role2 = new Role("mockrole2", "MockRole2", "Mock Role2", "Mock", true, null, null);
    Role role3 = new Role("mockrole3", "MockRole3", "Mock Role3", "Mock", true, null, null);
    
    roleMap.put(role1.getRoleId(), role1);
    roleMap.put(role2.getRoleId(), role2);
    roleMap.put(role3.getRoleId(), role3);
    
    ROLE_MAP = Collections.unmodifiableMap(roleMap);
    ROLES = Collections.unmodifiableSet(ROLE_MAP.values().stream().collect(java.util.stream.Collectors.toSet()));
  }
  
  @Override
  public String getSource() {
    return "Mock";
  }

  /**
   * Returns an immutable set of predefined roles.
   * This method is thread-safe and can be safely called from multiple threads,
   * including virtual threads.
   */
  @Override
  public Set<Role> listRoles() {
    return ROLES;
  }

  /**
   * Returns a role by ID using an efficient map lookup.
   * This method is thread-safe and can be safely called from multiple threads,
   * including virtual threads.
   *
   * @param roleId the ID of the role to retrieve
   * @return the role with the specified ID
   * @throws NoSuchRoleException if no role with the specified ID exists
   */
  @Override
  public Role getRole(String roleId) throws NoSuchRoleException {
    Role role = ROLE_MAP.get(roleId);
    if (role == null) {
      throw new NoSuchRoleException(roleId);
    }
    return role;
  }

  /**
   * Returns an empty set of privileges.
   * This method is thread-safe and can be safely called from multiple threads,
   * including virtual threads.
   */
  @Override
  public Set<Privilege> listPrivileges() {
    return Collections.emptySet();
  }

  /**
   * Always throws NoSuchPrivilegeException as this mock doesn't provide any privileges.
   * This method is thread-safe and can be safely called from multiple threads,
   * including virtual threads.
   *
   * @param privilegeId the ID of the privilege to retrieve
   * @throws NoSuchPrivilegeException always thrown as no privileges exist
   */
  @Override
  public Privilege getPrivilege(String privilegeId) throws NoSuchPrivilegeException {
    throw new NoSuchPrivilegeException(privilegeId);
  }

  /**
   * Always throws NoSuchPrivilegeException as this mock doesn't provide any privileges.
   * This method is thread-safe and can be safely called from multiple threads,
   * including virtual threads.
   *
   * @param privilegeName the name of the privilege to retrieve
   * @throws NoSuchPrivilegeException always thrown as no privileges exist
   */
  @Override
  public Privilege getPrivilegeByName(final String privilegeName) throws NoSuchPrivilegeException {
    throw new NoSuchPrivilegeException(privilegeName);
  }

  /**
   * Returns an empty list of privileges.
   * This method is thread-safe and can be safely called from multiple threads,
   * including virtual threads.
   *
   * @param privilegeIds the IDs of the privileges to retrieve
   * @return an empty list of privileges
   */
  @Override
  public List<Privilege> getPrivileges(final Set<String> privilegeIds) {
    return Collections.emptyList();
  }
}