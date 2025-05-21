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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.realm.RealmManager;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UserManagementTest
    extends AbstractSecurityTest
{
  private SecuritySystem securitySystem;

  @BeforeEach
  protected void setUp() throws Exception {
    super.setUp();

    securitySystem = getSecuritySystem();

    RealmManager realmManager = lookup(RealmManager.class);
    realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmA", "MockRealmB"));
  }

  @Override
  protected void customizeModules(List<Module> modules) {
    super.customizeModules(modules);
    modules.add(new AbstractModule()
    {
      @Override
      protected void configure() {
        bind(UserManager.class)
            .annotatedWith(Names.named("Mock"))
            .to(MockUserManager.class)
            .in(Singleton.class);
      }
    });
  }

  @Test
  public void testAllUsers() throws Exception {
    Set<User> users = securitySystem.listUsers();
    Assertions.assertFalse(users.isEmpty());

    // put users in map for easy search
    Map<String, User> userMap = this.getMapFromSet(users);

    // now check all of the users
    Assertions.assertTrue(userMap.containsKey("jcoder"));
    Assertions.assertTrue(userMap.containsKey("cdugas"));
    Assertions.assertTrue(userMap.containsKey("pperalez"));
    Assertions.assertTrue(userMap.containsKey("dknudsen"));
    Assertions.assertTrue(userMap.containsKey("anonymous-user"));

    Assertions.assertTrue(userMap.containsKey("bburton"));
    Assertions.assertTrue(userMap.containsKey("jblevins"));
    Assertions.assertTrue(userMap.containsKey("ksimmons"));
    Assertions.assertTrue(userMap.containsKey("fdahmen"));
    Assertions.assertTrue(userMap.containsKey("jcodar"));

    // FIXME: This is a pretty fragile assertion
    Assertions.assertEquals(15, users.size());

    // we just need to check to make sure there are 2 jcoders with the correct source
    this.verify2Jcoders(users);
  }

  @Test
  public void testSearchWithCriteria() throws Exception {
    UserSearchCriteria criteria = new UserSearchCriteria();

    criteria.setUserId("pperalez");
    Set<User> users = securitySystem.searchUsers(criteria);
    Assertions.assertEquals(1, users.size());
    Assertions.assertEquals("pperalez", users.iterator().next().getUserId());

    criteria.setUserId("ppera");
    users = securitySystem.searchUsers(criteria);
    Assertions.assertEquals(1, users.size());
    Assertions.assertEquals("pperalez", users.iterator().next().getUserId());

    criteria.setUserId("ppera");
    criteria.setSource("MockUserManagerB");
    users = securitySystem.searchUsers(criteria);
    Assertions.assertEquals(0, users.size());

    criteria.setUserId("ksim");
    users = securitySystem.searchUsers(criteria);
    Assertions.assertEquals(1, users.size());
    Assertions.assertEquals("ksimmons", users.iterator().next().getUserId());

    criteria.setUserId("jcod");
    criteria.setSource(null);
    users = securitySystem.searchUsers(criteria);
    Assertions.assertEquals(3, users.size());

    // put users in map for easy search
    Map<String, User> userMap = this.getMapFromSet(users);

    Assertions.assertTrue(userMap.containsKey("jcodar"));

    // we just need to check to make sure there are 2 jcoders with the correct source (the counts are already
    // checked above)
    this.verify2Jcoders(users);

  }

  private Map<String, User> getMapFromSet(Set<User> users) {
    Map<String, User> userMap = new HashMap<String, User>();
    for (User user : users) {
      userMap.put(user.getUserId(), user);
    }
    return userMap;
  }

  private void verify2Jcoders(Set<User> users) {
    Map<String, User> jcoders = new HashMap<String, User>();
    for (User user : users) {
      if (user.getUserId().equals("jcoder")) {
        jcoders.put(user.getSource(), user);
      }
    }
    Assertions.assertEquals(2, jcoders.size());
    Assertions.assertTrue(jcoders.containsKey("MockUserManagerA"));
    Assertions.assertTrue(jcoders.containsKey("MockUserManagerB"));
  }
}