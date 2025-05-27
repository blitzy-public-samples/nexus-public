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
package org.sonatype.nexus.security.authc;

import java.util.Collections;
import java.util.Set;

import org.sonatype.nexus.security.realm.MockRealmA;
import org.sonatype.nexus.security.realm.MockRealmB;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.SimpleAccount;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class FirstSuccessfulModularRealAuthenticatorTest
{
  private FirstSuccessfulModularRealmAuthenticator firstSuccessfulModularRealmAuthenticator;

  @Mock
  private Subject subject;

  @Mock
  private PrincipalCollection principals;

  @BeforeEach
  public void init() {
    firstSuccessfulModularRealmAuthenticator = new FirstSuccessfulModularRealmAuthenticator();
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Collections.emptySet());
    SecurityUtils.setSecurityManager(mock(org.apache.shiro.mgt.SecurityManager.class));
    when(SecurityUtils.getSubject()).thenReturn(subject);
  }

  @Test
  public void testMultiRealmInvalidCredentials() {
    UsernamePasswordToken usernamePasswordToken = new UsernamePasswordToken("username", "password");

    Realm realmOne = mock(Realm.class);
    Realm realmTwo = mock(Realm.class);

    when(realmOne.supports(usernamePasswordToken)).thenReturn(true);
    when(realmTwo.supports(usernamePasswordToken)).thenReturn(true);

    when(realmOne.getAuthenticationInfo(usernamePasswordToken)).thenThrow(new IncorrectCredentialsException());
    when(realmTwo.getAuthenticationInfo(usernamePasswordToken)).thenThrow(new IncorrectCredentialsException());

    NexusAuthenticationException exception = assertThrows(NexusAuthenticationException.class, () -> {
      firstSuccessfulModularRealmAuthenticator
          .doMultiRealmAuthentication(Lists.newArrayList(realmOne, realmTwo), usernamePasswordToken);
    });
    
    assertThat(exception.getAuthenticationFailureReasons(), containsInAnyOrder(
        AuthenticationFailureReason.INCORRECT_CREDENTIALS));
  }

  @Test
  public void testMultiRealmMultipleFailures() {
    UsernamePasswordToken usernamePasswordToken = new UsernamePasswordToken("username", "password");

    Realm realmOne = mock(Realm.class);
    Realm realmTwo = mock(Realm.class);

    when(realmOne.supports(usernamePasswordToken)).thenReturn(true);
    when(realmTwo.supports(usernamePasswordToken)).thenReturn(true);

    when(realmOne.getAuthenticationInfo(usernamePasswordToken)).thenThrow(new IncorrectCredentialsException());
    when(realmTwo.getAuthenticationInfo(usernamePasswordToken)).thenThrow(new UnknownAccountException());

    NexusAuthenticationException exception = assertThrows(NexusAuthenticationException.class, () -> {
      firstSuccessfulModularRealmAuthenticator
          .doMultiRealmAuthentication(Lists.newArrayList(realmOne, realmTwo), usernamePasswordToken);
    });
    
    assertThat(exception.getAuthenticationFailureReasons(), containsInAnyOrder(
        AuthenticationFailureReason.INCORRECT_CREDENTIALS,
        AuthenticationFailureReason.USER_NOT_FOUND));
  }

  @Test
  public void testSingleRealmFailureIsStillSuccessful() {
    UsernamePasswordToken usernamePasswordToken = new UsernamePasswordToken("username", "password");

    Realm realmOne = mock(Realm.class);
    Realm realmTwo = mock(Realm.class);

    when(realmOne.supports(usernamePasswordToken)).thenReturn(true);
    when(realmTwo.supports(usernamePasswordToken)).thenReturn(true);

    SimpleAccount simpleAccount = new SimpleAccount(ImmutableList.of("simple"), usernamePasswordToken, "realmName");
    when(realmOne.getAuthenticationInfo(usernamePasswordToken)).thenThrow(new IncorrectCredentialsException());
    when(realmTwo.getAuthenticationInfo(usernamePasswordToken)).thenReturn(simpleAccount);

    when(principals.getRealmNames()).thenReturn(Collections.singleton("realmName"));
    when(subject.isAuthenticated()).thenReturn(true);

    // No exception should be thrown
    firstSuccessfulModularRealmAuthenticator
        .doMultiRealmAuthentication(Lists.newArrayList(realmOne, realmTwo), usernamePasswordToken);
  }

  @Test
  public void testSameUserIdInDifferentRealmsShouldReturnsCorrectAuthenticationInfo() {
    // two same user ids but with different passwords and realms
    UsernamePasswordToken usernameRealmA = new UsernamePasswordToken("username", "passwordA");
    UsernamePasswordToken usernameRealmB = new UsernamePasswordToken("username", "passwordB");

    Realm realmOne = mock(MockRealmA.class);
    Realm realmTwo = mock(MockRealmB.class);

    when(realmOne.supports(usernameRealmA)).thenReturn(true);
    when(realmOne.supports(usernameRealmB)).thenReturn(true);

    when(realmTwo.supports(usernameRealmA)).thenReturn(true);
    when(realmTwo.supports(usernameRealmB)).thenReturn(true);

    SimpleAccount simpleAccountA = new SimpleAccount(ImmutableList.of("simple"), usernameRealmA, "MockRealmA");
    SimpleAccount simpleAccountB = new SimpleAccount(ImmutableList.of("simple"), usernameRealmB, "MockRealmB");

    when(realmOne.getAuthenticationInfo(usernameRealmA)).thenReturn(simpleAccountA);
    when(realmOne.getAuthenticationInfo(usernameRealmB)).thenReturn(simpleAccountB);

    when(realmTwo.getAuthenticationInfo(usernameRealmA)).thenReturn(simpleAccountA);
    when(realmTwo.getAuthenticationInfo(usernameRealmB)).thenReturn(simpleAccountB);

    // the first principal which is returned 'MockRealmA' is incorrect for the user 'usernameRealmB'
    when(principals.getRealmNames())
        .thenReturn(Collections.singleton("MockRealmA"))
        .thenReturn(Collections.singleton("MockRealmB"));
    when(subject.isAuthenticated()).thenReturn(true);

    AuthenticationInfo authenticationInfo = firstSuccessfulModularRealmAuthenticator
        .doMultiRealmAuthentication(Lists.newArrayList(realmOne, realmTwo), usernameRealmB);
    Set<String> realmNames = authenticationInfo.getPrincipals().getRealmNames();
    assertThat(realmNames, contains("MockRealmB"));
  }

  @Test
  public void testAuthenticatorInVirtualThread() {
    // This test verifies that the authenticator works correctly in a Virtual Thread context
    Thread.startVirtualThread(() -> {
      UsernamePasswordToken usernamePasswordToken = new UsernamePasswordToken("username", "password");

      Realm realmOne = mock(Realm.class);
      Realm realmTwo = mock(Realm.class);

      when(realmOne.supports(usernamePasswordToken)).thenReturn(true);
      when(realmTwo.supports(usernamePasswordToken)).thenReturn(true);

      SimpleAccount simpleAccount = new SimpleAccount(ImmutableList.of("simple"), usernamePasswordToken, "realmName");
      when(realmOne.getAuthenticationInfo(usernamePasswordToken)).thenThrow(new IncorrectCredentialsException());
      when(realmTwo.getAuthenticationInfo(usernamePasswordToken)).thenReturn(simpleAccount);

      when(principals.getRealmNames()).thenReturn(Collections.singleton("realmName"));
      when(subject.isAuthenticated()).thenReturn(true);

      // No exception should be thrown
      firstSuccessfulModularRealmAuthenticator
          .doMultiRealmAuthentication(Lists.newArrayList(realmOne, realmTwo), usernamePasswordToken);
    }).join(); // Wait for the virtual thread to complete
  }
}