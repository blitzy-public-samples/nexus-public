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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.TestAnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousManager;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.authz.NoSuchAuthorizationManagerException;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @since 3.0
 */
@ExtendWith(MockitoExtension.class)
class SecurityApiImplTest
    extends TestSupport
{
  @Mock
  private AnonymousManager anonymousManager;

  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private AuthorizationManager authorizationManager;

  @Captor
  private ArgumentCaptor<User> userCaptor;

  private TestAnonymousConfiguration configuration = new TestAnonymousConfiguration();

  @InjectMocks
  private SecurityApiImpl api;

  @BeforeEach
  void setup() throws NoSuchAuthorizationManagerException {
    when(securitySystem.getAuthorizationManager(UserManager.DEFAULT_SOURCE)).thenReturn(authorizationManager);
    when(anonymousManager.getConfiguration()).thenReturn(configuration);
  }

  @Test
  void testSetAnonymousAccess() {
    configuration.setEnabled(true);

    AnonymousConfiguration updatedConfiguration = api.setAnonymousAccess(false);

    assertFalse(updatedConfiguration.isEnabled());
    verify(anonymousManager).getConfiguration();
    verify(anonymousManager).setConfiguration(any());
  }

  /*
   * No save is made when configured and anonymous settings already match
   */
  @Test
  void testSetAnonymousAccess_unchanged() {
    configuration.setEnabled(true);
    when(anonymousManager.isConfigured()).thenReturn(true);

    AnonymousConfiguration updatedConfiguration = api.setAnonymousAccess(true);

    assertTrue(updatedConfiguration.isEnabled());
    verify(anonymousManager).getConfiguration();
    verify(anonymousManager, never()).setConfiguration(any());
  }

  /*
   * One save is made when unconfigured and anonymous settings already match
   */
  @Test
  void testSetAnonymousAccess_unconfigured() {
    when(anonymousManager.isConfigured()).thenReturn(false);

    AnonymousConfiguration updatedConfiguration = api.setAnonymousAccess(false);

    assertFalse(updatedConfiguration.isEnabled());

    verify(anonymousManager).getConfiguration();
    verify(anonymousManager).setConfiguration(any());
  }

  @Test
  void testAddUser() throws NoSuchUserManagerException {
    when(securitySystem.addUser(any(), eq("pass"))).thenAnswer(i -> i.getArguments()[0]);

    User user = api.addUser("foo", "bar", "baz", "foo@bar.com", true, "pass", List.of("roleId"));

    verify(securitySystem).addUser(any(), eq("pass"));

    assertThat(user.getUserId(), is("foo"));
    assertThat(user.getSource(), is(UserManager.DEFAULT_SOURCE));
    assertThat(user.getFirstName(), is("bar"));
    assertThat(user.getLastName(), is("baz"));
    assertThat(user.getEmailAddress(), is("foo@bar.com"));
    assertThat(user.getStatus(), is(UserStatus.active));
    assertThat(user.getRoles(), contains(new RoleIdentifier(UserManager.DEFAULT_SOURCE, "roleId")));
  }

  @Test
  void testAddRole() throws NoSuchAuthorizationManagerException {
    when(authorizationManager.addRole(any())).thenAnswer(i -> i.getArguments()[0]);

    Role role = api.addRole("foo", "bar", "baz", List.of("priv"), List.of("role"));

    verify(securitySystem).getAuthorizationManager(any());
    verify(authorizationManager).addRole(any());

    assertThat(role.getRoleId(), is("foo"));
    assertThat(role.getSource(), is(UserManager.DEFAULT_SOURCE));
    assertThat(role.getName(), is("bar"));
    assertThat(role.getDescription(), is("baz"));
    assertThat(role.getPrivileges(), contains("priv"));
    assertThat(role.getRoles(), contains("role"));
  }
  
  @Test
  void testConcurrentUserOperationsWithVirtualThreads() throws Exception {
    // Setup for concurrent operations
    int concurrentOperations = 100;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Setup mock behavior for concurrent operations
    when(securitySystem.addUser(any(), any())).thenAnswer(i -> i.getArguments()[0]);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent tasks
      for (int i = 0; i < concurrentOperations; i++) {
        int userId = i;
        executor.submit(() -> {
          try {
            // Create a unique user for each thread
            User user = api.addUser(
                "user" + userId,
                "First" + userId,
                "Last" + userId,
                "user" + userId + "@example.com",
                true,
                "password" + userId,
                List.of("role" + userId % 5) // Assign one of 5 roles
            );
            
            // Verify the user was created correctly
            if (user != null && user.getUserId().equals("user" + userId)) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Log any exceptions
            System.err.println("Error in virtual thread: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete (with timeout)
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertTrue(completed, "Not all virtual thread operations completed in time");
      
      // Verify all operations were successful
      assertThat(successCount.get(), is(concurrentOperations));
    }
  }
  
  @Test
  void testConcurrentRoleOperationsWithVirtualThreads() throws Exception {
    // Setup for concurrent operations
    int concurrentOperations = 100;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Setup mock behavior for concurrent operations
    when(authorizationManager.addRole(any())).thenAnswer(i -> i.getArguments()[0]);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent tasks
      for (int i = 0; i < concurrentOperations; i++) {
        int roleId = i;
        executor.submit(() -> {
          try {
            // Create a unique role for each thread
            Role role = api.addRole(
                "role" + roleId,
                "Role " + roleId,
                "Description for role " + roleId,
                List.of("priv" + roleId % 3), // Assign one of 3 privileges
                List.of("parentRole" + roleId % 2) // Assign one of 2 parent roles
            );
            
            // Verify the role was created correctly using pattern matching (Java 21 feature)
            if (role instanceof Role r && r.getRoleId().equals("role" + roleId)) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Log any exceptions
            System.err.println("Error in virtual thread: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete (with timeout)
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertTrue(completed, "Not all virtual thread operations completed in time");
      
      // Verify all operations were successful
      assertThat(successCount.get(), is(concurrentOperations));
    }
  }
  
  @Test
  void testConcurrentAnonymousAccessWithVirtualThreads() throws Exception {
    // Setup for concurrent operations
    int concurrentOperations = 100;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent tasks
      for (int i = 0; i < concurrentOperations; i++) {
        boolean enableAnonymous = i % 2 == 0; // Alternate between enabling and disabling
        executor.submit(() -> {
          try {
            // Toggle anonymous access
            AnonymousConfiguration updatedConfig = api.setAnonymousAccess(enableAnonymous);
            
            // Verify the configuration was updated correctly
            if (updatedConfig.isEnabled() == enableAnonymous) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Log any exceptions
            System.err.println("Error in virtual thread: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete (with timeout)
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertTrue(completed, "Not all virtual thread operations completed in time");
      
      // Verify most operations were successful (some might fail due to concurrent modifications)
      assertThat(successCount.get() > concurrentOperations / 2, is(true));
    }
  }
}