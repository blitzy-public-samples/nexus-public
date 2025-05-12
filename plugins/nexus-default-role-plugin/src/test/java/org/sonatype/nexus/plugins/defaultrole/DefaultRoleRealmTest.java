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
package org.sonatype.nexus.plugins.defaultrole;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.anonymous.AnonymousPrincipalCollection;

import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.singleton;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for {@link DefaultRoleRealm} using JUnit Jupiter and Java 21 features.
 */
@DisplayName("DefaultRoleRealm authorization tests")
public class DefaultRoleRealmTest
    extends TestSupport
{
  private DefaultRoleRealm underTest;

  @BeforeEach
  void setup() {
    underTest = new DefaultRoleRealm();
  }

  @Test
  @DisplayName("When role is not configured, authorization info should be null")
  void doGetAuthorizationInfoWhenNotConfigured() {
    underTest.setRole(null);

    AuthorizationInfo authorizationInfo = underTest.doGetAuthorizationInfo(principals("test"));
    assertThat(authorizationInfo, nullValue());
  }

  @Test
  @DisplayName("Authenticated user should receive the default role")
  void doGetAuthorizationInfoForAuthenticatedUser() {
    underTest.setRole("default-role");

    AuthorizationInfo authorizationInfo = underTest.doGetAuthorizationInfo(principals("test"));
    assertThat(authorizationInfo, notNullValue());
    assertThat(authorizationInfo.getRoles(), is(singleton("default-role")));
  }

  @Test
  @DisplayName("Anonymous user should not receive the default role")
  void doGetAuthorizationInfoForAnonymousUser() {
    underTest.setRole("default-role");

    AuthorizationInfo authorizationInfo = underTest.doGetAuthorizationInfo(principals("anonymous"));
    assertThat(authorizationInfo, nullValue());
  }

  /**
   * Creates a principal collection for the given user ID.
   * Uses pattern matching to determine the type of principal collection to create.
   *
   * @param userId the user ID
   * @return a principal collection for the user
   */
  private static PrincipalCollection principals(final String userId) {
    return switch (userId) {
      case "anonymous" -> new AnonymousPrincipalCollection(userId, "realm");
      default -> new SimplePrincipalCollection(userId, "realm");
    };
  }