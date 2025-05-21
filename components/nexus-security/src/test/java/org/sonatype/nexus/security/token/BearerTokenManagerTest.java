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

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.security.authc.apikey.ApiKey;
import org.sonatype.nexus.security.authc.apikey.ApiKeyService;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BearerTokenManagerTest
{
  private static final String FORMAT = "format";

  private static final String TOKEN = "token";

  @Mock
  private SecurityHelper securityHelper;

  @Mock
  private ApiKeyService apiKeyService;

  @Mock
  private SecurityManager securityManager;

  @Mock
  private AuthenticationInfo authenticationInfo;

  @Mock
  private PrincipalCollection principalCollection;

  @Mock
  private Subject subject;

  private BearerTokenManager underTest;

  @BeforeEach
  void setup() {
    when(securityHelper.getSecurityManager()).thenReturn(securityManager);
    when(securityManager.authenticate(any())).thenReturn(authenticationInfo);
    when(authenticationInfo.getPrincipals()).thenReturn(principalCollection);
    when(securityHelper.subject()).thenReturn(subject);
    when(subject.getPrincipals()).thenReturn(principalCollection);
    underTest = new BearerTokenManager(apiKeyService, securityHelper, FORMAT) { };
  }

  @Test
  void failFastWhenApiKeyStoreIsNull() {
    assertThrows(NullPointerException.class, () -> {
      new BearerTokenManager(null, securityHelper, FORMAT) { };
    });
  }

  @Test
  void failFastWhenSecurityHelperIsNull() {
    assertThrows(NullPointerException.class, () -> {
      new BearerTokenManager(apiKeyService, null, FORMAT) { };
    });
  }

  @Test
  void failFastWhenFormatIsNull() {
    assertThrows(NullPointerException.class, () -> {
      new BearerTokenManager(apiKeyService, securityHelper, null) { };
    });
  }

  @Test
  void failFastWhenPrincipalsNull() {
    assertThrows(NullPointerException.class, () -> {
      underTest.createToken(null);
    });
  }

  @Test
  void createNewKeyWhenOneDoesNotAlreadyExist() {
    when(apiKeyService.getApiKey(any(), any())).thenReturn(Optional.empty());
    when(apiKeyService.createApiKey(FORMAT, principalCollection)).thenReturn(TOKEN.toCharArray());
    assertEquals(FORMAT + "." + TOKEN, underTest.createToken(principalCollection));
    verify(apiKeyService).createApiKey(FORMAT, principalCollection);
  }

  @Test
  void reuseTokenWhenExists() {
    Optional<ApiKey> apiKey = Optional.of(mockApiKey(TOKEN.toCharArray()));
    when(apiKeyService.getApiKey(any(), any())).thenReturn(apiKey);
    assertEquals(FORMAT + "." + TOKEN, underTest.createToken(principalCollection));
    verify(apiKeyService, never()).createApiKey(any(), any());
  }

  @Test
  void deleteKey() {
    Optional<ApiKey> apiKey = Optional.of(mockApiKey(TOKEN.toCharArray()));
    when(apiKeyService.getApiKey(any(), any())).thenReturn(apiKey);
    assertTrue(underTest.deleteToken());
    verify(apiKeyService).deleteApiKey(FORMAT, principalCollection);
  }

  @Test
  void doNotDeleteKeyWhenNoKeyExists() {
    when(apiKeyService.getApiKey(any(), any())).thenReturn(Optional.empty());
    assertFalse(underTest.deleteToken());
    verify(apiKeyService, never()).deleteApiKey(FORMAT, principalCollection);
  }
  
  @Test
  void virtualThreadCompatibilityForTokenOperations() throws Exception {
    // Setup for virtual thread test
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Setup mock responses for all threads
      when(apiKeyService.getApiKey(any(), any())).thenReturn(Optional.empty());
      when(apiKeyService.createApiKey(FORMAT, principalCollection)).thenReturn(TOKEN.toCharArray());
      
      // Submit tasks to create and delete tokens using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Create a token
            String token = underTest.createToken(principalCollection);
            if ((FORMAT + "." + TOKEN).equals(token)) {
              // Token created successfully, now delete it
              when(apiKeyService.getApiKey(any(), any())).thenReturn(
                  Optional.of(mockApiKey(TOKEN.toCharArray())));
              if (underTest.deleteToken()) {
                successCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            // Exception during token operations
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout after 5 seconds
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all virtual thread tasks completed in time");
      assertEquals(taskCount, successCount.get(), "Not all token operations were successful");
    }
  }

  private ApiKey mockApiKey(final char[] token) {
    ApiKey key = mock(ApiKey.class);
    when(key.getApiKey()).thenReturn(token);
    return key;
  }
}