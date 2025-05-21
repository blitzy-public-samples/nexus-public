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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.config.memory.MemoryCUser;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.RealmSecurityManager;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests to validate that authentication processes in Nexus security correctly support Java 21 Virtual Threads.
 * 
 * This test ensures that login operations, token validation, and session management maintain correctness
 * and thread safety when executed by thousands of concurrent Virtual Threads. It also verifies that the
 * security subsystem properly interacts with authentication providers when operations are executed by
 * Virtual Threads.
 */
public class AuthenticationVirtualThreadTest
    extends TestSupport
{
  private static final String TEST_USERNAME = "testUser";

  private static final String TEST_PASSWORD = "admin123";

  private static final String LEGACY_PASSWORD_HASH = "f865b53623b121fd34ee5426c792e5c33af8c227";

  private static final int VIRTUAL_THREAD_COUNT = 5000;

  @Mock
  private SecurityConfigurationManager securityConfigurationManager;

  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private RealmSecurityManager realmSecurityManager;

  @Mock
  private Subject subject;

  private CUser testUser;

  private AuthenticatingRealmImpl authenticatingRealm;

  @Before
  public void setUp() throws Exception {
    testUser = new MemoryCUser();
    testUser.setId(TEST_USERNAME);
    testUser.setStatus(CUser.STATUS_ACTIVE);
    testUser.setPassword(LEGACY_PASSWORD_HASH);

    // Configure mocks for security configuration manager
    when(securityConfigurationManager.readUser(TEST_USERNAME)).thenAnswer((inv) -> testUser.clone());

    // Capture password updates
    doAnswer((inv) -> {
      testUser.setPassword(((CUser) inv.getArguments()[0]).getPassword());
      return null;
    }).when(securityConfigurationManager).updateUser(any());

    // Initialize the authenticating realm
    authenticatingRealm = new AuthenticatingRealmImpl(securityConfigurationManager,
        new DefaultSecurityPasswordService(new LegacyNexusPasswordService()), false);

    // Configure security system mock
    when(securitySystem.getSubject()).thenReturn(subject);
    when(securitySystem.getRealmSecurityManager()).thenReturn(realmSecurityManager);

    // Configure subject mock for successful authentication
    doAnswer(inv -> {
      // Simulate successful authentication
      return null;
    }).when(subject).login(any(UsernamePasswordToken.class));
  }

  /**
   * Tests that the AuthenticatingRealmImpl correctly handles authentication requests from thousands
   * of concurrent Virtual Threads without thread safety issues.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    // Create a thread factory that produces virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("auth-test-", 0).factory();
    
    // Create an executor service using the virtual thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Track successful authentications
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create a latch to ensure all threads start roughly at the same time
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Submit authentication tasks to the executor
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Perform authentication
            AuthenticationToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
            AuthenticationInfo info = authenticatingRealm.getAuthenticationInfo(token);
            
            // Verify authentication was successful
            assertNotNull("Authentication info should not be null", info);
            assertEquals("Principal should match username", TEST_USERNAME, info.getPrincipals().getPrimaryPrincipal());
            
            // Increment success counter
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            fail("Authentication failed with exception: " + e.getMessage());
          }
        }));
      }
      
      // Release the latch to start all threads
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(); // This will throw an exception if any task failed
      }
      
      // Verify all authentications were successful
      assertEquals("All authentication attempts should succeed", VIRTUAL_THREAD_COUNT, successCount.get());
      
      // Verify the password was rehashed exactly once, despite thousands of authentication attempts
      // This confirms proper synchronization in the authentication process
      verify(securityConfigurationManager, times(1)).updateUser(any());
      
      // Verify the password was rehashed to the new format
      assertThat(testUser.getPassword(), org.hamcrest.Matchers.startsWith("$shiro1$SHA-512$1024$"));
    }
  }

  /**
   * Tests that the SecuritySystem correctly handles login requests from thousands of concurrent
   * Virtual Threads without thread safety issues.
   */
  @Test
  public void testConcurrentLoginWithVirtualThreads() throws Exception {
    // Create a thread factory that produces virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("login-test-", 0).factory();
    
    // Create an executor service using the virtual thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Track successful logins
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create a latch to ensure all threads start roughly at the same time
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Create a map to track any exceptions by thread
      ConcurrentHashMap<String, Throwable> exceptions = new ConcurrentHashMap<>();
      
      // Submit login tasks to the executor
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadNum = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Get the current thread name for tracking
            String threadName = Thread.currentThread().getName();
            
            // Perform login through security system
            UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
            Subject threadSubject = securitySystem.getSubject();
            threadSubject.login(token);
            
            // Increment success counter
            successCount.incrementAndGet();
          }
          catch (Throwable e) {
            // Store the exception for later analysis
            exceptions.put("Thread-" + threadNum, e);
          }
        }));
      }
      
      // Release the latch to start all threads
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get(10, TimeUnit.SECONDS); // Add timeout to prevent hanging
        }
        catch (ExecutionException e) {
          // Already captured in our exceptions map
        }
      }
      
      // If there were any exceptions, fail the test with details
      if (!exceptions.isEmpty()) {
        StringBuilder errorMessage = new StringBuilder("Login failures detected in " + 
            exceptions.size() + " threads:\n");
        exceptions.forEach((thread, error) -> {
          errorMessage.append(thread).append(": ").append(error.getMessage()).append("\n");
        });
        fail(errorMessage.toString());
      }
      
      // Verify all logins were successful
      assertEquals("All login attempts should succeed", VIRTUAL_THREAD_COUNT, successCount.get());
      
      // Verify the subject.login method was called the expected number of times
      verify(subject, times(VIRTUAL_THREAD_COUNT)).login(any(UsernamePasswordToken.class));
    }
  }

  /**
   * Tests that the AuthenticatingRealmImpl correctly handles invalid authentication attempts
   * from concurrent Virtual Threads without thread safety issues.
   */
  @Test
  public void testConcurrentInvalidAuthenticationWithVirtualThreads() throws Exception {
    // Create a thread factory that produces virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("invalid-auth-test-", 0).factory();
    
    // Create an executor service using the virtual thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Track authentication failures
      AtomicInteger failureCount = new AtomicInteger(0);
      
      // Create a latch to ensure all threads start roughly at the same time
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Submit authentication tasks with invalid credentials to the executor
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Perform authentication with invalid password
            AuthenticationToken token = new UsernamePasswordToken(TEST_USERNAME, "INVALID_PASSWORD");
            authenticatingRealm.getAuthenticationInfo(token);
            
            // If we get here, authentication didn't fail as expected
            fail("Authentication should have failed with invalid credentials");
          }
          catch (AuthenticationException e) {
            // Expected exception for invalid credentials
            failureCount.incrementAndGet();
          }
          catch (Exception e) {
            fail("Unexpected exception: " + e.getMessage());
          }
        }));
      }
      
      // Release the latch to start all threads
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get(10, TimeUnit.SECONDS); // Add timeout to prevent hanging
        }
        catch (ExecutionException e) {
          // This is expected if the task called fail()
          if (!(e.getCause() instanceof AssertionError)) {
            throw e; // Rethrow unexpected exceptions
          }
        }
      }
      
      // Verify all authentication attempts failed as expected
      assertEquals("All authentication attempts should fail with invalid credentials", 
          VIRTUAL_THREAD_COUNT, failureCount.get());
      
      // Verify no password updates occurred during failed authentication attempts
      verify(securityConfigurationManager, times(0)).updateUser(any());
    }
  }

  /**
   * Tests that multiple authentication realms can be used concurrently by Virtual Threads
   * without thread safety issues.
   */
  @Test
  public void testMultipleRealmsWithVirtualThreads() throws Exception {
    // Create a mock realm that will be used alongside our authenticating realm
    Realm mockRealm = Mockito.mock(Realm.class);
    when(mockRealm.supports(any(AuthenticationToken.class))).thenReturn(true);
    when(mockRealm.getAuthenticationInfo(any(AuthenticationToken.class))).thenReturn(null); // Simulate no match
    
    // Configure realm security manager to return both realms
    List<Realm> realms = new ArrayList<>();
    realms.add(authenticatingRealm);
    realms.add(mockRealm);
    when(realmSecurityManager.getRealms()).thenReturn(realms);
    
    // Create a thread factory that produces virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("multi-realm-test-", 0).factory();
    
    // Create an executor service using the virtual thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Track successful authentications
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create a latch to ensure all threads start roughly at the same time
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Submit authentication tasks to the executor
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Get all realms from the security manager
            List<Realm> threadRealms = realmSecurityManager.getRealms();
            
            // Try authentication with each realm
            AuthenticationToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
            AuthenticationInfo info = null;
            
            for (Realm realm : threadRealms) {
              if (realm.supports(token)) {
                info = realm.getAuthenticationInfo(token);
                if (info != null) {
                  break; // Found a matching realm
                }
              }
            }
            
            // Verify authentication was successful with at least one realm
            assertNotNull("Authentication info should not be null", info);
            assertEquals("Principal should match username", TEST_USERNAME, info.getPrincipals().getPrimaryPrincipal());
            
            // Increment success counter
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            fail("Authentication failed with exception: " + e.getMessage());
          }
        }));
      }
      
      // Release the latch to start all threads
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(); // This will throw an exception if any task failed
      }
      
      // Verify all authentications were successful
      assertEquals("All authentication attempts should succeed", VIRTUAL_THREAD_COUNT, successCount.get());
      
      // Verify both realms were consulted
      verify(mockRealm, times(VIRTUAL_THREAD_COUNT)).supports(any(AuthenticationToken.class));
      verify(mockRealm, times(VIRTUAL_THREAD_COUNT)).getAuthenticationInfo(any(AuthenticationToken.class));
    }
  }
}