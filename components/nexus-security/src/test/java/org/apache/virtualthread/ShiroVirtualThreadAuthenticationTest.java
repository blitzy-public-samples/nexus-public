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
package org.apache.virtualthread;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.authc.event.AuthenticationEvent;
import org.apache.shiro.authc.event.AuthenticationListener;
import org.apache.shiro.authc.event.SuccessfulAuthenticationEvent;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests Apache Shiro's authentication mechanisms under Java 21 virtual threads.
 * Ensures correct behavior when authenticating users concurrently using virtual threads.
 * Validates that realm authentication, token validation, and authentication caching
 * work correctly without thread interference or pinning issues.
 */
@ExtendWith(MockitoExtension.class)
public class ShiroVirtualThreadAuthenticationTest
{
  private DefaultSecurityManager securityManager;
  
  private TestRealm testRealm;
  
  private TestAuthenticationListener authenticationListener;
  
  @Mock
  private PasswordService passwordService;
  
  @BeforeEach
  public void setUp() {
    // Create and configure the security manager
    securityManager = new DefaultSecurityManager();
    
    // Create and configure the test realm
    testRealm = new TestRealm();
    testRealm.setPasswordService(passwordService);
    securityManager.setRealm(testRealm);
    
    // Add authentication listener
    authenticationListener = new TestAuthenticationListener();
    securityManager.getEventBus().register(authenticationListener);
    
    // Enable authentication caching
    testRealm.setCachingEnabled(true);
    testRealm.setAuthenticationCachingEnabled(true);
    
    // Set up mock password service
    when(passwordService.passwordsMatch("password", "password")).thenReturn(true);
  }
  
  @AfterEach
  public void tearDown() {
    // Clear any thread bound subjects
    ThreadContext.remove();
    
    // Clean up the security manager
    if (securityManager != null) {
      securityManager.destroy();
    }
  }
  
  /**
   * Tests that a single virtual thread can authenticate successfully.
   */
  @Test
  public void testSingleVirtualThreadAuthentication() throws Exception {
    Thread.startVirtualThread(() -> {
      // Create a subject and authenticate
      Subject subject = new Subject.Builder(securityManager).buildSubject();
      subject.login(new UsernamePasswordToken("user1", "password"));
      
      // Verify authentication was successful
      assertTrue(subject.isAuthenticated());
      assertEquals("user1", subject.getPrincipal());
      
      // Verify authentication event was fired
      assertEquals(1, authenticationListener.getSuccessCount());
      
      // Logout
      subject.logout();
      assertFalse(subject.isAuthenticated());
    }).join();
  }
  
  /**
   * Tests concurrent authentication with multiple virtual threads.
   * Each thread authenticates a different user to verify thread isolation.
   */
  @Test
  public void testConcurrentVirtualThreadAuthentication() throws Exception {
    final int threadCount = 100;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final AtomicInteger successCount = new AtomicInteger(0);
    final ConcurrentHashMap<String, String> threadPrincipals = new ConcurrentHashMap<>();
    
    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int userId = i;
      Thread.startVirtualThread(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Create a unique username for this thread
          String username = "vuser" + userId;
          
          // Create a subject and authenticate
          Subject subject = new Subject.Builder(securityManager).buildSubject();
          subject.login(new UsernamePasswordToken(username, "password"));
          
          // Record the principal and thread info
          threadPrincipals.put(Thread.currentThread().toString(), (String) subject.getPrincipal());
          
          // Verify authentication was successful
          if (subject.isAuthenticated() && username.equals(subject.getPrincipal())) {
            successCount.incrementAndGet();
          }
          
          // Logout
          subject.logout();
        }
        catch (Exception e) {
          e.printStackTrace();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify all authentications were successful
    assertEquals(threadCount, successCount.get());
    assertEquals(threadCount, threadPrincipals.size());
    
    // Verify each thread had its own unique principal
    Set<String> uniquePrincipals = new HashSet<>(threadPrincipals.values());
    assertEquals(threadCount, uniquePrincipals.size());
  }
  
  /**
   * Tests authentication caching with virtual threads.
   * Verifies that authentication cache works correctly when accessed from multiple virtual threads.
   */
  @Test
  public void testAuthenticationCachingWithVirtualThreads() throws Exception {
    final int threadCount = 10;
    final int authAttemptsPerThread = 5;
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Reset authentication count in the realm
    testRealm.resetAuthenticationCount();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks for each thread
      for (int i = 0; i < threadCount; i++) {
        final String username = "cacheUser";
        
        executor.submit(() -> {
          try {
            for (int j = 0; j < authAttemptsPerThread; j++) {
              // Create a subject and authenticate
              Subject subject = new Subject.Builder(securityManager).buildSubject();
              subject.login(new UsernamePasswordToken(username, "password"));
              
              // Verify authentication was successful
              assertTrue(subject.isAuthenticated());
              assertEquals(username, subject.getPrincipal());
              
              // Logout
              subject.logout();
            }
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
    }
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify the realm was only called once per user due to caching
    // The first authentication attempt should hit the realm, subsequent attempts should use the cache
    assertEquals(1, testRealm.getAuthenticationCount());
  }
  
  /**
   * Tests that authentication events are properly published when using virtual threads.
   */
  @Test
  public void testAuthenticationEventsWithVirtualThreads() throws Exception {
    final int threadCount = 50;
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Reset the authentication listener
    authenticationListener.reset();
    
    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int userId = i;
      Thread.startVirtualThread(() -> {
        try {
          // Create a unique username for this thread
          String username = "eventUser" + userId;
          
          // Create a subject and authenticate
          Subject subject = new Subject.Builder(securityManager).buildSubject();
          subject.login(new UsernamePasswordToken(username, "password"));
          
          // Verify authentication was successful
          assertTrue(subject.isAuthenticated());
          
          // Logout
          subject.logout();
        }
        catch (Exception e) {
          e.printStackTrace();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Verify all authentication events were received
    assertEquals(threadCount, authenticationListener.getSuccessCount());
    assertEquals(threadCount, authenticationListener.getSuccessEvents().size());
    
    // Verify each event has a unique principal
    Set<String> eventPrincipals = new HashSet<>();
    for (SuccessfulAuthenticationEvent event : authenticationListener.getSuccessEvents()) {
      eventPrincipals.add((String) event.getSubject().getPrincipal());
    }
    assertEquals(threadCount, eventPrincipals.size());
  }
  
  /**
   * Test realm implementation that supports authentication and authorization.
   */
  private static class TestRealm extends AuthorizingRealm
  {
    private PasswordService passwordService;
    private AtomicInteger authenticationCount = new AtomicInteger(0);
    
    public void setPasswordService(PasswordService passwordService) {
      this.passwordService = passwordService;
    }
    
    public int getAuthenticationCount() {
      return authenticationCount.get();
    }
    
    public void resetAuthenticationCount() {
      authenticationCount.set(0);
    }
    
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
      // Increment authentication count
      authenticationCount.incrementAndGet();
      
      // Get username and password from token
      UsernamePasswordToken upToken = (UsernamePasswordToken) token;
      String username = upToken.getUsername();
      String password = new String(upToken.getPassword());
      
      // Create authentication info with the same password for simplicity
      // In a real application, you would look up the user and verify credentials
      SimplePrincipalCollection principals = new SimplePrincipalCollection(username, getName());
      return new SimpleAuthenticationInfo(principals, password);
    }
    
    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
      // Create a simple authorization info with a role and permission
      SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
      info.addRole("user");
      info.addStringPermission("read");
      return info;
    }
    
    @Override
    public boolean supports(AuthenticationToken token) {
      return token instanceof UsernamePasswordToken;
    }
  }
  
  /**
   * Authentication listener that tracks authentication events.
   */
  private static class TestAuthenticationListener implements AuthenticationListener
  {
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final List<SuccessfulAuthenticationEvent> successEvents = new CopyOnWriteArrayList<>();
    
    public void reset() {
      successCount.set(0);
      successEvents.clear();
    }
    
    public int getSuccessCount() {
      return successCount.get();
    }
    
    public List<SuccessfulAuthenticationEvent> getSuccessEvents() {
      return successEvents;
    }
    
    @Override
    public void onSuccess(AuthenticationToken token, AuthenticationInfo info, Subject subject) {
      successCount.incrementAndGet();
      successEvents.add(new SuccessfulAuthenticationEvent(subject, token, info));
    }
    
    @Override
    public void onFailure(AuthenticationToken token, AuthenticationException ae) {
      // Not tracking failures in this test
    }
    
    @Override
    public void onLogout(Subject subject) {
      // Not tracking logouts in this test
    }
  }
}