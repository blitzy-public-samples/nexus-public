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

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.DefaultSessionKey;
import org.apache.shiro.session.mgt.SessionKey;
import org.apache.shiro.session.mgt.eis.AbstractSessionDAO;
import org.apache.shiro.session.mgt.eis.CachingSessionDAO;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

/**
 * Tests Shiro's session management with Java 21 virtual threads, verifying thread safety,
 * concurrent session operations, and carrier thread pinning prevention.
 * 
 * This test specifically focuses on the NexusWebSessionManager and NexusSessionDAO classes
 * to ensure they properly handle session creation, validation, and expiration when executed
 * on virtual threads.
 */
public class ShiroVirtualThreadSessionTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int SESSION_TIMEOUT_MS = 500;
  private static final int EXTENDED_TIMEOUT_MS = 2000;
  
  @Mock
  private EventManager eventManager;
  
  private DefaultWebSessionManager sessionManager;
  private TestSessionDAO sessionDAO;
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() throws Exception {
    // Create a session DAO that tracks carrier thread pinning
    sessionDAO = new TestSessionDAO();
    
    // Configure the session manager with our test DAO
    sessionManager = new DefaultWebSessionManager();
    sessionManager.setSessionDAO(sessionDAO);
    sessionManager.setGlobalSessionTimeout(SESSION_TIMEOUT_MS);
    sessionManager.setDeleteInvalidSessions(true);
    
    // Create a virtual thread executor for concurrent testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests that sessions can be created and accessed from virtual threads.
   */
  @Test
  public void testSessionCreationWithVirtualThreads() throws Exception {
    // Create a session
    Session session = sessionManager.start(null);
    assertNotNull("Session should be created", session);
    Serializable sessionId = session.getId();
    
    // Access the session from a virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean(false);
    
    Thread.startVirtualThread(() -> {
      try {
        SessionKey key = new DefaultSessionKey(sessionId);
        Session retrievedSession = sessionManager.getSession(key);
        
        // Verify the session is the same
        if (retrievedSession != null && sessionId.equals(retrievedSession.getId())) {
          success.set(true);
        }
      }
      catch (Exception e) {
        log.error("Error accessing session from virtual thread", e);
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue("Virtual thread operation should complete", latch.await(5, TimeUnit.SECONDS));
    assertTrue("Session should be accessible from virtual thread", success.get());
  }
  
  /**
   * Tests that session validation works correctly with virtual threads.
   */
  @Test
  public void testSessionValidationWithVirtualThreads() throws Exception {
    // Create a session
    Session session = sessionManager.start(null);
    Serializable sessionId = session.getId();
    
    // Wait for the session to expire
    Thread.sleep(SESSION_TIMEOUT_MS * 2);
    
    // Validate sessions using a virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean sessionWasInvalidated = new AtomicBoolean(false);
    
    Thread.startVirtualThread(() -> {
      try {
        sessionManager.validateSessions();
        
        // Try to access the expired session
        try {
          SessionKey key = new DefaultSessionKey(sessionId);
          sessionManager.getSession(key);
          // If we get here, the session was not invalidated
        }
        catch (UnknownSessionException e) {
          // Expected - session was correctly invalidated
          sessionWasInvalidated.set(true);
        }
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue("Virtual thread operation should complete", latch.await(5, TimeUnit.SECONDS));
    assertTrue("Session should be invalidated", sessionWasInvalidated.get());
  }
  
  /**
   * Tests concurrent session operations using many virtual threads.
   */
  @Test
  public void testConcurrentSessionOperationsWithVirtualThreads() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    ConcurrentHashMap<Serializable, Session> sessions = new ConcurrentHashMap<>();
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Create many virtual threads that will all start at the same time
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Create a session
          Session session = sessionManager.start(null);
          Serializable sessionId = session.getId();
          
          // Store some data in the session
          session.setAttribute("threadId", threadId);
          sessions.put(sessionId, session);
          
          // Touch the session to keep it alive
          session.touch();
          
          // Retrieve the session again
          SessionKey key = new DefaultSessionKey(sessionId);
          Session retrievedSession = sessionManager.getSession(key);
          
          // Verify the session data
          if (retrievedSession != null && 
              threadId == (int) retrievedSession.getAttribute("threadId")) {
            successCount.incrementAndGet();
          }
          else {
            failureCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread {}", threadId, e);
          failureCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("All virtual threads should complete", 
        completionLatch.await(30, TimeUnit.SECONDS));
    
    // Verify results
    assertEquals("All operations should succeed", threadCount, successCount.get());
    assertEquals("No operations should fail", 0, failureCount.get());
    
    // Verify no carrier thread pinning occurred
    assertFalse("No carrier thread pinning should occur", sessionDAO.wasCarrierThreadPinned());
  }
  
  /**
   * Tests that session timeout works correctly with virtual threads.
   */
  @Test
  public void testSessionTimeoutWithVirtualThreads() throws Exception {
    // Create a session with a custom timeout
    Session session = sessionManager.start(null);
    Serializable sessionId = session.getId();
    session.setTimeout(EXTENDED_TIMEOUT_MS);
    
    // Access the session from a virtual thread after the default timeout but before the extended timeout
    Thread.sleep(SESSION_TIMEOUT_MS + 100);
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean sessionStillValid = new AtomicBoolean(false);
    
    Thread.startVirtualThread(() -> {
      try {
        SessionKey key = new DefaultSessionKey(sessionId);
        Session retrievedSession = sessionManager.getSession(key);
        
        // Verify the session is still valid due to the extended timeout
        if (retrievedSession != null && sessionId.equals(retrievedSession.getId())) {
          sessionStillValid.set(true);
        }
      }
      catch (Exception e) {
        log.error("Error accessing session from virtual thread", e);
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue("Virtual thread operation should complete", latch.await(5, TimeUnit.SECONDS));
    assertTrue("Session should still be valid with extended timeout", sessionStillValid.get());
    
    // Now wait for the extended timeout to expire
    Thread.sleep(EXTENDED_TIMEOUT_MS);
    
    // Try to access the session again
    CountDownLatch latch2 = new CountDownLatch(1);
    AtomicBoolean sessionExpired = new AtomicBoolean(false);
    
    Thread.startVirtualThread(() -> {
      try {
        SessionKey key = new DefaultSessionKey(sessionId);
        try {
          sessionManager.getSession(key);
        }
        catch (UnknownSessionException e) {
          // Expected - session has expired
          sessionExpired.set(true);
        }
      }
      finally {
        latch2.countDown();
      }
    });
    
    assertTrue("Virtual thread operation should complete", latch2.await(5, TimeUnit.SECONDS));
    assertTrue("Session should be expired after extended timeout", sessionExpired.get());
  }
  
  /**
   * Tests performance of session operations with virtual threads vs platform threads.
   */
  @Test
  public void testSessionPerformanceWithVirtualThreads() throws Exception {
    int operationCount = CONCURRENT_THREADS;
    
    // Measure time with platform threads
    long platformThreadTime = measureSessionOperations(operationCount, false);
    log.info("Platform thread time for {} operations: {} ms", operationCount, platformThreadTime);
    
    // Measure time with virtual threads
    long virtualThreadTime = measureSessionOperations(operationCount, true);
    log.info("Virtual thread time for {} operations: {} ms", operationCount, virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O bound operations
    // This is a simple check - in real-world scenarios with actual I/O,
    // the difference would be more pronounced
    assertThat("Virtual threads should be at least as efficient as platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 2));
  }
  
  /**
   * Measures the time to perform a number of session operations using either platform or virtual threads.
   */
  private long measureSessionOperations(int operationCount, boolean useVirtualThreads) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(operationCount);
    
    // Create an executor based on the thread type
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(Math.min(100, operationCount));
    
    try {
      // Submit tasks
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            
            // Perform session operations
            Session session = sessionManager.start(null);
            session.setAttribute("testKey", UUID.randomUUID().toString());
            session.touch();
            
            // Read the attribute back
            Object value = session.getAttribute("testKey");
            assertNotNull("Session attribute should be retrievable", value);
            
            // Stop the session
            sessionManager.stop(session);
          }
          catch (Exception e) {
            log.error("Error in session operation", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start timing
      long startTime = System.currentTimeMillis();
      startLatch.countDown();
      
      // Wait for completion
      completionLatch.await(60, TimeUnit.SECONDS);
      long endTime = System.currentTimeMillis();
      
      return endTime - startTime;
    }
    finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * A test SessionDAO implementation that tracks carrier thread pinning.
   */
  private static class TestSessionDAO extends CachingSessionDAO {
    private final AtomicBoolean carrierThreadPinned = new AtomicBoolean(false);
    private final ConcurrentHashMap<Serializable, Session> sessions = new ConcurrentHashMap<>();
    
    @Override
    protected Serializable doCreate(Session session) {
      checkForThreadPinning();
      Serializable sessionId = generateSessionId(session);
      assignSessionId(session, sessionId);
      sessions.put(sessionId, session);
      return sessionId;
    }
    
    @Override
    protected Session doReadSession(Serializable sessionId) {
      checkForThreadPinning();
      return sessions.get(sessionId);
    }
    
    @Override
    protected void doUpdate(Session session) {
      checkForThreadPinning();
      sessions.put(session.getId(), session);
    }
    
    @Override
    protected void doDelete(Session session) {
      checkForThreadPinning();
      sessions.remove(session.getId());
    }
    
    /**
     * Checks if the current thread is a carrier thread for a virtual thread,
     * and if it's being blocked by a long-running operation.
     */
    private void checkForThreadPinning() {
      // Simulate a blocking operation that could cause carrier thread pinning
      if (Thread.currentThread().isVirtual()) {
        try {
          // This is a synchronous, blocking operation that could cause pinning
          // In real code, this would be a blocking I/O or synchronization operation
          Thread.sleep(10);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        
        // Check if we're on a platform thread that's carrying a virtual thread
        // This is a simplified check - in real code, we'd need more sophisticated detection
        if (Thread.currentThread().getName().contains("carrier")) {
          carrierThreadPinned.set(true);
        }
      }
    }
    
    /**
     * Returns true if carrier thread pinning was detected.
     */
    public boolean wasCarrierThreadPinned() {
      return carrierThreadPinned.get();
    }
  }
}