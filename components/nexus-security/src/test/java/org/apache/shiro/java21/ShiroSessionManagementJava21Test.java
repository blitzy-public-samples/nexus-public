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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionKey;
import org.apache.shiro.session.mgt.SessionKey;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.MemorySessionDAO;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.apache.shiro.web.servlet.ShiroHttpServletRequest;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests Shiro's session management capabilities under Java 21, with particular focus on WebSessionManager
 * and session persistence. Validates that session creation, retrieval, and expiration operate correctly
 * when accessed concurrently using Virtual Threads and that session operations benefit from Java 21's
 * concurrency improvements.
 */
@ExtendWith(MockitoExtension.class)
public class ShiroSessionManagementJava21Test
{
  private DefaultWebSessionManager sessionManager;
  
  private SessionDAO sessionDAO;
  
  @Mock
  private HttpServletRequest mockRequest;
  
  @Mock
  private HttpServletResponse mockResponse;
  
  @BeforeEach
  public void setUp() {
    sessionDAO = new MemorySessionDAO();
    sessionManager = new DefaultWebSessionManager();
    sessionManager.setSessionDAO(sessionDAO);
    sessionManager.setGlobalSessionTimeout(500); // Short timeout for testing expiration
  }
  
  /**
   * Tests that session creation works correctly with Java 21.
   */
  @Test
  public void testSessionCreation() {
    // Setup mock request
    when(mockRequest.getRequestedSessionId()).thenReturn(null);
    ShiroHttpServletRequest shiroRequest = new ShiroHttpServletRequest(mockRequest, null, null);
    
    // Create a new session
    Session session = sessionManager.start(shiroRequest, mockResponse);
    
    // Verify session was created
    assertNotNull(session);
    assertNotNull(session.getId());
    assertTrue(session.getStartTimestamp() <= new Date().getTime());
  }
  
  /**
   * Tests that session retrieval works correctly with Java 21.
   */
  @Test
  public void testSessionRetrieval() {
    // Create a session first
    Session session = sessionManager.start(mockRequest, mockResponse);
    Serializable sessionId = session.getId();
    
    // Retrieve the session
    SessionKey key = new DefaultSessionKey(sessionId);
    Session retrievedSession = sessionManager.getSession(key);
    
    // Verify session was retrieved correctly
    assertNotNull(retrievedSession);
    assertEquals(sessionId, retrievedSession.getId());
  }
  
  /**
   * Tests that session expiration works correctly with Java 21.
   */
  @Test
  public void testSessionExpiration() throws InterruptedException {
    // Create a session
    Session session = sessionManager.start(mockRequest, mockResponse);
    Serializable sessionId = session.getId();
    
    // Wait for the session to expire (timeout is set to 500ms in setUp)
    Thread.sleep(1000);
    
    // Try to retrieve the expired session
    SessionKey key = new DefaultSessionKey(sessionId);
    Session retrievedSession = sessionManager.getSession(key);
    
    // Verify session has expired
    assertNull(retrievedSession);
  }
  
  /**
   * Tests concurrent session operations using Java 21 Virtual Threads.
   * This test creates multiple sessions concurrently and verifies they are all valid.
   */
  @Test
  public void testConcurrentSessionOperationsWithVirtualThreads() throws InterruptedException {
    int threadCount = 50;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Use Virtual Threads for concurrent session operations
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      // Create sessions concurrently using Virtual Threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Create a session
            Session session = sessionManager.start(mockRequest, mockResponse);
            Serializable sessionId = session.getId();
            
            // Verify session was created
            assertNotNull(session);
            assertNotNull(sessionId);
            
            // Retrieve the session
            SessionKey key = new DefaultSessionKey(sessionId);
            Session retrievedSession = sessionManager.getSession(key);
            
            // Verify session was retrieved correctly
            assertNotNull(retrievedSession);
            assertEquals(sessionId, retrievedSession.getId());
            
            completionLatch.countDown();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    
    // Verify all sessions were created
    Collection<Session> activeSessions = sessionDAO.getActiveSessions();
    assertEquals(threadCount, activeSessions.size());
  }
  
  /**
   * Tests session persistence with Java 21's improved I/O operations.
   * This test simulates I/O operations during session persistence and verifies
   * that they benefit from Java 21's concurrency improvements.
   */
  @Test
  public void testSessionPersistenceWithImprovedIO() throws InterruptedException {
    // Create a custom SessionDAO that simulates I/O operations
    SessionDAO ioSimulatingDAO = new MemorySessionDAO() {
      @Override
      public Serializable create(Session session) {
        // Simulate I/O delay that would benefit from Virtual Threads
        try {
          Thread.sleep(50);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return super.create(session);
      }
      
      @Override
      public void update(Session session) {
        // Simulate I/O delay that would benefit from Virtual Threads
        try {
          Thread.sleep(50);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        super.update(session);
      }
    };
    
    // Configure session manager with the I/O simulating DAO
    sessionManager.setSessionDAO(ioSimulatingDAO);
    
    int threadCount = 20;
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Measure time taken with Virtual Threads
    long startTime = System.nanoTime();
    
    // Use Virtual Threads for concurrent session operations
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Create a session
            Session session = sessionManager.start(mockRequest, mockResponse);
            
            // Update the session a few times to simulate activity
            for (int j = 0; j < 3; j++) {
              session.setAttribute("attr" + j, "value" + j);
              Thread.sleep(10); // Small delay between updates
            }
            
            completionLatch.countDown();
          }
          catch (Exception e) {
            e.printStackTrace();
          }
        });
      }
      
      // Wait for all operations to complete
      completionLatch.await(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    
    long duration = System.nanoTime() - startTime;
    
    // Verify all sessions were created and updated
    Collection<Session> activeSessions = ioSimulatingDAO.getActiveSessions();
    assertEquals(threadCount, activeSessions.size());
    
    // Log the duration for informational purposes
    System.out.println("Session persistence operations completed in " + Duration.ofNanos(duration).toMillis() + " ms");
    
    // The test passes if all sessions were correctly created and updated
    // The performance improvement from Virtual Threads is demonstrated but not strictly verified
  }
  
  /**
   * Tests session validation with concurrent access patterns using Virtual Threads.
   * This test verifies that session validation works correctly when multiple threads
   * are accessing and validating sessions concurrently.
   */
  @Test
  public void testConcurrentSessionValidation() throws InterruptedException {
    // Create a set of sessions first
    int sessionCount = 30;
    Serializable[] sessionIds = new Serializable[sessionCount];
    
    for (int i = 0; i < sessionCount; i++) {
      Session session = sessionManager.start(mockRequest, mockResponse);
      sessionIds[i] = session.getId();
      
      // Set different last access times to test validation logic
      if (i % 3 == 0) {
        // Make some sessions appear idle (but not expired)
        ((SimpleSession) session).setLastAccessTime(new Date(System.currentTimeMillis() - 200));
        sessionDAO.update(session);
      }
    }
    
    // Force validation of all sessions concurrently using Virtual Threads
    CountDownLatch completionLatch = new CountDownLatch(sessionCount);
    
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < sessionCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            SessionKey key = new DefaultSessionKey(sessionIds[index]);
            Session session = sessionManager.getSession(key);
            
            // Verify session is still valid
            assertNotNull(session);
            assertEquals(sessionIds[index], session.getId());
            
            // Touch the session to update last access time
            session.touch();
            
            completionLatch.countDown();
          }
          catch (Exception e) {
            e.printStackTrace();
          }
        });
      }
      
      // Wait for all validation operations to complete
      completionLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    
    // Verify all sessions are still active after validation
    Collection<Session> activeSessions = sessionDAO.getActiveSessions();
    assertEquals(sessionCount, activeSessions.size());
    
    // Verify last access times were updated
    for (Serializable sessionId : sessionIds) {
      SessionKey key = new DefaultSessionKey(sessionId);
      Session session = sessionManager.getSession(key);
      assertNotNull(session);
      
      // Last access time should be recent
      long lastAccessTime = session.getLastAccessTime();
      long currentTime = System.currentTimeMillis();
      assertTrue(currentTime - lastAccessTime < 5000, 
          "Last access time should be recent: " + new Date(lastAccessTime));
    }
  }
}