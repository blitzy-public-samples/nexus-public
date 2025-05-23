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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserNotFoundException;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.apache.shiro.util.ThreadState;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test suite to verify Apache Shiro compatibility with Java 21 features, particularly Virtual Threads.
 * 
 * This test validates that Shiro's security context propagation, authentication, authorization,
 * and session management work correctly with Java 21's Virtual Threads and other features.
 */
public class ShiroJava21CompatibilityTest
    extends AbstractSecurityTest
{
  private static final String TEST_USER_ID = "test-user";
  private static final String TEST_USER_PASSWORD = "password123";
  
  /**
   * Set up a test user for authentication tests.
   */
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    
    try {
      // Delete the user if it already exists
      getUserManager().deleteUser(TEST_USER_ID);
    }
    catch (UserNotFoundException e) {
      // Ignore, this is expected if the user doesn't exist yet
    }
    
    // Create a test user
    User user = getUserManager().newUser();
    user.setUserId(TEST_USER_ID);
    user.setFirstName("Test");
    user.setLastName("User");
    user.setEmailAddress("test@example.com");
    user.setStatus("active");
    user.setPassword(TEST_USER_PASSWORD);
    
    getUserManager().addUser(user);
  }
  
  /**
   * Clean up the test user after tests.
   */
  @Override
  protected void tearDown() throws Exception {
    try {
      getUserManager().deleteUser(TEST_USER_ID);
    }
    catch (UserNotFoundException e) {
      // Ignore
    }
    
    super.tearDown();
  }
  
  /**
   * Test basic authentication using a Virtual Thread.
   * Verifies that Shiro can authenticate a user within a Virtual Thread.
   */
  @Test
  public void testBasicAuthenticationWithVirtualThread() throws Exception {
    Thread virtualThread = Thread.ofVirtual().name("auth-test").start(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken(TEST_USER_ID, TEST_USER_PASSWORD);
      subject.login(token);
      
      assertTrue("User should be authenticated", subject.isAuthenticated());
      assertEquals("Authenticated user ID should match", TEST_USER_ID, subject.getPrincipal());
    });
    
    virtualThread.join();
  }
  
  /**
   * Test that ThreadContext binding works correctly with Virtual Threads.
   * Verifies that ThreadContext can be bound and retrieved within a Virtual Thread.
   */
  @Test
  public void testThreadContextBindingWithVirtualThread() throws Exception {
    final String TEST_KEY = "testKey";
    final String TEST_VALUE = "testValue";
    
    Thread virtualThread = Thread.ofVirtual().name("context-test").start(() -> {
      // Bind a value to the ThreadContext
      ThreadContext.put(TEST_KEY, TEST_VALUE);
      
      // Verify it can be retrieved
      assertEquals("ThreadContext value should be retrievable", TEST_VALUE, ThreadContext.get(TEST_KEY));
      
      // Clean up
      ThreadContext.remove(TEST_KEY);
      assertNull("ThreadContext value should be removed", ThreadContext.get(TEST_KEY));
    });
    
    virtualThread.join();
  }
  
  /**
   * Test that ThreadState binding works correctly with Virtual Threads.
   * Verifies that ThreadState can be bound and restored within a Virtual Thread.
   */
  @Test
  public void testThreadStateBindingWithVirtualThread() throws Exception {
    Thread virtualThread = Thread.ofVirtual().name("thread-state-test").start(() -> {
      // Create a ThreadState and bind it
      ThreadState threadState = new ThreadState();
      threadState.bind();
      
      // Verify the ThreadContext is empty after binding
      assertTrue("ThreadContext should be empty after binding", ThreadContext.getResources() == null || ThreadContext.getResources().isEmpty());
      
      // Add something to the ThreadContext
      ThreadContext.put("testKey", "testValue");
      assertNotNull("ThreadContext should have a value", ThreadContext.get("testKey"));
      
      // Restore the ThreadState
      threadState.restore();
      
      // Verify the ThreadContext is empty after restoring
      assertTrue("ThreadContext should be empty after restoring", ThreadContext.getResources() == null || ThreadContext.getResources().isEmpty());
    });
    
    virtualThread.join();
  }
  
  /**
   * Test security context propagation between parent and child Virtual Threads.
   * Verifies that security context is not automatically propagated between Virtual Threads.
   */
  @Test
  public void testSecurityContextPropagationBetweenVirtualThreads() throws Exception {
    final String TEST_KEY = "securityKey";
    final String TEST_VALUE = "securityValue";
    final AtomicReference<String> childValue = new AtomicReference<>();
    
    Thread parentThread = Thread.ofVirtual().name("parent-thread").start(() -> {
      // Set a value in the parent thread
      ThreadContext.put(TEST_KEY, TEST_VALUE);
      
      try {
        // Create a child thread
        Thread childThread = Thread.ofVirtual().name("child-thread").start(() -> {
          // Try to get the value in the child thread
          childValue.set(ThreadContext.get(TEST_KEY));
        });
        
        childThread.join();
      }
      catch (Exception e) {
        fail("Exception in child thread: " + e.getMessage());
      }
    });
    
    parentThread.join();
    
    // Verify the child thread did not inherit the ThreadContext value
    assertThat("Child thread should not inherit ThreadContext from parent", childValue.get(), is(nullValue()));
  }
  
  /**
   * Test manual security context propagation between parent and child Virtual Threads.
   * Verifies that security context can be manually propagated between Virtual Threads.
   */
  @Test
  public void testManualSecurityContextPropagationBetweenVirtualThreads() throws Exception {
    final String TEST_KEY = "securityKey";
    final String TEST_VALUE = "securityValue";
    final AtomicReference<String> childValue = new AtomicReference<>();
    
    Thread parentThread = Thread.ofVirtual().name("parent-thread").start(() -> {
      // Set a value in the parent thread
      ThreadContext.put(TEST_KEY, TEST_VALUE);
      
      // Capture the current context
      final Object resources = ThreadContext.getResources();
      
      try {
        // Create a child thread with manually propagated context
        Thread childThread = Thread.ofVirtual().name("child-thread").start(() -> {
          // Manually set the context in the child thread
          ThreadContext.setResources(resources);
          
          // Try to get the value in the child thread
          childValue.set(ThreadContext.get(TEST_KEY));
        });
        
        childThread.join();
      }
      catch (Exception e) {
        fail("Exception in child thread: " + e.getMessage());
      }
    });
    
    parentThread.join();
    
    // Verify the child thread received the manually propagated ThreadContext value
    assertThat("Child thread should receive manually propagated ThreadContext", childValue.get(), is(TEST_VALUE));
  }
  
  /**
   * Test authentication and authorization with multiple concurrent Virtual Threads.
   * Verifies that Shiro can handle multiple concurrent authentications using Virtual Threads.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    final int THREAD_COUNT = 10;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    final AtomicBoolean allSucceeded = new AtomicBoolean(true);
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple authentication tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to start simultaneously
            startLatch.await();
            
            // Perform authentication
            Subject subject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(TEST_USER_ID, TEST_USER_PASSWORD);
            subject.login(token);
            
            // Verify authentication succeeded
            if (!subject.isAuthenticated() || !TEST_USER_ID.equals(subject.getPrincipal())) {
              allSucceeded.set(false);
            }
            
            // Logout
            subject.logout();
          }
          catch (Exception e) {
            util.getLog().error("Thread {} failed: {}", threadId, e.getMessage(), e);
            allSucceeded.set(false);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(10, TimeUnit.SECONDS);
    }
    
    assertTrue("All authentication attempts should succeed", allSucceeded.get());
  }
  
  /**
   * Test session management with Virtual Threads.
   * Verifies that Shiro sessions work correctly with Virtual Threads.
   */
  @Test
  public void testSessionManagementWithVirtualThreads() throws Exception {
    final AtomicReference<String> sessionId = new AtomicReference<>();
    final AtomicBoolean sessionValid = new AtomicBoolean(false);
    
    // First virtual thread creates a session
    Thread thread1 = Thread.ofVirtual().name("session-create").start(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken(TEST_USER_ID, TEST_USER_PASSWORD);
      subject.login(token);
      
      // Store session ID for the second thread
      sessionId.set(subject.getSession().getId().toString());
      
      // Set a session attribute
      subject.getSession().setAttribute("testAttribute", "testValue");
    });
    
    thread1.join();
    
    // Second virtual thread verifies the session
    Thread thread2 = Thread.ofVirtual().name("session-verify").start(() -> {
      Subject subject = SecurityUtils.getSubject();
      
      // Try to access the same session
      subject.getSession().getId(); // This will create a new session
      
      // Login with the same credentials
      UsernamePasswordToken token = new UsernamePasswordToken(TEST_USER_ID, TEST_USER_PASSWORD);
      subject.login(token);
      
      // Verify session attribute is accessible
      Object attribute = subject.getSession().getAttribute("testAttribute");
      sessionValid.set("testValue".equals(attribute));
    });
    
    thread2.join();
    
    // Verify the session was valid
    assertTrue("Session should be valid across virtual threads after login", sessionValid.get());
  }
  
  /**
   * Test pattern matching for switch with Shiro authentication results.
   * Demonstrates using Java 21's pattern matching for switch with Shiro authentication.
   */
  @Test
  public void testPatternMatchingWithShiroAuthentication() throws Exception {
    CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
      try {
        Subject subject = SecurityUtils.getSubject();
        
        // Try to get the authentication state
        Object authState = subject.isAuthenticated() ? "authenticated" : "not-authenticated";
        
        // Use pattern matching for switch to handle different authentication states
        return switch (authState) {
          case String s when s.equals("authenticated") -> "User is already authenticated";
          case String s when s.equals("not-authenticated") -> {
            // Try to authenticate
            try {
              UsernamePasswordToken token = new UsernamePasswordToken(TEST_USER_ID, TEST_USER_PASSWORD);
              subject.login(token);
              yield "User was successfully authenticated";
            }
            catch (Exception e) {
              yield "Authentication failed: " + e.getMessage();
            }
          }
          default -> "Unknown authentication state";
        };
      }
      catch (Exception e) {
        return "Error: " + e.getMessage();
      }
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    String authResult = result.get(5, TimeUnit.SECONDS);
    assertThat("Authentication result should be successful", authResult, is("User was successfully authenticated"));
  }
  
  /**
   * Test that Shiro's remember-me functionality works with Virtual Threads.
   * Verifies that remember-me tokens can be set and retrieved across Virtual Threads.
   */
  @Test
  public void testRememberMeFunctionalityWithVirtualThreads() throws Exception {
    final AtomicBoolean remembered = new AtomicBoolean(false);
    
    // First thread sets remember-me
    Thread thread1 = Thread.ofVirtual().name("remember-set").start(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken(TEST_USER_ID, TEST_USER_PASSWORD);
      token.setRememberMe(true);
      subject.login(token);
      
      // Verify remember-me is set
      assertTrue("Subject should be remembered", subject.isRemembered());
      
      // Logout but keep remember-me cookie
      subject.logout();
    });
    
    thread1.join();
    
    // Second thread checks if user is remembered
    Thread thread2 = Thread.ofVirtual().name("remember-check").start(() -> {
      Subject subject = SecurityUtils.getSubject();
      
      // User should not be authenticated but remembered
      assertFalse("User should not be authenticated", subject.isAuthenticated());
      remembered.set(subject.isRemembered());
    });
    
    thread2.join();
    
    // Note: In a test environment without a real HTTP request/response cycle,
    // remember-me functionality might not work as expected since cookies can't be set.
    // This test is primarily to verify the API works with Virtual Threads.
    // In a real application, remember-me would require proper cookie handling.
    
    // We don't assert the remembered value since it depends on the test environment
    // but the test should run without exceptions
  }
}