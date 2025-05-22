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
package org.apache.shiro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.session.ExpiredSessionException;
import org.apache.shiro.session.InvalidSessionException;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionKey;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.session.mgt.SessionContext;
import org.apache.shiro.session.mgt.SessionKey;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.web.servlet.ShiroHttpServletRequest;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.apache.shiro.web.session.mgt.WebSessionKey;
import org.apache.shiro.web.session.mgt.WebSessionManager;
import org.apache.shiro.web.util.WebUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for Apache Shiro 2.0.0 session management compatibility with Java 21.
 * Validates WebSessionManager implementation to ensure session creation, validation,
 * timeout handling, and persistence function correctly under Java 21.
 * 
 * @since 3.60
 */
public class ShiroSessionManagerTest
{
  private DefaultWebSessionManager sessionManager;
  
  @BeforeEach
  public void setUp() {
    sessionManager = new DefaultWebSessionManager();
    // Configure shorter timeout for testing
    sessionManager.setGlobalSessionTimeout(500);
    // Enable session validation
    sessionManager.setSessionValidationSchedulerEnabled(true);
    sessionManager.setSessionValidationInterval(250);
  }
  
  /**
   * Tests basic session creation and initialization under Java 21.
   */
  @Test
  public void testSessionCreation() {
    SessionContext context = new DefaultSessionContext();
    Session session = sessionManager.start(context);
    
    assertNotNull(session);
    assertNotNull(session.getId());
    assertFalse(session.isExpired());
    
    // Verify session timeout is set correctly
    assertEquals(500, session.getTimeout());
    
    // Verify session can be retrieved by ID
    SessionKey key = new DefaultSessionKey(session.getId());
    Session retrievedSession = sessionManager.getSession(key);
    
    assertNotNull(retrievedSession);
    assertEquals(session.getId(), retrievedSession.getId());
  }
  
  /**
   * Tests session attribute storage and retrieval.
   */
  @Test
  public void testSessionAttributes() {
    SessionContext context = new DefaultSessionContext();
    Session session = sessionManager.start(context);
    
    // Set attributes
    session.setAttribute("testKey", "testValue");
    session.setAttribute("numberValue", 42);
    
    // Retrieve and verify attributes
    assertEquals("testValue", session.getAttribute("testKey"));
    assertEquals(42, session.getAttribute("numberValue"));
    
    // Remove attribute
    session.removeAttribute("testKey");
    assertNull(session.getAttribute("testKey"));
    
    // Verify remaining attribute
    assertEquals(42, session.getAttribute("numberValue"));
  }
  
  /**
   * Tests session timeout and expiration handling.
   */
  @Test
  public void testSessionTimeout() throws InterruptedException {
    // Create session with very short timeout
    SessionContext context = new DefaultSessionContext();
    Session session = sessionManager.start(context);
    session.setTimeout(100); // 100ms timeout
    
    // Get session ID for later retrieval
    Object sessionId = session.getId();
    
    // Wait for session to expire
    Thread.sleep(150);
    
    // Try to retrieve expired session
    SessionKey key = new DefaultSessionKey(sessionId);
    try {
      sessionManager.getSession(key);
      // If we get here, the session didn't expire as expected
      assertTrue(false, "Session should have expired");
    } 
    catch (ExpiredSessionException e) {
      // Expected behavior
      assertTrue(true);
    }
  }
  
  /**
   * Tests session validation scheduler.
   */
  @Test
  public void testSessionValidation() throws InterruptedException {
    // Create a session
    SessionContext context = new DefaultSessionContext();
    Session session = sessionManager.start(context);
    
    // Set a short timeout
    session.setTimeout(200);
    Object sessionId = session.getId();
    
    // Wait for validation to occur and clean up expired sessions
    Thread.sleep(300);
    
    // Try to retrieve the session - should be expired and removed by validator
    SessionKey key = new DefaultSessionKey(sessionId);
    try {
      sessionManager.getSession(key);
      assertTrue(false, "Session should have been validated and expired");
    } 
    catch (InvalidSessionException e) {
      // Expected behavior
      assertTrue(true);
    }
  }
  
  /**
   * Tests session serialization and deserialization.
   */
  @Test
  public void testSessionSerialization() throws IOException, ClassNotFoundException {
    // Create a session with attributes
    SimpleSession session = new SimpleSession();
    session.setId("test-session-id");
    session.setAttribute("testKey", "testValue");
    session.setAttribute("numberValue", 42);
    
    // Serialize the session
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    oos.writeObject(session);
    oos.close();
    
    // Deserialize the session
    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    ObjectInputStream ois = new ObjectInputStream(bais);
    SimpleSession deserializedSession = (SimpleSession) ois.readObject();
    ois.close();
    
    // Verify session was correctly serialized and deserialized
    assertEquals("test-session-id", deserializedSession.getId());
    assertEquals("testValue", deserializedSession.getAttribute("testKey"));
    assertEquals(42, deserializedSession.getAttribute("numberValue"));
  }
  
  /**
   * Tests web-specific session operations.
   */
  @Test
  public void testWebSessionOperations() {
    // Mock HTTP request and response
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    
    // Create a session context with the request and response
    SessionContext context = new DefaultWebSessionContext();
    WebUtils.saveServletRequest(request, context);
    WebUtils.saveServletResponse(response, context);
    
    // Start a session
    Session session = sessionManager.start(context);
    assertNotNull(session);
    
    // Create a web session key with the request and response
    WebSessionKey key = new WebSessionKey(session.getId(), request, response);
    
    // Retrieve the session using the web session key
    Session retrievedSession = sessionManager.getSession(key);
    assertNotNull(retrievedSession);
    assertEquals(session.getId(), retrievedSession.getId());
  }
  
  /**
   * Tests that session operations work correctly with Virtual Threads.
   */
  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testVirtualThreadSessionOperations() throws InterruptedException {
    // Number of virtual threads to create
    final int threadCount = 100;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Create a thread-per-task executor with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to create and use sessions in virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Create a session
            SessionContext context = new DefaultSessionContext();
            Session session = sessionManager.start(context);
            
            // Set thread-specific attribute
            String key = "thread-" + threadId;
            session.setAttribute(key, "value-" + threadId);
            
            // Verify attribute was set correctly
            assertEquals("value-" + threadId, session.getAttribute(key));
            
            // Retrieve session by ID and verify it's the same session
            SessionKey sessionKey = new DefaultSessionKey(session.getId());
            Session retrievedSession = sessionManager.getSession(sessionKey);
            assertEquals(session.getId(), retrievedSession.getId());
            assertEquals("value-" + threadId, retrievedSession.getAttribute(key));
            
            // Touch the session to keep it alive
            retrievedSession.touch();
            
            // Verify last access time was updated
            assertTrue(retrievedSession.getLastAccessTime().getTime() > 0);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(4, TimeUnit.SECONDS), "Not all virtual threads completed in time");
    }
  }
  
  /**
   * Tests concurrent session access and modification with Virtual Threads.
   */
  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testConcurrentSessionAccess() throws InterruptedException {
    // Create a shared session
    SessionContext context = new DefaultSessionContext();
    Session sharedSession = sessionManager.start(context);
    final Object sessionId = sharedSession.getId();
    
    // Number of virtual threads to create
    final int threadCount = 50;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Create a thread-per-task executor with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to access and modify the shared session
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Retrieve the shared session
            SessionKey key = new DefaultSessionKey(sessionId);
            Session session = sessionManager.getSession(key);
            
            // Set thread-specific attribute
            String attrKey = "thread-" + threadId;
            session.setAttribute(attrKey, "value-" + threadId);
            
            // Small delay to increase chance of concurrent access
            Thread.sleep(10);
            
            // Verify attribute was set correctly
            assertEquals("value-" + threadId, session.getAttribute(attrKey));
            
            // Touch the session to keep it alive
            session.touch();
          } 
          catch (Exception e) {
            e.printStackTrace();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(4, TimeUnit.SECONDS), "Not all virtual threads completed in time");
      
      // Verify the session is still valid
      SessionKey key = new DefaultSessionKey(sessionId);
      Session session = sessionManager.getSession(key);
      assertNotNull(session);
      assertFalse(session.isExpired());
      
      // Verify all thread-specific attributes are present
      for (int i = 0; i < threadCount; i++) {
        String attrKey = "thread-" + i;
        assertEquals("value-" + i, session.getAttribute(attrKey));
      }
    }
  }
  
  /**
   * Tests session timeout handling with Virtual Threads.
   */
  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testVirtualThreadSessionTimeout() throws InterruptedException {
    // Create a session with a short timeout
    SessionContext context = new DefaultSessionContext();
    Session session = sessionManager.start(context);
    session.setTimeout(200); // 200ms timeout
    final Object sessionId = session.getId();
    
    // Use a virtual thread to access the session after it has expired
    Thread.startVirtualThread(() -> {
      try {
        // Wait for the session to expire
        Thread.sleep(300);
        
        // Try to access the expired session
        SessionKey key = new DefaultSessionKey(sessionId);
        try {
          sessionManager.getSession(key);
          // If we get here, the session didn't expire as expected
          assertTrue(false, "Session should have expired");
        } 
        catch (ExpiredSessionException e) {
          // Expected behavior
          assertTrue(true);
        }
      } 
      catch (Exception e) {
        e.printStackTrace();
      }
    }).join();
  }
  
  /**
   * Tests that the session manager correctly handles a high volume of sessions
   * created by Virtual Threads.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testHighVolumeSessionCreation() throws InterruptedException {
    // Number of virtual threads/sessions to create
    final int sessionCount = 1000;
    final CountDownLatch latch = new CountDownLatch(sessionCount);
    
    // Create a thread-per-task executor with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to create sessions in virtual threads
      for (int i = 0; i < sessionCount; i++) {
        executor.submit(() -> {
          try {
            // Create a session
            SessionContext context = new DefaultSessionContext();
            Session session = sessionManager.start(context);
            assertNotNull(session);
            assertNotNull(session.getId());
            
            // Set a random attribute
            String randomKey = "key-" + Math.random();
            session.setAttribute(randomKey, "value");
            assertEquals("value", session.getAttribute(randomKey));
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(8, TimeUnit.SECONDS), "Not all virtual threads completed in time");
    }
    
    // Verify the session manager is still functioning correctly
    SessionContext context = new DefaultSessionContext();
    Session session = sessionManager.start(context);
    assertNotNull(session);
    session.setAttribute("testKey", "testValue");
    assertEquals("testValue", session.getAttribute("testKey"));
  }
}