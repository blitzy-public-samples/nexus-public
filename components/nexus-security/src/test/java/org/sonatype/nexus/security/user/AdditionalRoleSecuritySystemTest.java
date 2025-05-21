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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;

public class AdditionalRoleSecuritySystemTest
    extends AbstractSecurityTest
{
  private SecuritySystem securitySystem;

  @Override
  public void setUp() throws Exception {
    super.setUp();

    securitySystem = getSecuritySystem();
  }

  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return AdditionalRoleSecuritySystemTestSecurity.securityModel();
  }

  private Set<String> getRoles() throws Exception {
    AuthorizationManager authzManager = lookup(AuthorizationManager.class);

    Set<String> roles = new HashSet<>();
    for (Role role : authzManager.listRoles()) {
      roles.add(role.getRoleId());
    }

    return roles;
  }

  @Test
  public void testListUsers() throws Exception {
    UserSearchCriteria criteria = new UserSearchCriteria(null, null, "MockUserManagerA");
    Set<User> users = securitySystem.searchUsers(criteria);

    Map<String, User> userMap = toUserMap(users);

    User user = userMap.get("jcoder");
    Assertions.assertNotNull(user);

    // A,B,C,1
    Set<String> roleIds = toRoleIdSet(user.getRoles());
    assertThat(roleIds, hasItems("RoleA", "RoleB", "RoleC", "Role1"));
    assertThat(roleIds, hasSize(4));

    user = userMap.get("dknudsen");
    Assertions.assertNotNull(user);
    assertThat(user.getRoles(), hasSize(1));

    // Role2
    roleIds = toRoleIdSet(user.getRoles());
    assertThat(roleIds, hasItems("Role2"));

    user = userMap.get("cdugas");
    Assertions.assertNotNull(user);
    assertThat(user.getRoles(), hasSize(3));

    // A,B,1
    roleIds = toRoleIdSet(user.getRoles());
    assertThat(roleIds, hasItems("RoleA", "RoleB", "Role1"));

    user = userMap.get("pperalez");
    Assertions.assertNotNull(user);
    assertThat(user.getRoles(), empty());
  }

  @Disabled("TESTING, issue here with more usermanager bound than test requires")
  public void testSearchEffectiveTrue() throws Exception {
    UserSearchCriteria criteria = new UserSearchCriteria();
    criteria.setOneOfRoleIds(getRoles());

    criteria.setUserId("pperalez");
    User user = searchForSingleUser(criteria, "pperalez", null);
    Assertions.assertNull(user);

    criteria.setUserId("jcoder");
    user = searchForSingleUser(criteria, "jcoder", null);
    Assertions.assertNotNull(user);
    Assertions.assertEquals(4, user.getRoles().size(), "Roles: " + toRoleIdSet(user.getRoles()));

    // A,B,C,1
    Set<String> roleIds = toRoleIdSet(user.getRoles());
    assertThat(roleIds, hasItems("RoleA", "RoleB", "RoleC", "Role1"));

    criteria.setUserId("dknudsen");
    user = searchForSingleUser(criteria, "dknudsen", null);
    Assertions.assertNotNull(user);
    assertThat(user.getRoles(), hasSize(1));

    // Role2
    roleIds = toRoleIdSet(user.getRoles());
    assertThat(roleIds, hasItems("Role2"));

    criteria.setUserId("cdugas");
    user = searchForSingleUser(criteria, "cdugas", null);
    Assertions.assertNotNull(user);
    assertThat(user.getRoles(), hasSize(3));

    // A,B,1
    roleIds = toRoleIdSet(user.getRoles());
    assertThat(roleIds, hasItems("RoleA", "RoleB", "Role1"));
  }

  @Test
  public void testSearchEffectiveFalse() throws Exception {
    UserSearchCriteria criteria = new UserSearchCriteria();

    criteria.setUserId("pperalez");
    User user = searchForSingleUser(criteria, "pperalez", "MockUserManagerA");
    Assertions.assertNotNull(user);

    criteria.setUserId("jcoder");
    user = searchForSingleUser(criteria, "jcoder", "MockUserManagerA");
    Assertions.assertNotNull(user);

    // A,B,C,1
    Set<String> roleIds = toRoleIdSet(user.getRoles());
    assertThat(roleIds, hasItems("RoleA", "RoleB", "RoleC", "Role1"));
    assertThat(user.getRoles(), hasSize(4));

    criteria.setUserId("dknudsen");
    user = searchForSingleUser(criteria, "dknudsen", "MockUserManagerA");
    Assertions.assertNotNull(user);
    assertThat(user.getRoles(), hasSize(1));

    // Role2
    roleIds = toRoleIdSet(user.getRoles());
    assertThat(roleIds, hasItems("Role2"));

    criteria.setUserId("cdugas");
    user = searchForSingleUser(criteria, "cdugas", "MockUserManagerA");
    Assertions.assertNotNull(user);
    assertThat(user.getRoles(), hasSize(3));

    // A,B,1
    roleIds = toRoleIdSet(user.getRoles());
    assertThat(roleIds, hasItems("RoleA", "RoleB", "Role1"));
  }

  @Disabled("TESTING, issue here with more usermanager bound than test requires")
  public void testNestedRoles() throws Exception {
    UserSearchCriteria criteria = new UserSearchCriteria();
    criteria.getOneOfRoleIds().add("Role1");

    Set<User> result = securitySystem.searchUsers(criteria);

    Map<String, User> userMap = toUserMap(result);
    Assertions.assertTrue(userMap.containsKey("admin"), "User not found in: " + userMap);
    Assertions.assertTrue(userMap.containsKey("test-user"), "User not found in: " + userMap);
    Assertions.assertTrue(userMap.containsKey("jcoder"), "User not found in: " + userMap);
    Assertions.assertTrue(userMap.containsKey("cdugas"), "User not found in: " + userMap);
    // Assertions.assertTrue(userMap.containsKey("other-user"), "User not found in: " + userMap);
    // other user is only defined in the mapping, simulates a user that was deleted

    assertThat(result, hasSize(4));
  }

  private User searchForSingleUser(
      final UserSearchCriteria criteria,
      final String userId,
      final String source) throws Exception
  {
    criteria.setSource(source);
    Set<User> users = securitySystem.searchUsers(criteria);

    System.out.println("Found users:");
    for (User user : users) {
      System.out.format("%s, source=%s%n", user, user.getSource());
    }

    Map<String, User> userMap = toUserMap(users);
    Assertions.assertTrue(users.size() <= 1, "More then 1 User was returned: " + userMap.keySet());

    return userMap.get(userId);
  }

  private Map<String, User> toUserMap(final Set<User> users) {
    HashMap<String, User> map = new HashMap<>();
    for (User plexusUser : users) {
      map.put(plexusUser.getUserId(), plexusUser);
    }
    return map;
  }

  private Set<String> toRoleIdSet(final Set<RoleIdentifier> roles) {
    Set<String> roleIds = new HashSet<>();
    for (RoleIdentifier role : roles) {
      roleIds.add(role.getRoleId());
    }
    return roleIds;
  }
}
