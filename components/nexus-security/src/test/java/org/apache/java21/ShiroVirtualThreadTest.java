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
package org.apache.java21;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.apache.shiro.util.ThreadContext;
import org.apache.shiro.util.ThreadState;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests Apache Shiro 2.0.0 compatibility with Java 21 Virtual Threads.
 * 
 * This test verifies that Shiro's authentication, authorization, and session management
 * work correctly when executed within Virtual Threads. It tests thread contextual security
 * information propagation between platform and virtual threads, ensuring security contexts
 * remain properly associated with the correct execution paths.
 */
public class ShiroVirtualThreadTest
    extends TestSupport
{
  private static final String USERNAME = "testuser";
  private static final String PASSWORD = "password";
  private static final String ROLE = "testrole";
  private static final String PERMISSION = "test:permission";
  
  private DefaultSecurityManager securityManager;
  private SimpleAccountRealm realm;
  
  @Before
  public void setUp() {
    // Set up a simple Shiro environment with a test user
    realm = new SimpleAccountRealm();
    realm.addAccount(USERNAME, PASSWORD, ROLE);
    realm.setPermissionResolver((permissionString, account) -> {
      if (PERMISSION.equals(permissionString)) {
        return true;
      }
      return false;
    });
    
    securityManager = new DefaultSecurityManager(realm);
    SecurityUtils.setSecurityManager(securityManager);
  }
  
  @After
  public void tearDown() {
    // Clean up the security manager and thread context
    ThreadContext.remove();
    SecurityUtils.setSecurityManager(null);
  }
  
  /**
   * Tests basic authentication and authorization in a virtual thread.
   * Verifies that a subject can be created, authenticated, and permissions checked
   * within a virtual thread context.
   */
  @Test
  public void testBasicAuthenticationInVirtualThread() throws Exception {
    AtomicBoolean success = new AtomicBoolean(false);
    
    Thread virtualThread = Thread.ofVirtual().name("auth-test-thread").start(() -> {
      try {
        // Create and authenticate a subject
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
        subject.login(token);
        
        // Verify authentication and authorization
        assertTrue("Subject should be authenticated", subject.isAuthenticated());
        assertTrue("Subject should have role", subject.hasRole(ROLE));
        assertTrue("Subject should have permission", subject.isPermitted(PERMISSION));
        
        // Create a session and store some data
        Session session = subject.getSession();
        session.setAttribute("testKey", "testValue");
        
        // Verify session data
        assertEquals("testValue", session.getAttribute("testKey"));
        
        success.set(true);
      }
      catch (Exception e) {
        log.error("Error in virtual thread test", e);
      }
    });
    
    virtualThread.join();
    assertTrue("Authentication test in virtual thread should succeed", success.get());
  }
  
  /**
   * Tests that ThreadLocal-based security context is properly maintained across
   * virtual thread boundaries.
   */
  @Test
  public void testThreadLocalContextPropagation() throws Exception {
    // Authenticate in the main thread
    Subject mainSubject = SecurityUtils.getSubject();
    UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
    mainSubject.login(token);
    
    String sessionId = mainSubject.getSession().getId().toString();
    
    // Create a thread state to bind the subject to the current thread
    ThreadState threadState = new SubjectThreadState(mainSubject);
    threadState.bind();
    
    AtomicReference<String> virtualThreadSessionId = new AtomicReference<>();
    
    try {
      // Launch a virtual thread and verify the security context is not automatically propagated
      Thread virtualThread = Thread.ofVirtual().name("context-test-thread").start(() -> {
        Subject virtualSubject = SecurityUtils.getSubject();
        // A new subject should be created in the virtual thread
        assertFalse("Virtual thread should have a different subject", virtualSubject.isAuthenticated());
        
        // Now explicitly propagate the subject to this virtual thread
        ThreadState vtThreadState = new SubjectThreadState(mainSubject);
        vtThreadState.bind();
        
        try {
          // Now we should have the same subject
          Subject boundSubject = SecurityUtils.getSubject();
          assertTrue("Bound subject should be authenticated", boundSubject.isAuthenticated());
          virtualThreadSessionId.set(boundSubject.getSession().getId().toString());
        }
        finally {
          vtThreadState.restore();
        }
      });
      
      virtualThread.join();
      
      // Verify the session ID was the same, indicating the subject was properly propagated
      assertThat(virtualThreadSessionId.get(), is(equalTo(sessionId)));
    }
    finally {
      threadState.restore();
    }
  }
  
  /**
   * Tests concurrent authentication operations using multiple virtual threads.
   * Verifies that Shiro can handle multiple concurrent authentication requests
   * from different virtual threads without issues.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean allSucceeded = new AtomicBoolean(true);
    
    List<Thread> virtualThreads = new ArrayList<>();
    
    // Create multiple virtual threads that will authenticate concurrently
    for (int i = 0; i < threadCount; i++) {
      Thread vt = Thread.ofVirtual().name("concurrent-auth-" + i).start(() -> {
        try {
          // Wait for all threads to start at the same time
          startLatch.await();
          
          // Perform authentication
          Subject subject = SecurityUtils.getSubject();
          UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
          subject.login(token);
          
          // Verify authentication succeeded
          if (!subject.isAuthenticated() || !subject.hasRole(ROLE)) {
            allSucceeded.set(false);
          }
          
          // Create and use a session
          Session session = subject.getSession();
          session.setAttribute("testKey", "testValue");
          if (!"testValue".equals(session.getAttribute("testKey"))) {
            allSucceeded.set(false);
          }
          
          // Logout
          subject.logout();
        }
        catch (Exception e) {
          log.error("Error in concurrent authentication test", e);
          allSucceeded.set(false);
        }
        finally {
          completionLatch.countDown();
        }
      });
      
      virtualThreads.add(vt);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("All threads should complete in time", 
        completionLatch.await(30, TimeUnit.SECONDS));
    
    // Verify all authentications succeeded
    assertTrue("All concurrent authentications should succeed", allSucceeded.get());
  }
  
  /**
   * Tests serialization and deserialization of Shiro security objects in virtual thread contexts.
   * Verifies that Shiro's serializable objects can be properly serialized and deserialized
   * when used within virtual threads.
   */
  @Test
  public void testSerializationInVirtualThreads() throws Exception {
    // Create a serializable test object to store in the session
    SerializableTestObject testObject = new SerializableTestObject("test-data");
    
    AtomicReference<byte[]> serializedSession = new AtomicReference<>();
    AtomicReference<String> sessionId = new AtomicReference<>();
    
    // First virtual thread creates and serializes a session
    Thread vtCreate = Thread.ofVirtual().name("serialize-thread").start(() -> {
      try {
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
        subject.login(token);
        
        Session session = subject.getSession();
        session.setAttribute("testObject", testObject);
        
        // Store the session ID for later verification
        sessionId.set(session.getId().toString());
        
        // Serialize the session
        if (session instanceof Serializable) {
          serializedSession.set(org.apache.shiro.util.SerializationUtils.serialize((Serializable) session));
        }
      }
      catch (Exception e) {
        log.error("Error in serialization test", e);
      }
    });
    
    vtCreate.join();
    assertThat("Session should be serialized", serializedSession.get(), is(notNullValue()));
    
    AtomicBoolean deserializationSucceeded = new AtomicBoolean(false);
    
    // Second virtual thread deserializes and verifies the session
    Thread vtDeserialize = Thread.ofVirtual().name("deserialize-thread").start(() -> {
      try {
        // Deserialize the session
        Session deserializedSession = org.apache.shiro.util.SerializationUtils.deserialize(serializedSession.get());
        
        // Verify the session ID and stored object
        assertThat(deserializedSession.getId().toString(), is(equalTo(sessionId.get())));
        
        SerializableTestObject retrievedObject = 
            (SerializableTestObject) deserializedSession.getAttribute("testObject");
        assertThat(retrievedObject.getData(), is(equalTo("test-data")));
        
        deserializationSucceeded.set(true);
      }
      catch (Exception e) {
        log.error("Error in deserialization test", e);
      }
    });
    
    vtDeserialize.join();
    assertTrue("Deserialization in virtual thread should succeed", deserializationSucceeded.get());
  }
  
  /**
   * Tests using Shiro with the Java 21 ExecutorService.newVirtualThreadPerTaskExecutor().
   * Verifies that Shiro works correctly when used with the new virtual thread executor.
   */
  @Test
  public void testShiroWithVirtualThreadExecutor() throws Exception {
    int taskCount = 50;
    List<Future<Boolean>> results = new ArrayList<>();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple authentication tasks
      for (int i = 0; i < taskCount; i++) {
        results.add(executor.submit(() -> {
          try {
            // Perform authentication
            Subject subject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD);
            subject.login(token);
            
            // Verify authentication and authorization
            boolean authenticated = subject.isAuthenticated();
            boolean hasRole = subject.hasRole(ROLE);
            boolean hasPermission = subject.isPermitted(PERMISSION);
            
            // Create and use a session
            Session session = subject.getSession();
            session.setAttribute("testKey", "testValue");
            boolean sessionWorks = "testValue".equals(session.getAttribute("testKey"));
            
            // Logout
            subject.logout();
            
            return authenticated && hasRole && hasPermission && sessionWorks;
          }
          catch (Exception e) {
            log.error("Error in virtual thread executor test", e);
            return false;
          }
        }));
      }
      
      // Verify all tasks completed successfully
      for (Future<Boolean> result : results) {
        assertTrue("Task should complete successfully", result.get());
      }
    }
  }
  
  /**
   * Helper method to assert equality with a descriptive message.
   */
  private static void assertEquals(Object expected, Object actual) {
    assertThat(actual, is(equalTo(expected)));
  }
  
  /**
   * A simple serializable test object for session storage tests.
   */
  private static class SerializableTestObject implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String data;
    
    public SerializableTestObject(String data) {
      this.data = data;
    }
    
    public String getData() {
      return data;
    }
  }
}