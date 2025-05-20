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
package org.apache.shiro.java21;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.apache.shiro.util.ThreadContext;
import org.apache.shiro.util.ThreadState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;

/**
 * Tests Apache Shiro compatibility with Java 21 Virtual Threads.
 * 
 * Validates that Shiro's security operations (authentication, authorization, and session management)
 * work correctly when executed concurrently using virtual threads.
 * 
 * Verifies that thread-local security contexts are properly maintained across virtual thread boundaries
 * and that no thread pinning occurs during security operations.
 */
@ExtendWith(MockitoExtension.class)
@Tag("Java21TestGroup")
@Tag("VirtualThreadTestGroup")
public class VirtualThreadShiroIntegrationTest
{
  private static final String USERNAME = "testuser";
  private static final String PASSWORD = "password";
  private static final String ROLE = "testrole";
  private static final String PERMISSION = "test:permission";
  
  private DefaultSecurityManager securityManager;
  private SimpleAccountRealm realm;
  private ThreadFactory virtualThreadFactory;
  private ExecutorService virtualThreadExecutor;
  private ThreadPinningDetector pinningDetector;

  @BeforeEach
  public void setUp() {
    // Set up Shiro security manager and realm
    realm = new SimpleAccountRealm();
    realm.addAccount(USERNAME, PASSWORD, ROLE);
    securityManager = new DefaultSecurityManager(realm);
    SecurityUtils.setSecurityManager(securityManager);
    
    // Create virtual thread factory and executor
    virtualThreadFactory = Thread.ofVirtual().name("shiro-test-", 0).factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Initialize thread pinning detector
    pinningDetector = new ThreadPinningDetector();
  }

  @AfterEach
  public void tearDown() {
    // Clean up thread context and shutdown executor
    ThreadContext.remove();
    SecurityUtils.setSecurityManager(null);
    virtualThreadExecutor.shutdown();
    try {
      if (!virtualThreadExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
        virtualThreadExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      virtualThreadExecutor.shutdownNow();
    }
  }

  /**
   * Tests that basic authentication works correctly in a virtual thread.
   */
  @Test
  public void testAuthenticationInVirtualThread() throws Exception {
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
      try {
        subject.login(token);
        return subject.isAuthenticated();
      } catch (AuthenticationException e) {
        return false;
      } finally {
        subject.logout();
      }
    }, virtualThreadExecutor);

    assertTrue(future.get(), "Authentication should succeed in virtual thread");
  }

  /**
   * Tests that authorization checks work correctly in a virtual thread.
   */
  @Test
  public void testAuthorizationInVirtualThread() throws Exception {
    // Add permission to the user
    realm.setPermissionResolver(permission -> permission);
    realm.addRole(ROLE, PERMISSION);

    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
      try {
        subject.login(token);
        boolean hasRole = subject.hasRole(ROLE);
        boolean isPermitted = subject.isPermitted(PERMISSION);
        return hasRole && isPermitted;
      } catch (AuthenticationException e) {
        return false;
      } finally {
        subject.logout();
      }
    }, virtualThreadExecutor);

    assertTrue(future.get(), "Authorization should work in virtual thread");
  }

  /**
   * Tests that session management works correctly in a virtual thread.
   */
  @Test
  public void testSessionManagementInVirtualThread() throws Exception {
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      Subject subject = SecurityUtils.getSubject();
      Session session = subject.getSession();
      String attributeKey = "testAttribute";
      String attributeValue = "testValue";
      session.setAttribute(attributeKey, attributeValue);
      return attributeValue.equals(session.getAttribute(attributeKey));
    }, virtualThreadExecutor);

    assertTrue(future.get(), "Session management should work in virtual thread");
  }

  /**
   * Tests that thread-local security contexts are properly maintained across virtual thread boundaries.
   */
  @Test
  public void testThreadLocalContextPropagation() throws Exception {
    // Create and bind a subject to the main thread
    Subject mainThreadSubject = new Subject.Builder(securityManager).buildSubject();
    ThreadState threadState = new SubjectThreadState(mainThreadSubject);
    threadState.bind();
    
    // Verify the subject is bound to the main thread
    Subject retrievedSubject = SecurityUtils.getSubject();
    assertEquals(mainThreadSubject, retrievedSubject, "Subject should be bound to main thread");
    
    // Test that a new virtual thread gets its own thread-local context
    CompletableFuture<Subject> virtualThreadSubjectFuture = CompletableFuture.supplyAsync(() -> {
      return SecurityUtils.getSubject();
    }, virtualThreadExecutor);
    
    Subject virtualThreadSubject = virtualThreadSubjectFuture.get();
    assertNotNull(virtualThreadSubject, "Virtual thread should have a subject");
    assertFalse(mainThreadSubject.equals(virtualThreadSubject), 
        "Virtual thread should have a different subject than main thread");
    
    // Clean up
    threadState.restore();
  }

  /**
   * Tests that multiple concurrent virtual threads can perform Shiro operations correctly.
   */
  @Test
  public void testConcurrentVirtualThreads() throws Exception {
    int threadCount = 100;
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    
    for (int i = 0; i < threadCount; i++) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
        try {
          subject.login(token);
          boolean authenticated = subject.isAuthenticated();
          boolean hasRole = subject.hasRole(ROLE);
          Session session = subject.getSession();
          session.setAttribute("testKey", "testValue");
          boolean sessionWorks = "testValue".equals(session.getAttribute("testKey"));
          return authenticated && hasRole && sessionWorks;
        } catch (Exception e) {
          return false;
        } finally {
          subject.logout();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all futures to complete and verify results
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    allFutures.get(5, TimeUnit.SECONDS); // Add timeout to prevent test hanging
    
    for (CompletableFuture<Boolean> future : futures) {
      assertTrue(future.get(), "All Shiro operations should succeed in concurrent virtual threads");
    }
  }

  /**
   * Tests that Shiro operations don't cause thread pinning issues.
   */
  @Test
  public void testNoPinningDuringShiroOperations() throws Exception {
    // Start pinning detection
    pinningDetector.startDetection();
    
    try {
      // Perform Shiro operations in a virtual thread
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
        try {
          // Perform various Shiro operations that might cause pinning
          subject.login(token);
          subject.hasRole(ROLE);
          Session session = subject.getSession();
          session.setAttribute("testKey", "testValue");
          session.getAttribute("testKey");
          subject.logout();
        } catch (Exception e) {
          throw new RuntimeException("Shiro operation failed", e);
        }
      }, virtualThreadExecutor);
      
      future.get(5, TimeUnit.SECONDS); // Add timeout to prevent test hanging
      
      // Check if any pinning was detected
      Collection<String> pinningEvents = pinningDetector.getPinningEvents();
      assertTrue(pinningEvents.isEmpty(), 
          "No thread pinning should occur during Shiro operations: " + pinningEvents);
      
    } finally {
      pinningDetector.stopDetection();
    }
  }

  /**
   * Tests that Shiro's SecurityManager can be accessed from a virtual thread.
   */
  @Test
  public void testSecurityManagerAccessInVirtualThread() throws Exception {
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      return SecurityUtils.getSecurityManager() != null;
    }, virtualThreadExecutor);

    assertTrue(future.get(), "SecurityManager should be accessible from virtual thread");
  }

  /**
   * Tests that a Subject created in one virtual thread can be propagated to another virtual thread.
   */
  @Test
  public void testSubjectPropagationBetweenVirtualThreads() throws Exception {
    // Create a subject in the first virtual thread
    AtomicReference<Subject> subjectRef = new AtomicReference<>();
    
    CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
      subject.login(token);
      subjectRef.set(subject);
    }, virtualThreadExecutor);
    
    future1.get(); // Wait for the first thread to complete
    
    // Use the subject in a second virtual thread
    CompletableFuture<Boolean> future2 = CompletableFuture.supplyAsync(() -> {
      Subject subject = subjectRef.get();
      ThreadState threadState = new SubjectThreadState(subject);
      threadState.bind();
      try {
        return SecurityUtils.getSubject().isAuthenticated();
      } finally {
        threadState.restore();
      }
    }, virtualThreadExecutor);
    
    assertTrue(future2.get(), "Subject should be propagated between virtual threads");
  }

  /**
   * Tests that Shiro's thread context is properly isolated between virtual threads.
   */
  @Test
  public void testThreadContextIsolation() throws Exception {
    // Set a value in the thread context of the main thread
    String key = "testKey";
    String value = "testValue";
    ThreadContext.put(key, value);
    
    // Check that the value is not visible in a virtual thread
    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
      return ThreadContext.get(key);
    }, virtualThreadExecutor);
    
    assertThat(future.get(), is(null));
    
    // Clean up
    ThreadContext.remove(key);
  }
}