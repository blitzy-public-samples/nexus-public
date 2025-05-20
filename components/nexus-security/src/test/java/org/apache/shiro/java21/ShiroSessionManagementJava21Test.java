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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionKey;
import org.apache.shiro.session.mgt.SessionContext;
import org.apache.shiro.session.mgt.SessionKey;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.apache.shiro.web.servlet.ShiroHttpServletRequest;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.apache.shiro.web.session.mgt.WebSessionManager;
import org.apache.shiro.web.util.WebUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
  @Mock
  private SessionDAO sessionDAO;

  @Mock
  private HttpServletRequest servletRequest;

  @Mock
  private HttpServletResponse servletResponse;

  private DefaultWebSessionManager sessionManager;

  @BeforeEach
  public void setUp() {
    sessionManager = new DefaultWebSessionManager();
    sessionManager.setSessionDAO(sessionDAO);
    sessionManager.setGlobalSessionTimeout(500); // Short timeout for testing expiration
  }

  /**
   * Tests that the WebSessionManager can create and retrieve sessions correctly when accessed
   * concurrently by multiple Virtual Threads.
   */
  @Test
  @DisplayName("Test concurrent session creation with Virtual Threads")
  public void testConcurrentSessionCreationWithVirtualThreads() throws Exception {
    // Set up a mock session that will be returned by the DAO
    SimpleSession mockSession = new SimpleSession();
    String sessionId = UUID.randomUUID().toString();
    mockSession.setId(sessionId);
    when(sessionDAO.create(any(Session.class))).thenReturn(sessionId);
    when(sessionDAO.read(sessionId)).thenReturn(mockSession);

    // Set up the HTTP request to return our session ID
    when(servletRequest.getRequestURI()).thenReturn("/test");
    when(servletRequest.getContextPath()).thenReturn("");
    ShiroHttpServletRequest shiroRequest = new ShiroHttpServletRequest(servletRequest, servletResponse, null);
    when(servletRequest.getAttribute(WebUtils.SERVLET_REQUEST_KEY)).thenReturn(shiroRequest);

    // Number of concurrent threads to test with
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    // Use Virtual Threads for concurrent session access
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a session
            Session session = sessionManager.start(servletRequest, servletResponse);
            assertNotNull(session);
            assertEquals(sessionId, session.getId());
            
            // Retrieve the session
            SessionKey key = new DefaultSessionKey(sessionId);
            Session retrievedSession = sessionManager.getSession(key);
            assertNotNull(retrievedSession);
            assertEquals(sessionId, retrievedSession.getId());
            
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            // Log any exceptions
            System.err.println("Error in virtual thread: " + e.getMessage());
            e.printStackTrace();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete (with timeout)
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      
      // Verify all threads succeeded
      assertEquals(threadCount, successCount.get(), "Not all threads completed successfully");
      
      // Verify the session was created and read the expected number of times
      verify(sessionDAO, times(threadCount)).create(any(Session.class));
      verify(sessionDAO, times(threadCount)).read(sessionId);
    }
  }

  /**
   * Tests that session persistence operations work correctly with Java 21's I/O improvements
   * when accessed by Virtual Threads.
   */
  @Test
  @DisplayName("Test session persistence with Virtual Threads")
  public void testSessionPersistenceWithVirtualThreads() throws Exception {
    // Create a custom SessionDAO implementation that simulates I/O operations
    SessionDAO ioSessionDAO = new SimulatedIOSessionDAO();
    sessionManager.setSessionDAO(ioSessionDAO);

    // Number of concurrent threads to test with
    int threadCount = 50;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    // Use Virtual Threads for concurrent session persistence operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a session context
            SessionContext context = new SessionContext() {
              @Override
              public Map<String, Object> getAttributes() {
                return new ConcurrentHashMap<>();
              }
            };
            
            // Start a new session
            Session session = sessionManager.start(context);
            assertNotNull(session);
            String sessionId = (String) session.getId();
            
            // Set some attributes
            session.setAttribute("testKey" + index, "testValue" + index);
            
            // Get the session again to verify persistence
            SessionKey key = new DefaultSessionKey(sessionId);
            Session retrievedSession = sessionManager.getSession(key);
            assertNotNull(retrievedSession);
            assertEquals("testValue" + index, retrievedSession.getAttribute("testKey" + index));
            
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            System.err.println("Error in virtual thread: " + e.getMessage());
            e.printStackTrace();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete (with timeout)
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      
      // Verify all threads succeeded
      assertEquals(threadCount, successCount.get(), "Not all threads completed successfully");
    }
  }

  /**
   * Tests that session expiration and validation work correctly with concurrent access patterns
   * using Virtual Threads.
   */
  @Test
  @DisplayName("Test session expiration with Virtual Threads")
  public void testSessionExpirationWithVirtualThreads() throws Exception {
    // Create a session with a very short timeout
    sessionManager.setGlobalSessionTimeout(100); // 100ms timeout
    
    // Create a session
    SimpleSession session = new SimpleSession();
    String sessionId = UUID.randomUUID().toString();
    session.setId(sessionId);
    when(sessionDAO.create(any(Session.class))).thenReturn(sessionId);
    when(sessionDAO.read(sessionId)).thenReturn(session);

    // Start the session
    Session startedSession = sessionManager.start(servletRequest, servletResponse);
    assertNotNull(startedSession);
    assertEquals(sessionId, startedSession.getId());

    // Number of concurrent threads to test with
    int threadCount = 20;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger expiredSessionsCount = new AtomicInteger(0);

    // Use Virtual Threads to concurrently check session expiration
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Wait for the session to expire
            Thread.sleep(150); // Wait longer than the timeout
            
            // Now the session should be expired
            // Simulate session validation by updating the last access time
            session.touch();
            session.validate();
            
            // This should not be reached if the session is properly expired
            System.err.println("Session validation did not throw expiration exception as expected");
          }
          catch (Exception e) {
            // We expect an exception due to session expiration
            if (e.getMessage() != null && e.getMessage().contains("expired")) {
              expiredSessionsCount.incrementAndGet();
            }
            else {
              System.err.println("Unexpected error: " + e.getMessage());
              e.printStackTrace();
            }
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete (with timeout)
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      
      // Verify all threads detected the expired session
      assertEquals(threadCount, expiredSessionsCount.get(), 
          "Not all threads detected the expired session");
    }
  }
  
  /**
   * Tests that session validation works correctly with concurrent access patterns
   * using Virtual Threads. This test specifically focuses on the session validation
   * mechanism that Shiro uses to clean up expired sessions.
   */
  @Test
  @DisplayName("Test concurrent session validation with Virtual Threads")
  public void testConcurrentSessionValidationWithVirtualThreads() throws Exception {
    // Create a custom SessionDAO that tracks validation calls
    AtomicInteger validationCount = new AtomicInteger(0);
    SessionDAO validationSessionDAO = mock(SessionDAO.class);
    
    // Set up mock sessions with different expiration states
    SimpleSession validSession1 = new SimpleSession();
    validSession1.setId("valid1");
    
    SimpleSession validSession2 = new SimpleSession();
    validSession2.setId("valid2");
    
    SimpleSession expiredSession = new SimpleSession();
    expiredSession.setId("expired");
    expiredSession.setLastAccessTime(new Date(System.currentTimeMillis() - 600000)); // 10 minutes ago
    
    // Configure the mock SessionDAO
    Collection<Session> activeSessions = List.of(validSession1, validSession2, expiredSession);
    when(validationSessionDAO.getActiveSessions()).thenReturn(activeSessions);
    
    // Configure the session manager with our test SessionDAO
    sessionManager.setSessionDAO(validationSessionDAO);
    sessionManager.setSessionValidationInterval(100); // Short interval for testing
    
    // Enable session validation
    sessionManager.setSessionValidationSchedulerEnabled(true);
    
    // Wait a bit for the validation to occur
    Thread.sleep(200);
    
    // Disable validation scheduler to prevent further runs
    sessionManager.setSessionValidationSchedulerEnabled(false);
    
    // Verify that the expired session was deleted
    verify(validationSessionDAO, times(1)).delete(any(Session.class));
    
    // Verify that the active sessions were retrieved
    verify(validationSessionDAO, times(1)).getActiveSessions();
  }

  /**
   * Tests that multiple concurrent session operations (create, read, update, delete)
   * can be performed efficiently using Virtual Threads without thread contention issues.
   */
  @Test
  @DisplayName("Test mixed session operations with Virtual Threads")
  public void testMixedSessionOperationsWithVirtualThreads() throws Exception {
    // Create a custom SessionDAO that tracks operation counts
    SimulatedIOSessionDAO ioSessionDAO = new SimulatedIOSessionDAO();
    sessionManager.setSessionDAO(ioSessionDAO);
    
    // Number of concurrent threads to test with
    int threadCount = 50;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Use Virtual Threads for concurrent mixed session operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a session context
            SessionContext context = new SessionContext() {
              @Override
              public Map<String, Object> getAttributes() {
                return new ConcurrentHashMap<>();
              }
            };
            
            // Perform different operations based on thread index
            if (index % 4 == 0) {
              // Create a new session
              Session session = sessionManager.start(context);
              assertNotNull(session);
            }
            else if (index % 4 == 1) {
              // Create and then read a session
              Session session = sessionManager.start(context);
              assertNotNull(session);
              String sessionId = (String) session.getId();
              
              SessionKey key = new DefaultSessionKey(sessionId);
              Session retrievedSession = sessionManager.getSession(key);
              assertNotNull(retrievedSession);
              assertEquals(sessionId, retrievedSession.getId());
            }
            else if (index % 4 == 2) {
              // Create, update, and read a session
              Session session = sessionManager.start(context);
              assertNotNull(session);
              String sessionId = (String) session.getId();
              
              session.setAttribute("testKey", "testValue" + index);
              
              SessionKey key = new DefaultSessionKey(sessionId);
              Session retrievedSession = sessionManager.getSession(key);
              assertNotNull(retrievedSession);
              assertEquals("testValue" + index, retrievedSession.getAttribute("testKey"));
            }
            else {
              // Create and then delete a session
              Session session = sessionManager.start(context);
              assertNotNull(session);
              
              // Stop the session (which should delete it)
              sessionManager.stop(session);
              
              // Verify the session was deleted by checking the DAO directly
              assertFalse(ioSessionDAO.sessionExists((Serializable) session.getId()));
            }
            
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            System.err.println("Error in virtual thread: " + e.getMessage());
            e.printStackTrace();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete (with timeout)
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      
      // Verify all threads succeeded
      assertEquals(threadCount, successCount.get(), "Not all threads completed successfully");
      
      // Verify that operations were performed
      assertTrue(ioSessionDAO.getCreateCount() > 0, "No sessions were created");
      assertTrue(ioSessionDAO.getReadCount() > 0, "No sessions were read");
      assertTrue(ioSessionDAO.getUpdateCount() > 0, "No sessions were updated");
      assertTrue(ioSessionDAO.getDeleteCount() > 0, "No sessions were deleted");
    }
  }

  /**
   * A custom SessionDAO implementation that simulates I/O operations with delays
   * to test how Virtual Threads handle I/O-bound session persistence operations.
   * Also tracks operation counts for verification.
   */
  private static class SimulatedIOSessionDAO implements SessionDAO {
    private final Map<Serializable, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger createCount = new AtomicInteger(0);
    private final AtomicInteger readCount = new AtomicInteger(0);
    private final AtomicInteger updateCount = new AtomicInteger(0);
    private final AtomicInteger deleteCount = new AtomicInteger(0);
    
    @Override
    public Serializable create(Session session) {
      // Simulate I/O delay for session creation
      simulateIODelay();
      
      Serializable sessionId = UUID.randomUUID().toString();
      session.setId(sessionId);
      sessions.put(sessionId, session);
      createCount.incrementAndGet();
      return sessionId;
    }

    @Override
    public Session read(Serializable sessionId) {
      // Simulate I/O delay for session retrieval
      simulateIODelay();
      
      readCount.incrementAndGet();
      return sessions.get(sessionId);
    }

    @Override
    public void update(Session session) {
      // Simulate I/O delay for session update
      simulateIODelay();
      
      sessions.put(session.getId(), session);
      updateCount.incrementAndGet();
    }

    @Override
    public void delete(Session session) {
      // Simulate I/O delay for session deletion
      simulateIODelay();
      
      sessions.remove(session.getId());
      deleteCount.incrementAndGet();
    }

    @Override
    public Collection<Session> getActiveSessions() {
      // Simulate I/O delay for retrieving active sessions
      simulateIODelay();
      
      return sessions.values();
    }
    
    /**
     * Checks if a session exists in the DAO.
     */
    public boolean sessionExists(Serializable sessionId) {
      return sessions.containsKey(sessionId);
    }
    
    /**
     * Gets the count of session creation operations.
     */
    public int getCreateCount() {
      return createCount.get();
    }
    
    /**
     * Gets the count of session read operations.
     */
    public int getReadCount() {
      return readCount.get();
    }
    
    /**
     * Gets the count of session update operations.
     */
    public int getUpdateCount() {
      return updateCount.get();
    }
    
    /**
     * Gets the count of session delete operations.
     */
    public int getDeleteCount() {
      return deleteCount.get();
    }
    
    /**
     * Simulates an I/O delay that would typically block a platform thread
     * but should not block the carrier thread when using Virtual Threads.
     */
    private void simulateIODelay() {
      try {
        // Sleep to simulate I/O delay (e.g., database or network operation)
        // Virtual Threads should handle this efficiently without blocking carrier threads
        Thread.sleep(Duration.ofMillis(50));
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}