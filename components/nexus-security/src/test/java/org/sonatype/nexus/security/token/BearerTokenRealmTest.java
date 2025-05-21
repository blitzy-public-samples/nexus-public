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
package org.sonatype.nexus.security.token;

import java.security.Principal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.security.UserPrincipalsHelper;
import org.sonatype.nexus.security.authc.NexusApiKeyAuthenticationToken;
import org.sonatype.nexus.security.authc.apikey.ApiKey;
import org.sonatype.nexus.security.authc.apikey.ApiKeyService;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserStatus;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.apache.shiro.subject.PrincipalCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.token.BearerTokenRealm.ANONYMOUS_USER;
import static org.sonatype.nexus.security.token.BearerTokenRealm.IS_TOKEN_AUTH_KEY;

@ExtendWith(MockitoExtension.class)
class BearerTokenRealmTest
{
  private static final String FORMAT = "format";

  @Mock
  private ApiKeyService keyStore;

  @Mock
  private UserPrincipalsHelper principalsHelper;

  @Mock
  private NexusApiKeyAuthenticationToken token;

  @Mock
  private AuthenticationToken unsupportedToken;

  @Mock
  private PrincipalCollection principalCollection;

  @Mock
  private Principal principal;

  @Mock
  private Provider<HttpServletRequest> requestProvider;

  @Mock
  private HttpServletRequest request;

  @Mock
  private CredentialsMatcher credentialsMatcher;

  BearerTokenRealm underTest;

  @BeforeEach
  void setup() throws Exception {
    when(token.getPrincipal()).thenReturn(FORMAT);
    when(unsupportedToken.getPrincipal()).thenReturn(FORMAT);
    when(principalCollection.getPrimaryPrincipal()).thenReturn(principal);
    ApiKey key = mock(ApiKey.class);
    when(key.getPrincipals()).thenReturn(principalCollection);
    when(keyStore.getApiKeyByToken(any(), any())).thenReturn(Optional.of(key));
    when(principalsHelper.getUserStatus(principalCollection)).thenReturn(UserStatus.active);
    when(credentialsMatcher.doCredentialsMatch(any(), any())).thenReturn(true);
    when(requestProvider.get()).thenReturn(request);
    underTest = new BearerTokenRealm(keyStore, principalsHelper, FORMAT) {};
    underTest.setRequestProvider(requestProvider);
    underTest.setCredentialsMatcher(credentialsMatcher);
  }

  @Test
  void should_support_when_correct_type_and_format() throws Exception {
    assertTrue(underTest.supports(token));
  }

  @Test
  void should_not_support_when_wrong_type() throws Exception {
    assertFalse(underTest.supports(unsupportedToken));
  }

  @Test
  void should_not_support_when_wrong_format() throws Exception {
    when(token.getPrincipal()).thenReturn("UnsupportedFormat");
    assertFalse(underTest.supports(token));
  }

  @Test
  void should_get_auth_info_when_active() throws Exception {
    AuthenticationInfo authenticationInfo = underTest.doGetAuthenticationInfo(token);
    assertNotNull(authenticationInfo.getPrincipals());
  }

  @Test
  void should_get_auth_info_when_anonymous_and_supported() throws Exception {
    when(principalCollection.getPrimaryPrincipal()).thenReturn(ANONYMOUS_USER);
    when(principalsHelper.getUserStatus(principalCollection)).thenReturn(UserStatus.disabled);
    underTest = new BearerTokenRealm(keyStore, principalsHelper, FORMAT)
    {
      @Override
      protected boolean isAnonymousSupported() {
        return true;
      }
    };
    AuthenticationInfo authenticationInfo = underTest.doGetAuthenticationInfo(token);
    assertNotNull(authenticationInfo.getPrincipals());
  }

  @Test
  void should_return_null_when_anonymous_but_not_supported() throws Exception {
    when(principalsHelper.getUserStatus(principalCollection)).thenReturn(UserStatus.disabled);
    when(principalCollection.getPrimaryPrincipal()).thenReturn(ANONYMOUS_USER);
    assertNull(underTest.doGetAuthenticationInfo(token));
  }

  @Test
  void should_delete_keys_on_user_not_found_exception() throws Exception {
    when(principalsHelper.getUserStatus(principalCollection)).thenThrow(new UserNotFoundException("userid"));
    assertNull(underTest.doGetAuthenticationInfo(token));
    verify(keyStore).deleteApiKeys(principalCollection);
  }

  @Test
  void should_return_null_auth_info_when_principals_null() throws Exception {
    when(keyStore.getApiKeyByToken(any(), any())).thenReturn(Optional.empty());
    assertNull(underTest.doGetAuthenticationInfo(token));
  }

  @Test
  void should_return_null_auth_info_when_user_not_active() throws Exception {
    when(principalsHelper.getUserStatus(principalCollection)).thenReturn(UserStatus.disabled);
    assertNull(underTest.doGetAuthenticationInfo(token));
  }

  @Test
  void should_return_primary_principal_when_get_cache_key() throws Exception {
    assertEquals(principal, underTest.getAuthenticationCacheKey(token));
  }

  @Test
  void should_return_null_when_token_null() throws Exception {
    assertNull(underTest.getAuthenticationCacheKey(null));
  }

  @Test
  void should_return_null_when_principals_null() throws Exception {
    when(keyStore.getApiKeyByToken(any(), any())).thenReturn(Optional.empty());
    assertNull(underTest.getAuthenticationCacheKey(token));
  }

  @Test
  void should_not_support_anonymous_access_by_default() throws Exception {
    assertFalse(underTest.isAnonymousSupported());
  }

  @Test
  void should_enable_caching() {
    assertTrue(underTest.isAuthenticationCachingEnabled());
  }

  @Test
  void should_verify_assert_credentials_match_sets_attributes() {
    underTest.assertCredentialsMatch(token, underTest.doGetAuthenticationInfo(token));
    verify(request).setAttribute(IS_TOKEN_AUTH_KEY, Boolean.TRUE);
    verify(token).setPrincipal(principal);
  }
  
  @Test
  void should_authenticate_in_virtual_thread() throws Exception {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    Thread virtualThread = Thread.ofVirtual().name("virtual-auth-test").start(() -> {
      try {
        AuthenticationInfo authInfo = underTest.doGetAuthenticationInfo(token);
        future.complete(authInfo != null && authInfo.getPrincipals() != null);
      } 
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    virtualThread.join();
    assertTrue(future.get());
  }
  
  @Test
  void should_verify_credentials_in_virtual_thread() throws Exception {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    Thread virtualThread = Thread.ofVirtual().name("virtual-cred-test").start(() -> {
      try {
        AuthenticationInfo authInfo = underTest.doGetAuthenticationInfo(token);
        underTest.assertCredentialsMatch(token, authInfo);
        future.complete(true);
      } 
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    virtualThread.join();
    assertTrue(future.get());
    verify(request).setAttribute(IS_TOKEN_AUTH_KEY, Boolean.TRUE);
    verify(token).setPrincipal(principal);
  }
}