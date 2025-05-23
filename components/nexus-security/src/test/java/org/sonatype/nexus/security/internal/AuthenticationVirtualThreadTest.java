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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.authz.MockAuthorizationManagerB;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserStatus;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.reset;

/**
 * Tests for authentication operations using Java 21 Virtual Threads.
 * 
 * This test ensures that authentication processes in Nexus security correctly support
 * Java 21 Virtual Threads. It validates that login operations, token validation, and
 * session management maintain correctness and thread safety when executed by thousands
 * of concurrent Virtual Threads.
 */
public class AuthenticationVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int CONCURRENT_THREADS_HIGH = 5000;
  private static final String TEST_USERNAME = "jcoder";
  private static final String TEST_PASSWORD = "jcoder";
  private static final String INVALID_PASSWORD = "invalid";

  @Mock
  private EventManager eventManager;

  private SecuritySystem securitySystem;
  private AbstractSecurityTest securityTest;

  @Before
  public void setUp() throws Exception {
    // Skip test if Virtual Threads are not supported
    assumeVirtualThreadSupported();
    
    // Set up the security test environment
    securityTest = new AbstractSecurityTest() {
      @Override
      protected void customizeModules(List<Module> modules) {
        super.customizeModules(modules);
        modules.add(new AbstractModule() {
          @Override
          protected void configure() {
            bind(AuthorizationManager.class)
                .annotatedWith(Names.named("sourceB"))
                .to(MockAuthorizationManagerB.class)
                .in(Singleton.class);
          }
        });
      }

      @Override
      public EventManager getEventManager() {
        return eventManager;
      }
    };
    
    securityTest.setUp();
    reset(eventManager);
    securitySystem = securityTest.getSecuritySystem();
  }

  /**
   * Tests that login operations work correctly when executed by many concurrent Virtual Threads.
   * This verifies that the authentication process is thread-safe and can handle high concurrency.
   */
  @Test
  public void testConcurrentLoginWithVirtualThreads() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);

    // Create a large number of Virtual Threads to perform login operations concurrently
    runConcurrently(CONCURRENT_THREADS, () -> {
      try {
        UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
        Subject subject = securitySystem.getSubject();
        subject.login(token);
        assertTrue(subject.isAuthenticated());
        subject.logout();
        successCount.incrementAndGet();
      }
      catch (Exception e) {
        failureCount.incrementAndGet();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for all threads to complete
    assertTrue("Timed out waiting for authentication threads", latch.await(30, TimeUnit.SECONDS));
    
    // Verify all authentications succeeded
    assertEquals("All login attempts should succeed", CONCURRENT_THREADS, successCount.get());
    assertEquals("No login attempts should fail", 0, failureCount.get());
  }

  /**
   * Tests that invalid login attempts are correctly rejected when executed by concurrent Virtual Threads.
   * This verifies that authentication failures are handled properly under high concurrency.
   */
  @Test
  public void testConcurrentInvalidLoginWithVirtualThreads() throws Exception {
    AtomicInteger expectedFailureCount = new AtomicInteger(0);
    AtomicInteger unexpectedSuccessCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);

    // Create a large number of Virtual Threads to perform invalid login operations concurrently
    runConcurrently(CONCURRENT_THREADS, () -> {
      try {
        UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, INVALID_PASSWORD);
        Subject subject = securitySystem.getSubject();
        subject.login(token);
        // This should not happen - login should fail with invalid credentials
        unexpectedSuccessCount.incrementAndGet();
      }
      catch (AuthenticationException e) {
        // This is expected
        expectedFailureCount.incrementAndGet();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for all threads to complete
    assertTrue("Timed out waiting for authentication threads", latch.await(30, TimeUnit.SECONDS));
    
    // Verify all authentications failed as expected
    assertEquals("All invalid login attempts should fail", CONCURRENT_THREADS, expectedFailureCount.get());
    assertEquals("No invalid login attempts should succeed", 0, unexpectedSuccessCount.get());
  }

  /**
   * Tests that token validation works correctly under high Virtual Thread concurrency.
   * This verifies that the token validation process is thread-safe and can handle high concurrency.
   */
  @Test
  public void testConcurrentTokenValidationWithVirtualThreads() throws Exception {
    // First login to get a valid subject
    UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
    Subject initialSubject = securitySystem.getSubject();
    initialSubject.login(token);
    assertTrue(initialSubject.isAuthenticated());
    
    // Now validate the token from many concurrent Virtual Threads
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS_HIGH);
    
    // Create a large number of Virtual Threads to validate the token concurrently
    runConcurrently(CONCURRENT_THREADS_HIGH, () -> {
      try {
        // Check if the subject is still authenticated
        if (initialSubject.isAuthenticated()) {
          successCount.incrementAndGet();
        }
        else {
          failureCount.incrementAndGet();
        }
      }
      catch (Exception e) {
        failureCount.incrementAndGet();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for all threads to complete
    assertTrue("Timed out waiting for token validation threads", latch.await(30, TimeUnit.SECONDS));
    
    // Verify all validations succeeded
    assertEquals("All token validations should succeed", CONCURRENT_THREADS_HIGH, successCount.get());
    assertEquals("No token validations should fail", 0, failureCount.get());
    
    // Cleanup
    initialSubject.logout();
  }

  /**
   * Tests that realm interactions work correctly with Virtual Threads.
   * This verifies that the security subsystem properly interacts with authentication providers
   * when operations are executed by Virtual Threads.
   */
  @Test
  public void testRealmInteractionWithVirtualThreads() throws Exception {
    // Create a map to track results by thread
    Map<Thread, Boolean> threadResults = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);

    // Create a large number of Virtual Threads to interact with realms concurrently
    runConcurrently(CONCURRENT_THREADS, () -> {
      try {
        // Perform a login operation that will interact with the realm
        UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
        Subject subject = securitySystem.getSubject();
        subject.login(token);
        
        // Verify the subject has the expected permissions from the realm
        boolean hasPermission = subject.isPermitted("test:read");
        threadResults.put(Thread.currentThread(), hasPermission);
        
        // Cleanup
        subject.logout();
      }
      catch (Exception e) {
        threadResults.put(Thread.currentThread(), false);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for all threads to complete
    assertTrue("Timed out waiting for realm interaction threads", latch.await(30, TimeUnit.SECONDS));
    
    // Verify all threads successfully interacted with the realm
    for (Map.Entry<Thread, Boolean> entry : threadResults.entrySet()) {
      assertTrue("Thread " + entry.getKey() + " failed to get expected permissions from realm", entry.getValue());
    }
    assertEquals("All threads should have reported results", CONCURRENT_THREADS, threadResults.size());
  }

  /**
   * Tests that AuthenticatingRealm implementations remain thread-safe with Virtual Threads.
   * This verifies that the realm can handle concurrent authentication requests from Virtual Threads.
   */
  @Test
  public void testAuthenticatingRealmThreadSafetyWithVirtualThreads() throws Exception {
    // Create multiple users to test concurrent access to the realm
    List<User> testUsers = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      User user = new User();
      String userId = "testUser" + i;
      user.setUserId(userId);
      user.setSource("default");
      user.setFirstName("Test");
      user.setLastName("User" + i);
      user.setEmailAddress(userId + "@example.com");
      user.setStatus(UserStatus.active);
      
      // Add the user to the system
      securitySystem.addUser(user, "password" + i);
      testUsers.add(user);
    }
    
    // Track results
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS_HIGH);
    
    // Create a large number of Virtual Threads to authenticate different users concurrently
    runConcurrently(CONCURRENT_THREADS_HIGH, () -> {
      try {
        // Select a user based on thread ID to distribute load
        int userIndex = (int) (Thread.currentThread().threadId() % testUsers.size());
        User user = testUsers.get(userIndex);
        
        // Authenticate as this user
        UsernamePasswordToken token = new UsernamePasswordToken(user.getUserId(), "password" + userIndex);
        Subject subject = securitySystem.getSubject();
        subject.login(token);
        
        // Verify authentication succeeded
        assertTrue(subject.isAuthenticated());
        assertEquals(user.getUserId(), subject.getPrincipal().toString());
        
        // Cleanup
        subject.logout();
        successCount.incrementAndGet();
      }
      catch (Exception e) {
        failureCount.incrementAndGet();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for all threads to complete
    assertTrue("Timed out waiting for authentication threads", latch.await(60, TimeUnit.SECONDS));
    
    // Verify all authentications succeeded
    assertEquals("All authentication attempts should succeed", CONCURRENT_THREADS_HIGH, successCount.get());
    assertEquals("No authentication attempts should fail", 0, failureCount.get());
    
    // Clean up test users
    for (User user : testUsers) {
      securitySystem.deleteUser(user.getUserId());
    }
  }

  /**
   * Tests that concurrent login and logout operations work correctly with Virtual Threads.
   * This verifies that session management is thread-safe under high concurrency.
   */
  @Test
  public void testConcurrentLoginLogoutWithVirtualThreads() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);

    // Create a large number of Virtual Threads to perform login/logout cycles concurrently
    runConcurrently(CONCURRENT_THREADS, () -> {
      try {
        // Login
        UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
        Subject subject = securitySystem.getSubject();
        subject.login(token);
        assertTrue(subject.isAuthenticated());
        
        // Perform some operations while authenticated
        assertTrue(subject.isPermitted("test:read"));
        assertFalse(subject.isPermitted("invalid:permission"));
        
        // Logout
        subject.logout();
        assertFalse(subject.isAuthenticated());
        
        successCount.incrementAndGet();
      }
      catch (Exception e) {
        failureCount.incrementAndGet();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for all threads to complete
    assertTrue("Timed out waiting for login/logout threads", latch.await(30, TimeUnit.SECONDS));
    
    // Verify all login/logout cycles succeeded
    assertEquals("All login/logout cycles should succeed", CONCURRENT_THREADS, successCount.get());
    assertEquals("No login/logout cycles should fail", 0, failureCount.get());
  }
}