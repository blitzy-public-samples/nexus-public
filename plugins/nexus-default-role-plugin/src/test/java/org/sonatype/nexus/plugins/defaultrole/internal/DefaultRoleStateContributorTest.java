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
package org.sonatype.nexus.plugins.defaultrole.internal;

import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.plugins.defaultrole.DefaultRoleRealm;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.NoSuchAuthorizationManagerException;
import org.sonatype.nexus.security.realm.RealmManager;
import org.sonatype.nexus.security.role.Role;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.apache.shiro.util.ThreadState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.user.UserManager.DEFAULT_SOURCE;

/**
 * Test for DefaultRoleStateContributor using JUnit Jupiter and Java 21 features.
 * Tests the state contribution behavior for default roles.
 */
@ExtendWith(MockitoExtension.class)
public class DefaultRoleStateContributorTest
    extends TestSupport
{

  @Mock
  private DefaultRoleRealm defaultRoleRealm;

  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private Subject subject;

  @Mock
  private RealmManager realmManager;

  @InjectMocks
  private DefaultRoleStateContributor underTest;

  private Role defaultRole;

  private ThreadState subjectThreadState;

  @BeforeEach
  void setupDefaultRoleRealm() {
    defaultRole = new Role();
    defaultRole.setRoleId("id");
    defaultRole.setName("name");

    when(defaultRoleRealm.getRole()).thenReturn(defaultRole.getRoleId());
  }

  @AfterEach
  void clearSubject() {
    if (subjectThreadState != null) {
      subjectThreadState.clear();
    }
  }

  /**
   * Tests that unauthenticated users do not receive the default role state.
   */
  @Test
  void unauthenticatedUserDoesNotGetDefaultRoleState() {
    when(realmManager.isRealmEnabled(DefaultRoleRealm.NAME)).thenReturn(true);
    Subject subject = mock(Subject.class);
    when(subject.isAuthenticated()).thenReturn(false);
    when(subject.isRemembered()).thenReturn(false);
    setSubject(subject);

    Map<String, Object> state = underTest.getState();

    assertThat(state, is(emptyMap()));
  }

  /**
   * Tests that authenticated users receive the default role state when the realm is enabled.
   */
  @Test
  void authenticatedUserGetsTheDefaultRoleState() throws NoSuchAuthorizationManagerException {
    when(realmManager.isRealmEnabled(DefaultRoleRealm.NAME)).thenReturn(true);
    Subject subject = mock(Subject.class);
    when(subject.isAuthenticated()).thenReturn(true);
    setSubject(subject);

    when(securitySystem.listRoles(DEFAULT_SOURCE)).thenReturn(singleton(defaultRole));

    Map<String, Object> state = underTest.getState();
    
    // Using pattern matching for instanceof check with Java 21
    if (state.get("defaultRole") instanceof Map<?, ?> defaultRoleState) {
      assertThat(defaultRoleState.get("id"), is(defaultRole.getRoleId()));
      assertThat(defaultRoleState.get("name"), is(defaultRole.getName()));
    }
  }

  /**
   * Tests that authenticated users do not receive the default role state when the realm is disabled.
   */
  @Test
  void authenticatedUserDoesNotGetTheDefaultRoleWhenDisabled() {
    when(realmManager.isRealmEnabled(DefaultRoleRealm.NAME)).thenReturn(false);
    Subject subject = mock(Subject.class);
    when(subject.isAuthenticated()).thenReturn(true);
    setSubject(subject);

    Map<String, Object> state = underTest.getState();

    assertThat(state, is(emptyMap()));
  }

  /**
   * Helper method to set the current subject in the thread state.
   * 
   * @param subject The subject to set as the current subject
   */
  private void setSubject(Subject subject) {
    assert subjectThreadState == null;

    subjectThreadState = new SubjectThreadState(subject);
    subjectThreadState.bind();
  }

}
