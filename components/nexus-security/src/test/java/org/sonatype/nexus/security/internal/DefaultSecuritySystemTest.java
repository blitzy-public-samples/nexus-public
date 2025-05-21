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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.UserPrincipalsExpired;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.authz.MockAuthorizationManagerB;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserStatus;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DefaultSecuritySystem}.
 */
@ExtendWith(MockitoExtension.class)
public class DefaultSecuritySystemTest
    extends AbstractSecurityTest
{
  @Mock
  EventManager eventManager;

  @Override
  protected void customizeModules(List<Module> modules) {
    super.customizeModules(modules);
    modules.add(new AbstractModule()
    {
      @Override
      protected void configure() {
        bind(AuthorizationManager.class)
            .annotatedWith(Names.named("sourceB"))
            .to(MockAuthorizationManagerB.class)
            .in(Singleton.class);
      }
    });
  }

  @BeforeEach
  public void setup() throws Exception {
    reset(eventManager);
  }

  @Override
  protected void tearDown() throws Exception {
    this.getSecuritySystem().stop();

    super.tearDown();
  }

  @Override
  public EventManager getEventManager() {
    return eventManager;
  }

  @Test
  public void testLogin() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();

    // login
    UsernamePasswordToken token = new UsernamePasswordToken("jcoder", "jcoder");
    Subject subject = securitySystem.getSubject();
    assertNotNull(subject);
    subject.login(token);

    assertThrows(AuthenticationException.class, () -> {
      subject.login(new UsernamePasswordToken("jcoder", "INVALID"));
    }, "expected AuthenticationException");
  }

  @Test
  public void testLogout() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();

    // bind to a servlet request/response
    // this.setupLoginContext( "test" );

    // login
    UsernamePasswordToken token = new UsernamePasswordToken("jcoder", "jcoder");
    Subject subject = securitySystem.getSubject();
    assertNotNull(subject);
    subject.login(token);

    // check the logged in user
    Subject loggedinSubject = securitySystem.getSubject();
    // assertEquals( subject.getSession().getId(), loggedinSubject.getSession().getId() );
    assertTrue(subject.isAuthenticated());
    assertTrue(loggedinSubject.isAuthenticated(), 
        "Subject principal: " + loggedinSubject.getPrincipal() + " is not logged in");
    loggedinSubject.logout();

    // the current user should be null
    subject = securitySystem.getSubject();
    assertFalse(subject.isAuthenticated());
    assertFalse(loggedinSubject.isAuthenticated());
  }

  @Test
  public void testAuthorization() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();
    PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");
    
    assertThrows(AuthorizationException.class, () -> {
      securitySystem.checkPermission(principal, "INVALID-ROLE:*");
    }, "expected: AuthorizationException");

    assertDoesNotThrow(() -> {
      securitySystem.checkPermission(principal, "test:read");
    });
  }

  /*
   * FIXME: BROKEN
   */
  public void BROKENtestPermissionFromRole() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();
    PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");

    securitySystem.checkPermission(principal, "from-role2:read");
  }

  @Test
  public void testGetUser() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();
    User jcoder = securitySystem.getUser("jcoder", "MockUserManagerA");

    assertNotNull(jcoder);
  }

  @Test
  public void testAuthorizationManager() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();

    Set<Role> roles = securitySystem.listRoles("sourceB");
    assertThat(roles.size(), is(2));

    Map<String, Role> roleMap = new HashMap<String, Role>();
    for (Role role : roles) {
      roleMap.put(role.getRoleId(), role);
    }

    assertTrue(roleMap.containsKey("test-role1"));
    assertTrue(roleMap.containsKey("test-role2"));

    Role role1 = roleMap.get("test-role1");
    assertThat(role1.getName(), is("Role 1"));

    assertTrue(role1.getPrivileges().contains("from-role1:read"));
    assertTrue(role1.getPrivileges().contains("from-role1:delete"));
  }

  @Test
  public void testSearchRoles() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();

    Set<Role> roles = securitySystem.searchRoles("sourceB", "query");
    // Search is equal to listRoles for not LDAP sources
    assertThat(roles, is(securitySystem.listRoles()));
  }

  @Test
  public void testAddUser() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();

    User user = new User();
    user.setEmailAddress("email@foo.com");
    user.setName("testAddUser");
    user.setSource("MockUserManagerA");
    user.setStatus(UserStatus.active);
    user.setUserId("testAddUser");

    user.addRole(new RoleIdentifier("default", "test-role1"));

    assertNotNull(securitySystem.addUser(user, "test123"));
  }

  @Test
  public void testUpdateUser_changePasswordStatus() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();

    securitySystem.addUser(createUser("testUpdateUser", UserStatus.changepassword), "test123");

    securitySystem.updateUser(createUser("testUpdateUser", UserStatus.disabled));

    boolean foundExpiredEvent = false;
    ArgumentCaptor<Object> eventArgument = ArgumentCaptor.forClass(Object.class);
    verify(eventManager, times(2)).post(eventArgument.capture());
    for (Object argValue : eventArgument.getAllValues()) {
      if (argValue instanceof UserPrincipalsExpired) {
        UserPrincipalsExpired expired = (UserPrincipalsExpired) argValue;
        assertThat(expired.getUserId(), is("testUpdateUser"));
        foundExpiredEvent = true;
      }
    }

    if (!foundExpiredEvent) {
      fail("UserPrincipalsExpired event was not fired");
    }
  }

  @Test
  public void testChangePassword_AfterUserLogin() throws UserNotFoundException, NoSuchUserManagerException {
    SecuritySystem securitySystem = this.getSecuritySystem();
    Subject subject = securitySystem.getSubject();
    subject.login(new UsernamePasswordToken("jcoder", "jcoder"));

    // change my own
    assertDoesNotThrow(() -> {
      securitySystem.changePassword("jcoder", "newpassword");
    });

    // change another user's password
    AuthorizationException exception = assertThrows(AuthorizationException.class, () -> {
      securitySystem.changePassword("fakeuser", "newpassword");
    });
    
    assertThat(exception.getMessage(), is("jcoder is not permitted to change the password for fakeuser"));
  }

  private User createUser(String name, UserStatus status) {
    User user = new User();
    user.setEmailAddress("email@foo.com");
    user.setName(name);
    user.setSource("MockUserManagerA");
    user.setStatus(status);
    user.setUserId(name);

    user.addRole(new RoleIdentifier("default", "test-role1"));

    return user;
  }
  
  @Test
  public void testSecuritySystemWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();
    int threadCount = 10;
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple tasks to be executed by virtual threads
      Future<?>[] futures = new Future<?>[threadCount];
      
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        futures[i] = executor.submit(() -> {
          try {
            // Test subject creation in virtual threads
            Subject subject = securitySystem.getSubject();
            assertNotNull(subject, "Subject should not be null in virtual thread " + threadId);
            
            // Test login in virtual threads (only for the first thread to avoid too many logins)
            if (threadId == 0) {
              UsernamePasswordToken token = new UsernamePasswordToken("jcoder", "jcoder");
              subject.login(token);
              assertTrue(subject.isAuthenticated(), "Subject should be authenticated in virtual thread");
              
              // Test permission check in virtual thread
              assertTrue(subject.isPermitted("test:read"), "Subject should have test:read permission");
              
              // Test logout in virtual thread
              subject.logout();
              assertFalse(subject.isAuthenticated(), "Subject should be logged out in virtual thread");
            }
            
            // Test permission checks with principal collection in all threads
            PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");
            securitySystem.checkPermission(principal, "test:read");
            
            return null;
          } catch (Exception e) {
            throw new RuntimeException("Error in virtual thread " + threadId, e);
          }
        });
      }
      
      // Wait for all threads to complete and check for exceptions
      for (int i = 0; i < threadCount; i++) {
        try {
          futures[i].get();
        } catch (Exception e) {
          fail("Virtual thread execution failed: " + e.getMessage());
        }
      }
    }
  }
}
