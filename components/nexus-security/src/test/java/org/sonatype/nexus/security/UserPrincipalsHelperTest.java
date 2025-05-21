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
package org.sonatype.nexus.security;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserStatus;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link UserPrincipalsHelper}.
 */
@ExtendWith(MockitoExtension.class)
public class UserPrincipalsHelperTest
    extends TestSupport
{
  private static final String ALPHA_REALM = "ALPHA";

  private static final String BETA_REALM = "BETA";

  private static final String NO_USER_MANAGER_REALM = "NO_MANAGER";

  private static final String PRIMARY_PRINCIPAL = "JoeUser";

  @Mock
  private UserManager userManagerAlpha;

  @Mock
  private UserManager userManagerBeta;

  @Mock
  private User user;

  private UserPrincipalsHelper underTest;

  private final Set<String> USER_IDS = ImmutableSet.of(PRIMARY_PRINCIPAL);

  @BeforeEach
  public void setup() {
    when(userManagerAlpha.getAuthenticationRealmName()).thenReturn(ALPHA_REALM);
    when(userManagerAlpha.listUserIds()).thenReturn(USER_IDS);

    when(userManagerBeta.getAuthenticationRealmName()).thenReturn(BETA_REALM);
    when(userManagerBeta.listUserIds()).thenReturn(ImmutableSet.of("NoUser"));

    underTest = new UserPrincipalsHelper(ImmutableList.of(userManagerAlpha, userManagerBeta));
  }

  @Test
  public void testFindUserManagerWhenPrimaryPrincipal()
      throws NoSuchUserManagerException
  {
      final PrincipalCollection principals = getPrincipals();
      UserManager userManager = underTest.findUserManager(principals);
      assertThat(userManager, is(userManagerAlpha));
  }

  @Test
  public void testFindUserManagerWhenNotPrimaryPrincipal()
      throws NoSuchUserManagerException
  {
    //Set the primary principal realm to a realm that has no user manager.
    //This aligns with the scenario where 'rutauth-realm' is primary (first)
    final PrincipalCollection principals = getPrincipalsWithNoUsrMgrPrimary();

    UserManager userManager = underTest.findUserManager(principals);
    assertThat(userManager, is(userManagerAlpha));
  }

  @Test
  public void testFindUserManagerForPrincipals_NotFound() {
    final SimplePrincipalCollection principals = new SimplePrincipalCollection();
    principals.add(PRIMARY_PRINCIPAL, "foo");
    
    NoSuchUserManagerException exception = assertThrows(NoSuchUserManagerException.class, () -> {
      underTest.findUserManager(principals);
    });
    
    assertThat(exception.getMessage(), CoreMatchers.is("User-manager not found for realm(s): [foo]"));
  }

  @Test
  public void testFindUserManagerWithNoPrincipals_Missing() {
    NoSuchUserManagerException exception = assertThrows(NoSuchUserManagerException.class, () -> {
      underTest.findUserManager(null);
    });
    
    assertThat(exception.getMessage(), CoreMatchers.is("User-manager not found: Missing principals"));
  }

  @Test
  public void testGetUserStatus_NoUserManagerFound() {
    final SimplePrincipalCollection principals = new SimplePrincipalCollection();
    principals.add(PRIMARY_PRINCIPAL, "foo");
    principals.add(PRIMARY_PRINCIPAL, "boo");
    principals.add(PRIMARY_PRINCIPAL, "hoo");
    
    UserNotFoundException exception = assertThrows(UserNotFoundException.class, () -> {
      underTest.getUserStatus(principals);
    });
    
    assertThat(exception.getMessage(),
        CoreMatchers.is("User not found: JoeUser; User-manager not found for realm(s): [foo, boo, hoo]"));
  }

  @Test
  public void tesGetUserStatus() throws UserNotFoundException {
    when(user.getStatus()).thenReturn(UserStatus.active);
    when(userManagerAlpha.getUser(PRIMARY_PRINCIPAL)).thenReturn(user);

    final PrincipalCollection principals = getPrincipals();
    assertThat(underTest.getUserStatus(principals), is(UserStatus.active));
  }

  @Test
  public void testGetUserStatus_WhenNotPrimary() throws UserNotFoundException {
    when(user.getStatus()).thenReturn(UserStatus.active);
    when(userManagerAlpha.getUser(PRIMARY_PRINCIPAL)).thenReturn(user);

    final PrincipalCollection principals = getPrincipalsWithNoUsrMgrPrimary();
    assertThat(underTest.getUserStatus(principals), is(UserStatus.active));
  }
  
  @Test
  public void testPrincipalPropagationAcrossVirtualThreads() throws ExecutionException, InterruptedException {
    // Setup user and principals
    when(user.getStatus()).thenReturn(UserStatus.active);
    when(userManagerAlpha.getUser(PRIMARY_PRINCIPAL)).thenReturn(user);
    final PrincipalCollection principals = getPrincipals();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Execute a task in a virtual thread that accesses the principals
      CompletableFuture<UserStatus> future = CompletableFuture.supplyAsync(() -> {
        try {
          // Verify we can find the user manager with the principals in a virtual thread
          UserManager manager = underTest.findUserManager(principals);
          assertThat(manager, is(userManagerAlpha));
          
          // Verify we can get the user status with the principals in a virtual thread
          return underTest.getUserStatus(principals);
        } 
        catch (Exception e) {
          throw new RuntimeException("Failed to access principals in virtual thread", e);
        }
      }, executor);
      
      // Verify the result from the virtual thread
      UserStatus status = future.get();
      assertThat(status, is(UserStatus.active));
    }
  }
  
  @Test
  public void testMultipleConcurrentVirtualThreadsWithPrincipals() throws ExecutionException, InterruptedException {
    // Setup user and principals
    when(user.getStatus()).thenReturn(UserStatus.active);
    when(userManagerAlpha.getUser(PRIMARY_PRINCIPAL)).thenReturn(user);
    final PrincipalCollection principals = getPrincipals();
    
    // Number of concurrent virtual threads to test with
    int threadCount = 10;
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create multiple concurrent tasks
      CompletableFuture<?>[] futures = new CompletableFuture[threadCount];
      
      for (int i = 0; i < threadCount; i++) {
        futures[i] = CompletableFuture.supplyAsync(() -> {
          try {
            // Verify we can find the user manager with the principals in a virtual thread
            UserManager manager = underTest.findUserManager(principals);
            assertThat(manager, is(userManagerAlpha));
            
            // Verify we can get the user status with the principals in a virtual thread
            UserStatus status = underTest.getUserStatus(principals);
            assertThat(status, is(UserStatus.active));
            return true;
          } 
          catch (Exception e) {
            throw new RuntimeException("Failed to access principals in virtual thread", e);
          }
        }, executor);
      }
      
      // Wait for all futures to complete and verify results
      CompletableFuture.allOf(futures).join();
    }
  }

  //Returns a collection of principals with the primary (first) having
  //no associated user manager
  private PrincipalCollection getPrincipalsWithNoUsrMgrPrimary() {
    final SimplePrincipalCollection principals =
        new SimplePrincipalCollection(PRIMARY_PRINCIPAL, NO_USER_MANAGER_REALM);

    principals.addAll(getPrincipals());

    return principals;
  }

  //Returns a simple collection of principals
  private PrincipalCollection getPrincipals() {
    final SimplePrincipalCollection principals = new SimplePrincipalCollection();
    //Set the primary principal
    principals.add(PRIMARY_PRINCIPAL, userManagerAlpha.getAuthenticationRealmName());
    principals.add(PRIMARY_PRINCIPAL, userManagerBeta.getAuthenticationRealmName());

    return principals;
  }
}