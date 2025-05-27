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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests Apache Shiro's authentication mechanisms under Java 21 virtual threads.
 * 
 * This test verifies that realm authentication, token validation, and authentication caching
 * work correctly without thread interference or pinning issues when using virtual threads.
 */
public class ShiroVirtualThreadAuthenticationTest
    extends TestSupport
{
  private static final String USERNAME = "admin";
  private static final String PASSWORD = "password";
  private static final int CONCURRENT_USERS = 100;
  private static final int AUTHENTICATION_ATTEMPTS = 5;
  
  private DefaultSecurityManager securityManager;
  private TestRealm testRealm;
  
  @Mock
  private CacheManager cacheManager;
  
  @Mock
  private Cache<Object, AuthenticationInfo> authenticationCache;
  
  @Before
  public void setUp() {
    // Set up the test realm
    testRealm = new TestRealm();
    
    // Configure cache manager and authentication cache
    when(cacheManager.getCache(anyString())).thenReturn(authenticationCache);
    testRealm.setCacheManager(cacheManager);
    testRealm.setAuthenticationCachingEnabled(true);
    
    // Set up security manager with the test realm
    securityManager = new DefaultSecurityManager(testRealm);
    ThreadContext.bind(securityManager);
  }
  
  @After
  public void tearDown() {
    ThreadContext.unbindSecurityManager();
    ThreadContext.unbindSubject();
    securityManager.destroy();
  }
  
  /**
   * Tests that basic authentication works with virtual threads.
   */
  @Test
  public void testBasicAuthenticationWithVirtualThread() throws Exception {
    Thread virtualThread = Thread.ofVirtual().name("auth-test-thread").start(() -> {
      // Create a subject and authenticate
      Subject subject = new Subject.Builder(securityManager).buildSubject();
      subject.login(new UsernamePasswordToken(USERNAME, PASSWORD));
      
      // Verify the subject is authenticated
      assertTrue(subject.isAuthenticated());
      assertEquals(USERNAME, subject.getPrincipal());
      
      // Logout
      subject.logout();
      assertFalse(subject.isAuthenticated());
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the authentication cache was used
    verify(authenticationCache).get(any());
    verify(authenticationCache).put(any(), any());
  }
  
  /**
   * Tests concurrent authentication with multiple virtual threads.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_USERS);
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicInteger failureCount = new AtomicInteger(0);
    
    // Create virtual threads for concurrent authentication
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final int userId = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a subject and authenticate
            Subject subject = new Subject.Builder(securityManager).buildSubject();
            subject.login(new UsernamePasswordToken(USERNAME + userId, PASSWORD));
            
            // Verify the subject is authenticated
            if (subject.isAuthenticated() && USERNAME.equals(subject.getPrincipal().toString().replace(String.valueOf(userId), ""))) {
              successCount.incrementAndGet();
            }
            
            // Logout
            subject.logout();
          } 
          catch (Exception e) {
            failureCount.incrementAndGet();
            log.error("Authentication failed", e);
          } 
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue("Authentication threads did not complete in time", 
          completionLatch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify all authentications were successful
    assertEquals("All authentications should succeed", CONCURRENT_USERS, successCount.get());
    assertEquals("No authentications should fail", 0, failureCount.get());
  }
  
  /**
   * Tests authentication caching with virtual threads.
   */
  @Test
  public void testAuthenticationCachingWithVirtualThreads() throws Exception {
    final CyclicBarrier barrier = new CyclicBarrier(CONCURRENT_USERS);
    final CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_USERS);
    
    // Create virtual threads for concurrent authentication of the same user
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        executor.submit(() -> {
          try {
            // Synchronize all threads to start at the same time
            barrier.await();
            
            // Perform multiple authentication attempts for the same user
            for (int attempt = 0; attempt < AUTHENTICATION_ATTEMPTS; attempt++) {
              Subject subject = new Subject.Builder(securityManager).buildSubject();
              subject.login(new UsernamePasswordToken(USERNAME, PASSWORD));
              assertTrue(subject.isAuthenticated());
              subject.logout();
            }
          } 
          catch (Exception e) {
            log.error("Authentication failed", e);
            fail("Authentication should not fail: " + e.getMessage());
          } 
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue("Authentication threads did not complete in time", 
          completionLatch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify the authentication cache was used
    // The cache should be accessed at least once for the get operation
    verify(authenticationCache, times(1)).get(any());
    // The cache should be accessed exactly once for the put operation (first authentication)
    verify(authenticationCache, times(1)).put(any(), any());
  }
  
  /**
   * Tests failed authentication with virtual threads.
   */
  @Test
  public void testFailedAuthenticationWithVirtualThreads() throws Exception {
    final CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_USERS);
    final AtomicInteger correctFailureCount = new AtomicInteger(0);
    
    // Configure the realm to fail authentication
    testRealm.setShouldFailAuthentication(true);
    
    // Create virtual threads for concurrent authentication
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        executor.submit(() -> {
          try {
            Subject subject = new Subject.Builder(securityManager).buildSubject();
            subject.login(new UsernamePasswordToken(USERNAME, "wrong_password"));
            fail("Authentication should have failed");
          } 
          catch (AuthenticationException e) {
            // This is the expected outcome
            correctFailureCount.incrementAndGet();
          } 
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue("Authentication threads did not complete in time", 
          completionLatch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify all authentications failed as expected
    assertEquals("All authentications should fail", CONCURRENT_USERS, correctFailureCount.get());
  }
  
  /**
   * Tests authentication with thread-local state in virtual threads.
   */
  @Test
  public void testThreadLocalStateWithVirtualThreads() throws Exception {
    final CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_USERS);
    final AtomicInteger successCount = new AtomicInteger(0);
    
    // Create virtual threads for concurrent authentication
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final int userId = i;
        executor.submit(() -> {
          try {
            // Set thread-local state
            ThreadContext.put("userId", "user-" + userId);
            
            // Create a subject and authenticate
            Subject subject = new Subject.Builder(securityManager).buildSubject();
            subject.login(new UsernamePasswordToken(USERNAME, PASSWORD));
            
            // Verify thread-local state is preserved
            String userIdFromContext = (String) ThreadContext.get("userId");
            if (userIdFromContext != null && userIdFromContext.equals("user-" + userId)) {
              successCount.incrementAndGet();
            }
            
            // Logout and clean up
            subject.logout();
            ThreadContext.remove("userId");
          } 
          catch (Exception e) {
            log.error("Authentication failed", e);
          } 
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue("Authentication threads did not complete in time", 
          completionLatch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify thread-local state was preserved for all threads
    assertEquals("Thread-local state should be preserved in all virtual threads", 
        CONCURRENT_USERS, successCount.get());
  }
  
  /**
   * A test realm implementation for authentication testing.
   */
  private static class TestRealm extends AuthenticatingRealm {
    private boolean shouldFailAuthentication = false;
    
    public TestRealm() {
      setCredentialsMatcher(new CredentialsMatcher() {
        @Override
        public boolean doCredentialsMatch(AuthenticationToken token, AuthenticationInfo info) {
          return !shouldFailAuthentication;
        }
      });
    }
    
    public void setShouldFailAuthentication(boolean shouldFail) {
      this.shouldFailAuthentication = shouldFail;
    }
    
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
      if (shouldFailAuthentication) {
        return null;
      }
      
      UsernamePasswordToken upToken = (UsernamePasswordToken) token;
      return new SimpleAuthenticationInfo(upToken.getUsername(), upToken.getPassword(), getName());
    }
  }
}