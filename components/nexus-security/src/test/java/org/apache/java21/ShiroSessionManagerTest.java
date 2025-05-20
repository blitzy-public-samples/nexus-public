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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.SessionListener;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.session.mgt.SessionKey;
import org.apache.shiro.session.mgt.SessionValidationScheduler;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.AbstractSessionDAO;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.testsupport.TestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests Shiro's WebSessionManager implementation compatibility with Java 21 features.
 * This test ensures that session creation, validation, expiration, and cleanup work correctly
 * in the new Java runtime environment, particularly validating that session operations
 * work reliably with Virtual Threads and the updated security model in Java 21.
 */
public class ShiroSessionManagerTest
    extends TestSupport
{
  private static final int SESSION_TIMEOUT_MS = 500;
  private static final int VALIDATION_INTERVAL_MS = 250;
  private static final int CONCURRENT_SESSIONS = 1000;
  
  private DefaultWebSessionManager sessionManager;
  private TestSessionDAO sessionDAO;
  private TestSessionListener sessionListener;
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  public void setUp() {
    // Create a custom session DAO for testing
    sessionDAO = new TestSessionDAO();
    
    // Create a session listener to track session events
    sessionListener = new TestSessionListener();
    
    // Configure the session manager with our test components
    sessionManager = new DefaultWebSessionManager();
    sessionManager.setSessionDAO(sessionDAO);
    sessionManager.setGlobalSessionTimeout(SESSION_TIMEOUT_MS);
    sessionManager.setSessionValidationInterval(VALIDATION_INTERVAL_MS);
    sessionManager.getSessionListeners().add(sessionListener);
    sessionManager.setSessionValidationSchedulerEnabled(true);
    
    // Create a virtual thread executor for concurrent testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdownNow();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (sessionManager != null) {
      SessionValidationScheduler scheduler = sessionManager.getSessionValidationScheduler();
      if (scheduler != null) {
        scheduler.disableSessionValidation();
      }
    }
  }
  
  /**
   * Tests basic session creation and retrieval using a virtual thread.
   */
  @Test
  public void testSessionCreationWithVirtualThread() throws Exception {
    Future<Session> sessionFuture = virtualThreadExecutor.submit(() -> {
      // Start a new session
      Session session = sessionManager.start(null);
      assertThat(session, notNullValue());
      assertThat(session.getId(), notNullValue());
      
      // Set some attribute
      session.setAttribute("testKey", "testValue");
      
      return session;
    });
    
    // Get the session from the future
    Session session = sessionFuture.get(5, TimeUnit.SECONDS);
    
    // Verify the session was created and stored correctly
    assertThat(session, notNullValue());
    assertThat(sessionDAO.getActiveSessions().size(), is(1));
    assertThat(sessionListener.getStartCount(), is(1));
    
    // Retrieve the session and verify attributes
    Future<Object> attributeFuture = virtualThreadExecutor.submit(() -> {
      Session retrievedSession = sessionManager.getSession(new TestSessionKey(session.getId()));
      return retrievedSession.getAttribute("testKey");
    });
    
    Object attribute = attributeFuture.get(5, TimeUnit.SECONDS);
    assertThat(attribute, equalTo("testValue"));
  }
  
  /**
   * Tests session expiration and cleanup with Java 21's time handling.
   */
  @Test
  public void testSessionExpirationWithJava21TimeHandling() throws Exception {
    // Create a session with a short timeout
    Session session = sessionManager.start(null);
    assertThat(session, notNullValue());
    assertThat(sessionDAO.getActiveSessions().size(), is(1));
    
    // Wait for the session to expire (timeout + validation interval + buffer)
    Thread.sleep(SESSION_TIMEOUT_MS + VALIDATION_INTERVAL_MS + 100);
    
    // Verify the session was expired and removed
    assertThat(sessionDAO.getActiveSessions().size(), is(0));
    assertThat(sessionListener.getExpiredCount(), greaterThan(0));
    
    // Attempt to access the expired session
    Session retrievedSession = sessionManager.getSession(new TestSessionKey(session.getId()));
    assertThat(retrievedSession, nullValue());
  }
  
  /**
   * Tests concurrent session access using Java 21 Virtual Threads and structured concurrency.
   */
  @Test
  public void testConcurrentSessionAccessWithVirtualThreads() throws Exception {
    // Create a countdown latch to coordinate threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_SESSIONS);
    
    // Track created sessions
    List<Session> sessions = new ArrayList<>(CONCURRENT_SESSIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create multiple sessions concurrently using virtual threads
    for (int i = 0; i < CONCURRENT_SESSIONS; i++) {
      final int sessionIndex = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Create a session
          Session session = sessionManager.start(null);
          session.setAttribute("index", sessionIndex);
          sessions.add(session);
          
          // Verify we can retrieve the session
          Session retrieved = sessionManager.getSession(new TestSessionKey(session.getId()));
          if (retrieved != null && sessionIndex == (int) retrieved.getAttribute("index")) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread session test", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertThat("All virtual threads completed in time", completed, is(true));
    
    // Verify all sessions were created and retrieved successfully
    assertThat(successCount.get(), equalTo(CONCURRENT_SESSIONS));
    
    // Verify the session listener received the correct number of start events
    assertThat(sessionListener.getStartCount(), equalTo(CONCURRENT_SESSIONS));
  }
  
  /**
   * Tests session validation processing with the new threading model in Java 21.
   */
  @Test
  public void testSessionValidationWithNewThreadingModel() throws Exception {
    // Create sessions with different last access times
    List<Session> sessions = new ArrayList<>();
    
    // Create some active sessions
    for (int i = 0; i < 10; i++) {
      Session session = sessionManager.start(null);
      sessions.add(session);
    }
    
    // Create some expired sessions by manipulating their last access time
    for (int i = 0; i < 5; i++) {
      SimpleSession session = (SimpleSession) sessionManager.start(null);
      // Set last access time to well in the past
      session.setLastAccessTime(session.getLastAccessTime() - (SESSION_TIMEOUT_MS * 2));
      sessionDAO.update(session);
      sessions.add(session);
    }
    
    // Verify initial session count
    assertThat(sessionDAO.getActiveSessions().size(), is(15));
    
    // Force session validation
    sessionManager.validateSessions();
    
    // Wait for validation to complete (validation interval + buffer)
    Thread.sleep(VALIDATION_INTERVAL_MS + 100);
    
    // Verify expired sessions were removed
    assertThat(sessionDAO.getActiveSessions().size(), lessThan(15));
    assertThat(sessionListener.getExpiredCount(), greaterThan(0));
  }
  
  /**
   * Tests that session listeners and events propagate correctly in the new threading model.
   */
  @Test
  public void testSessionListenersWithVirtualThreads() throws Exception {
    // Create a session
    Session session = sessionManager.start(null);
    assertThat(sessionListener.getStartCount(), is(1));
    
    // Stop the session using a virtual thread
    Future<?> stopFuture = virtualThreadExecutor.submit(() -> {
      sessionManager.stop(new TestSessionKey(session.getId()));
    });
    
    // Wait for the stop operation to complete
    stopFuture.get(5, TimeUnit.SECONDS);
    
    // Verify the stop event was received by the listener
    assertThat(sessionListener.getStopCount(), is(1));
    
    // Create a session that will expire
    Session expiringSession = sessionManager.start(null);
    ((SimpleSession) expiringSession).setLastAccessTime(System.currentTimeMillis() - (SESSION_TIMEOUT_MS * 2));
    sessionDAO.update((SimpleSession) expiringSession);
    
    // Force validation to trigger expiration
    sessionManager.validateSessions();
    
    // Wait for validation to complete
    Thread.sleep(VALIDATION_INTERVAL_MS + 100);
    
    // Verify the expiration event was received by the listener
    assertThat(sessionListener.getExpiredCount(), greaterThan(0));
  }
  
  /**
   * Tests that adjustments to default timeouts work correctly in Java 21.
   */
  @Test
  public void testTimeoutAdjustmentsWithJava21() throws Exception {
    // Create a session manager with a custom timeout
    DefaultWebSessionManager customManager = new DefaultWebSessionManager();
    customManager.setSessionDAO(new TestSessionDAO());
    customManager.setGlobalSessionTimeout(100); // Very short timeout
    
    // Create a session
    Session session = customManager.start(null);
    assertThat(session, notNullValue());
    
    // Verify the session timeout is set correctly
    assertThat(session.getTimeout(), equalTo(100L));
    
    // Wait for the session to expire
    Thread.sleep(150);
    
    // Verify the session is expired
    assertThrows(Exception.class, () -> {
      session.touch();
    });
  }
  
  /**
   * A test implementation of SessionDAO for testing purposes.
   */
  private static class TestSessionDAO extends AbstractSessionDAO {
    private final ConcurrentHashMap<Serializable, Session> sessions = new ConcurrentHashMap<>();
    
    @Override
    protected Serializable doCreate(Session session) {
      Serializable id = generateSessionId(session);
      ((SimpleSession) session).setId(id);
      sessions.put(id, session);
      return id;
    }
    
    @Override
    protected Session doReadSession(Serializable sessionId) {
      return sessions.get(sessionId);
    }
    
    @Override
    public void update(Session session) {
      sessions.put(session.getId(), session);
    }
    
    @Override
    public void delete(Session session) {
      sessions.remove(session.getId());
    }
    
    @Override
    public Collection<Session> getActiveSessions() {
      return sessions.values();
    }
  }
  
  /**
   * A test implementation of SessionKey for testing purposes.
   */
  private static class TestSessionKey implements SessionKey {
    private final Serializable sessionId;
    
    public TestSessionKey(Serializable sessionId) {
      this.sessionId = sessionId;
    }
    
    @Override
    public Serializable getSessionId() {
      return sessionId;
    }
  }
  
  /**
   * A test implementation of SessionListener to track session events.
   */
  private static class TestSessionListener implements SessionListener {
    private final AtomicInteger startCount = new AtomicInteger(0);
    private final AtomicInteger stopCount = new AtomicInteger(0);
    private final AtomicInteger expiredCount = new AtomicInteger(0);
    
    @Override
    public void onStart(Session session) {
      startCount.incrementAndGet();
    }
    
    @Override
    public void onStop(Session session) {
      stopCount.incrementAndGet();
    }
    
    @Override
    public void onExpiration(Session session) {
      expiredCount.incrementAndGet();
    }
    
    public int getStartCount() {
      return startCount.get();
    }
    
    public int getStopCount() {
      return stopCount.get();
    }
    
    public int getExpiredCount() {
      return expiredCount.get();
    }
  }
}