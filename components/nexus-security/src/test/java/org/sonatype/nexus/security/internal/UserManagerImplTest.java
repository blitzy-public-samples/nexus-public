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
package org.sonatype.nexus.security.internal;

import java.util.Set;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.CUserRoleMapping;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.config.memory.MemoryCUserRoleMapping;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserSearchCriteria;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.shiro.authc.credential.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.user.UserManager.DEFAULT_SOURCE;

@ExtendWith(MockitoExtension.class)
public class UserManagerImplTest
    extends TestSupport
{
  @Mock
  EventManager eventManager;

  @Mock
  SecurityConfigurationManager securityConfigurationManager;

  @Mock
  SecuritySystem securitySystem;

  @Mock
  PasswordService passwordService;

  @Mock
  PasswordValidator passwordValidator;

  private UserManagerImpl underTest;

  @BeforeEach
  void setup() {
    underTest = new UserManagerImpl(eventManager, securityConfigurationManager, securitySystem, passwordService,
        passwordValidator);
  }

  @Test
  void testChangePassword() throws Exception {
    CUser user = new MemoryCUser();
    user.setStatus(CUser.STATUS_CHANGE_PASSWORD);
    user.setId("test");

    when(securityConfigurationManager.readUser("test")).thenReturn(user);

    underTest.changePassword("test", "newpass");

    assertThat(user.getStatus(), is(CUser.STATUS_ACTIVE));

    verify(passwordValidator).validate("newpass");
  }

  @Test
  void searchUsersDefaultSource() {
    CUser user1 = new MemoryCUser();
    user1.setStatus(CUser.STATUS_CHANGE_PASSWORD);
    user1.setId("test1");

    CUser user2 = new MemoryCUser();
    user2.setStatus(CUser.STATUS_CHANGE_PASSWORD);
    user2.setId("test2");

    when(securityConfigurationManager.listUsers()).thenReturn(ImmutableList.of(user1, user2));

    UserSearchCriteria crit = new UserSearchCriteria();
    crit.setSource(DEFAULT_SOURCE);

    Set<User> users = underTest.searchUsers(crit);
    assertThat(users.size(), is(2));

    verify(securityConfigurationManager, never()).listUserRoleMappings();
  }

  @Test
  void searchUsersNoSource() throws Exception {
    CUser user1 = new MemoryCUser();
    user1.setStatus(CUser.STATUS_CHANGE_PASSWORD);
    user1.setId("test1");

    CUser user2 = new MemoryCUser();
    user2.setStatus(CUser.STATUS_CHANGE_PASSWORD);
    user2.setId("test2");

    CUser user3 = new MemoryCUser();
    user3.setStatus(CUser.STATUS_CHANGE_PASSWORD);
    user3.setId("test3");

    CUserRoleMapping roleMapping1 = new MemoryCUserRoleMapping();
    roleMapping1.setUserId("test1");
    roleMapping1.setSource(DEFAULT_SOURCE);
    roleMapping1.setRoles(ImmutableSet.of("admin"));

    CUserRoleMapping roleMapping2 = new MemoryCUserRoleMapping();
    roleMapping2.setUserId("test2");
    roleMapping2.setSource(DEFAULT_SOURCE);
    roleMapping2.setRoles(ImmutableSet.of("admin"));

    CUserRoleMapping roleMapping3 = new MemoryCUserRoleMapping();
    roleMapping3.setUserId("test3");
    roleMapping3.setSource("EXTERNAL_SOURCE");
    roleMapping3.setRoles(ImmutableSet.of("admin"));

    when(securityConfigurationManager.listUsers()).thenReturn(ImmutableList.of(user1, user2));
    when(securityConfigurationManager.listUserRoleMappings()).thenReturn(
        ImmutableList.of(roleMapping1, roleMapping2, roleMapping3));
    when(securityConfigurationManager.readUser("test3")).thenReturn(user3);

    UserSearchCriteria crit = new UserSearchCriteria();

    Set<User> users = underTest.searchUsers(crit);
    assertThat(users.size(), is(3));
  }

  @Test
  void testPatternMatchingWithUserRoles() {
    // Test using pattern matching for instanceof with Java 21
    CUserRoleMapping roleMapping = new MemoryCUserRoleMapping();
    roleMapping.setUserId("test1");
    roleMapping.setSource(DEFAULT_SOURCE);
    roleMapping.setRoles(ImmutableSet.of("admin", "developer"));

    // Using pattern matching for instanceof
    if (roleMapping instanceof CUserRoleMapping mapping) {
      assertThat(mapping.getUserId(), is("test1"));
      assertThat(mapping.getSource(), is(DEFAULT_SOURCE));
      assertThat(mapping.getRoles().contains("admin"), is(true));
    }
  }

  @Test
  void testVirtualThreadsForConcurrentUserOperations() throws Exception {
    // Create users for testing
    CUser user1 = new MemoryCUser();
    user1.setId("vt-user1");
    user1.setStatus(CUser.STATUS_ACTIVE);

    CUser user2 = new MemoryCUser();
    user2.setId("vt-user2");
    user2.setStatus(CUser.STATUS_ACTIVE);

    when(securityConfigurationManager.readUser("vt-user1")).thenReturn(user1);
    when(securityConfigurationManager.readUser("vt-user2")).thenReturn(user2);

    // Using virtual threads to perform concurrent password validations
    Thread vt1 = Thread.ofVirtual().name("password-validator-1").start(() -> {
      try {
        underTest.changePassword("vt-user1", "securePass123!");
      }
      catch (Exception e) {
        // Handle exception
      }
    });

    Thread vt2 = Thread.ofVirtual().name("password-validator-2").start(() -> {
      try {
        underTest.changePassword("vt-user2", "anotherSecurePass456!");
      }
      catch (Exception e) {
        // Handle exception
      }
    });

    // Wait for both threads to complete
    vt1.join();
    vt2.join();

    // Verify password validations were performed
    verify(passwordValidator).validate("securePass123!");
    verify(passwordValidator).validate("anotherSecurePass456!");
  }
}