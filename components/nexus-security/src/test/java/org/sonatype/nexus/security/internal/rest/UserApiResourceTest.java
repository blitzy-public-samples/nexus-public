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
package org.sonatype.nexus.security.internal.rest;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.hamcrest.BeanMatchers;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.security.ErrorMessageUtil;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.config.AdminPasswordFileManager;
import org.sonatype.nexus.security.internal.AdminPasswordFileManagerImpl;
import org.sonatype.nexus.security.internal.RealmToSource;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserSearchCriteria;
import org.sonatype.nexus.security.user.UserStatus;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserApiResourceTest
    extends TestSupport
{
  public static final String USER_ID = "jsmith";
  private static final String SAML_REALM_NAME = "SamlRealm";
  private static final String CROWD_REALM_NAME = "Crowd";
  private static final String LDAP_REALM_NAME = "LdapRealm";
  private static final String NEXUS_AUTHENTICATING_REALM_NAME = "NexusAuthenticatingRealm";

  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private ApplicationDirectories applicationDirectories;

  private AdminPasswordFileManager adminPasswordFileManager;

  private UserApiResource underTest;

  @BeforeEach
  public void setup() throws Exception {
    when(applicationDirectories.getWorkDirectory()).thenReturn(util.createTempDir());
    adminPasswordFileManager = new AdminPasswordFileManagerImpl(applicationDirectories);
    underTest = new UserApiResource(securitySystem, adminPasswordFileManager);

    final User user = createUser();
    when(securitySystem.getUser(any(), any())).thenAnswer(i -> {
      if ("jdoe".equals(i.getArguments()[0]) && "LDAP".equals(i.getArguments()[1])) {
        throw new UserNotFoundException((String) i.getArguments()[0]);
      }
      return user;
    });
    when(securitySystem.getUser(user.getUserId())).thenReturn(user);

    UserManager ldap = mock(UserManager.class);
    when(ldap.supportsWrite()).thenReturn(false);
    when(securitySystem.getUserManager("LDAP")).thenReturn(ldap);

    when(securitySystem.getUserManager(UserManager.DEFAULT_SOURCE)).thenReturn(userManager);
    when(securitySystem.listRoles(UserManager.DEFAULT_SOURCE))
        .thenReturn(Collections.singleton(new Role("nx-admin", null, null, null, true, null, null)));
    when(userManager.supportsWrite()).thenReturn(true);
  }

  @AfterEach
  public void cleanup() {
    adminPasswordFileManager.removeFile();
  }

  /*
   * Get users
   */
  @Test
  public void shouldGetUsers() {
    when(securitySystem.searchUsers(any())).thenReturn(Collections.singleton(createUser()));
    Collection<ApiUser> users = underTest.getUsers("js", UserManager.DEFAULT_SOURCE);

    assertThat(users, hasSize(1));
    assertThat(users, contains(BeanMatchers.similarTo(underTest.fromUser(createUser()))));

    ArgumentCaptor<UserSearchCriteria> captor = ArgumentCaptor.forClass(UserSearchCriteria.class);
    verify(securitySystem).searchUsers(captor.capture());

    UserSearchCriteria criteria = captor.getValue();
    assertThat(criteria.getUserId(), is("js"));
    assertThat(criteria.getSource(), is(UserManager.DEFAULT_SOURCE));
    assertNull(criteria.getLimit());
  }

  @Test
  public void shouldGetUsersWithNonDefaultLimit() {
    when(securitySystem.searchUsers(any())).thenReturn(Collections.singleton(createUser()));

    underTest.getUsers("js", null);

    ArgumentCaptor<UserSearchCriteria> captor = ArgumentCaptor.forClass(UserSearchCriteria.class);
    verify(securitySystem).searchUsers(captor.capture());

    UserSearchCriteria criteria = captor.getValue();
    assertThat(criteria.getUserId(), is("js"));
    assertNull(criteria.getSource());
    assertThat(criteria.getLimit(), is(100));
  }

  /*
   * Create user
   */
  @Test
  public void shouldCreateUser() throws Exception {
    User user = createUser();
    when(securitySystem.addUser(user, "admin123")).thenReturn(user);
    ApiCreateUser createUser = new ApiCreateUser(USER_ID, "John", "Smith", "jsmith@example.org", "admin123",
        ApiUserStatus.disabled, Collections.singleton("nx-admin"));

    ApiUser returned = underTest.createUser(createUser);

    assertThat(returned, BeanMatchers.similarTo(underTest.fromUser(user)));

    verify(securitySystem).addUser(user, "admin123");
  }

  @Test
  public void shouldThrowExceptionWhenUserManagerIsMissing() throws Exception {
    User user = createUser();
    when(securitySystem.addUser(user, "admin123")).thenThrow(new NoSuchUserManagerException(user.getSource()));

    ApiCreateUser createUser = new ApiCreateUser(USER_ID, "John", "Smith", "jsmith@example.org", "admin123",
        ApiUserStatus.disabled, Collections.singleton("nx-admin"));

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.createUser(createUser));
    
    assertWebException(exception, Status.NOT_FOUND, "Unable to locate source: default");
  }

  /*
   * Delete user
   */
  @Test
  public void shouldDeleteUsersWithNoRealm() throws Exception {
    underTest.deleteUser(USER_ID, null);

    verify(securitySystem).deleteUser(USER_ID, UserManager.DEFAULT_SOURCE);
  }

  @Test
  public void shouldDeleteUsersWithSamlRealm() throws Exception {
    when(securitySystem.isValidRealm(SAML_REALM_NAME)).thenReturn(true);
    when(securitySystem.getUser(
        USER_ID,
        RealmToSource.getSource(SAML_REALM_NAME))).thenReturn(createUserWithSource(SAML_REALM_NAME));
    underTest.deleteUser(USER_ID, SAML_REALM_NAME);

    verify(securitySystem).deleteUser(USER_ID, RealmToSource.getSource(SAML_REALM_NAME));
  }

  @Test
  public void shouldThrowExceptionWhenRealmIsEmpty() {
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.deleteUser(USER_ID, ""));
    
    assertWebException(exception, Status.BAD_REQUEST, "Invalid or empty realm name.");
  }

  @Test
  public void shouldThrowExceptionWhenRealmIsInvalid() {
    when(securitySystem.isValidRealm(any())).thenReturn(false);
    
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.deleteUser(USER_ID, "InvalidRealm123"));
    
    assertWebException(exception, Status.BAD_REQUEST, "Invalid or empty realm name.");
  }

  @Test
  public void shouldDeleteUsersWithCrowdRealm() throws Exception {
    when(securitySystem.isValidRealm(CROWD_REALM_NAME)).thenReturn(true);
    when(securitySystem.getUser(USER_ID,
        RealmToSource.getSource(CROWD_REALM_NAME))).thenReturn(createUserWithSource(CROWD_REALM_NAME));
    underTest.deleteUser(USER_ID, CROWD_REALM_NAME);

    verify(securitySystem).deleteUser(USER_ID, RealmToSource.getSource(CROWD_REALM_NAME));
  }

  @Test
  public void shouldDeleteUsersWithLdapRealm() throws Exception {
    when(securitySystem.isValidRealm(LDAP_REALM_NAME)).thenReturn(true);
    when(securitySystem.getUser(USER_ID,
        RealmToSource.getSource(LDAP_REALM_NAME))).thenReturn(createUserWithSource(LDAP_REALM_NAME));
    underTest.deleteUser(USER_ID, LDAP_REALM_NAME);

    verify(securitySystem).deleteUser(USER_ID, RealmToSource.getSource(LDAP_REALM_NAME));
  }

  @Test
  public void shouldDeleteUsersWithDefaultRealm() throws Exception {
    when(securitySystem.isValidRealm(NEXUS_AUTHENTICATING_REALM_NAME)).thenReturn(true);
    when(securitySystem.getUser(USER_ID,
        RealmToSource.getSource(NEXUS_AUTHENTICATING_REALM_NAME))).thenReturn(createUserWithSource(NEXUS_AUTHENTICATING_REALM_NAME));
    underTest.deleteUser(USER_ID, NEXUS_AUTHENTICATING_REALM_NAME);

    verify(securitySystem).deleteUser(USER_ID, "default");
  }

  @Test
  public void shouldThrowExceptionWhenUserIsMissing() throws Exception {
    when(securitySystem.getUser("unknownuser")).thenThrow(new UserNotFoundException("unknownuser"));
    
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.deleteUser("unknownuser", null));
    
    assertWebException(exception, Status.NOT_FOUND, "User 'unknownuser' not found.");
  }

  @Test
  public void shouldThrowExceptionWhenUserManagerIsUnknown() throws Exception {
    User user = createUser();
    doThrow(new NoSuchUserManagerException(user.getSource())).when(securitySystem).deleteUser(user.getUserId(),
        user.getSource());
    
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.deleteUser(USER_ID, null));
    
    assertWebException(exception, Status.NOT_FOUND, "Unable to locate source: default");
  }

  /*
   * Update user
   */
  @Test
  public void shouldUpdateUser() throws Exception {
    User user = createUser();
    underTest.updateUser(USER_ID, underTest.fromUser(user));

    verify(securitySystem).updateUser(user);
  }

  @Test
  public void shouldUpdateUserWithNullExternal() throws Exception {
    User user = createUser();
    ApiUser apiUser = underTest.fromUser(user);
    apiUser.setExternalRoles(null);

    underTest.updateUser(USER_ID, apiUser);

    verify(securitySystem).updateUser(user);
  }

  @Test
  public void shouldUpdateUserWithExternalSource() throws Exception {
    User user = createUser();
    user.setSource("LDAP");
    ApiUser apiUser = underTest.fromUser(user);
    underTest.updateUser(USER_ID, apiUser);

    verify(securitySystem).setUsersRoles(USER_ID, "LDAP", user.getRoles());
  }

  @Test
  public void shouldThrowExceptionWhenExternalSourceUserIsUnknown() throws Exception {
    User user = createUser();

    ApiUser apiUser = new ApiUser("jdoe", user.getFirstName(), user.getLastName(), user.getEmailAddress(),
        "LDAP", ApiUserStatus.convert(user.getStatus()), true, Collections.emptySet(), Collections.emptySet());

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.updateUser("jdoe", apiUser));
    
    assertWebException(exception, Status.NOT_FOUND, "User 'jdoe' not found.");
  }

  @Test
  public void shouldThrowExceptionWhenDeletingLdapUser() throws Exception {
    when(securitySystem.getUser(any())).thenReturn(createLdapUser());
    
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.deleteUser("tanderson", null));
    
    assertWebException(exception, Status.BAD_REQUEST, "Non-local user cannot be deleted.");
  }

  @Test
  public void shouldThrowExceptionWhenUserIdMismatch() throws Exception {
    User user = createUser();
    
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.updateUser("fred", underTest.fromUser(user)));
    
    assertWebException(exception, Status.BAD_REQUEST, "The path's userId does not match the body");
  }

  @Test
  public void shouldThrowExceptionWhenUserSourceIsUnknown() throws Exception {
    User user = createUser();
    when(securitySystem.updateUser(user)).thenThrow(new NoSuchUserManagerException(user.getSource()));
    
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.updateUser(USER_ID, underTest.fromUser(user)));
    
    assertWebException(exception, Status.NOT_FOUND, "Unable to locate source: default");
  }

  @Test
  public void shouldThrowExceptionWhenUpdatingUnknownUser() throws Exception {
    User user = createUser();
    when(securitySystem.updateUser(user)).thenThrow(new UserNotFoundException(user.getUserId()));
    
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.updateUser(USER_ID, underTest.fromUser(user)));
    
    assertWebException(exception, Status.NOT_FOUND, "User 'jsmith' not found.");
  }

  /*
   * Change password
   */

  @Test
  public void shouldChangePassword() throws Exception {
    underTest.changePassword("test", "test");

    verify(securitySystem).changePassword("test", "test");
  }

  @Test
  public void shouldThrowExceptionWhenChangingPasswordForInvalidUser() throws Exception {
    doThrow(new UserNotFoundException("test")).when(securitySystem).changePassword("test", "test");

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.changePassword("test", "test"));
    
    assertWebException(exception, Status.NOT_FOUND, "User 'test' not found.");
  }

  @Test
  public void shouldThrowExceptionWhenPasswordIsMissing() throws Exception {
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.changePassword("test", null));
    
    assertWebException(exception, Status.BAD_REQUEST, "Password must be supplied.");
    verify(securitySystem, never()).changePassword(any(), any());
  }

  @Test
  public void shouldThrowExceptionWhenPasswordIsEmpty() throws Exception {
    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.changePassword("test", ""));
    
    assertWebException(exception, Status.BAD_REQUEST, "Password must be supplied.");
    verify(securitySystem, never()).changePassword(any(), any());
  }

  @Test
  public void shouldRemoveDefaultAdminFileWhenChangingAdminPassword() throws Exception {
    adminPasswordFileManager.writeFile("oldPassword");

    underTest.changePassword("admin", "newPassword");

    assertThat(adminPasswordFileManager.exists(), is(false));
  }

  @Test
  public void shouldNotRemoveDefaultAdminFileWhenChangingOtherUserPassword() throws Exception {
    adminPasswordFileManager.writeFile("oldPassword");

    underTest.changePassword("test", "test");

    verify(securitySystem).changePassword("test", "test");
    assertThat(adminPasswordFileManager.exists(), is(true));
  }

  /*
   * Virtual Thread tests for concurrent operations
   */
  
  @Test
  public void testConcurrentGetUsers() throws Exception {
    when(securitySystem.searchUsers(any())).thenReturn(Collections.singleton(createUser()));
    
    int numThreads = 10;
    ExecutorService executor = createVirtualThreadExecutor();
    
    try {
      List<Future<Collection<ApiUser>>> futures = new ArrayList<>();
      
      // Submit multiple concurrent requests
      for (int i = 0; i < numThreads; i++) {
        futures.add(executor.submit(() -> underTest.getUsers("js", UserManager.DEFAULT_SOURCE)));
      }
      
      // Verify all requests completed successfully
      for (Future<Collection<ApiUser>> future : futures) {
        Collection<ApiUser> users = future.get(5, TimeUnit.SECONDS);
        assertThat(users, hasSize(1));
        assertThat(users, contains(BeanMatchers.similarTo(underTest.fromUser(createUser()))));
      }
      
      // Verify the security system was called the expected number of times
      verify(securitySystem, times(numThreads)).searchUsers(any());
    } 
    finally {
      executor.shutdownNow();
    }
  }
  
  @Test
  public void testConcurrentCreateUser() throws Exception {
    User user = createUser();
    when(securitySystem.addUser(any(), any())).thenReturn(user);
    
    int numThreads = 10;
    AtomicInteger counter = new AtomicInteger(0);
    ExecutorService executor = createVirtualThreadExecutor();
    
    try {
      List<Future<ApiUser>> futures = new ArrayList<>();
      
      // Submit multiple concurrent requests with unique user IDs
      for (int i = 0; i < numThreads; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          String userId = USER_ID + counter.incrementAndGet();
          ApiCreateUser createUser = new ApiCreateUser(userId, "John", "Smith", 
              "jsmith" + index + "@example.org", "password", ApiUserStatus.disabled, 
              Collections.singleton("nx-admin"));
          return underTest.createUser(createUser);
        }));
      }
      
      // Verify all requests completed successfully
      for (Future<ApiUser> future : futures) {
        ApiUser apiUser = future.get(5, TimeUnit.SECONDS);
        assertThat(apiUser.getFirstName(), is("John"));
        assertThat(apiUser.getLastName(), is("Smith"));
      }
      
      // Verify the security system was called the expected number of times
      verify(securitySystem, times(numThreads)).addUser(any(), any());
    } 
    finally {
      executor.shutdownNow();
    }
  }
  
  @Test
  public void testConcurrentPasswordChange() throws Exception {
    int numThreads = 10;
    ExecutorService executor = createVirtualThreadExecutor();
    
    try {
      List<Future<Void>> futures = new ArrayList<>();
      
      // Submit multiple concurrent password change requests
      for (int i = 0; i < numThreads; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          underTest.changePassword("test" + index, "newpassword" + index);
          return null;
        }));
      }
      
      // Verify all requests completed successfully
      for (Future<Void> future : futures) {
        future.get(5, TimeUnit.SECONDS);
      }
      
      // Verify the security system was called the expected number of times
      verify(securitySystem, times(numThreads)).changePassword(any(), any());
    } 
    finally {
      executor.shutdownNow();
    }
  }

  /**
   * Helper method to create a Virtual Thread executor service.
   */
  private ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Helper method to assert WebApplicationMessageException properties.
   */
  private void assertWebException(WebApplicationMessageException exception, Status status, String message) {
    assertThat(exception.getResponse().getStatus(), is(status.getStatusCode()));
    assertThat(exception.getResponse().getEntity().toString(), 
        is(ErrorMessageUtil.getFormattedMessage("\"" + message + "\"")));
    assertThat(exception.getResponse().getMediaType(), is(MediaType.APPLICATION_JSON_TYPE));
  }

  private User createUser() {
    User user = new User();
    user.setEmailAddress("john@example.org");
    user.setFirstName("John");
    user.setLastName("Smith");
    user.setReadOnly(false);
    user.setStatus(UserStatus.disabled);
    user.setUserId(USER_ID);
    user.setVersion(1);
    user.setSource(UserManager.DEFAULT_SOURCE);
    user.setRoles(Collections.singleton(new RoleIdentifier(UserManager.DEFAULT_SOURCE, "nx-admin")));
    return user;
  }

  private User createLdapUser() {
    User user = new User();
    user.setEmailAddress("thomas@example.org");
    user.setFirstName("Thomas");
    user.setLastName("Anderson");
    user.setReadOnly(false);
    user.setStatus(UserStatus.disabled);
    user.setUserId("tanderson");
    user.setVersion(1);
    user.setSource("LDAP");
    user.setRoles(Collections.singleton(new RoleIdentifier(UserManager.DEFAULT_SOURCE, "nx-admin")));
    return user;
  }

  private User createUserWithSource(String realm) {
    User user = new User();
    user.setEmailAddress("john@example.org");
    user.setFirstName("John");
    user.setLastName("Smith");
    user.setReadOnly(false);
    user.setStatus(UserStatus.disabled);
    user.setUserId(USER_ID);
    user.setVersion(1);
    user.setSource(RealmToSource.getSource(realm));
    user.setRoles(Collections.singleton(new RoleIdentifier(RealmToSource.getSource(realm), "nx-admin")));
    return user;
  }

  @Mock
  private UserManager userManager;
}