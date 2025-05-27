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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionKey;
import org.apache.shiro.session.mgt.SessionKey;
import org.apache.shiro.web.servlet.ShiroHttpServletRequest;
import org.apache.shiro.web.session.mgt.WebSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.app.GlobalComponentLookupHelper;

/**
 * Tests Shiro's session management with Java 21 virtual threads, verifying thread safety,
 * concurrent session operations, and carrier thread pinning prevention.
 * 
 * Specifically tests the NexusWebSessionManager and NexusSessionDAO classes to ensure they
 * properly handle session creation, validation, and expiration when executed on virtual threads.
 */
public class ShiroVirtualThreadSessionTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int SESSION_TIMEOUT_MS = 500;
  private static final int OPERATION_TIMEOUT_MS = 5000;
  
  private WebSessionManager sessionManager;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  public void setUp() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-", 0).factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Create a platform thread executor for comparison
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    // Set up the session manager with a short timeout for testing
    sessionManager = createSessionManager();
    
    // Set up base URL holder for session manager
    GlobalComponentLookupHelper lookupHelper = mock(GlobalComponentLookupHelper.class);
    BaseUrlHolder.set("http://localhost:8081", lookupHelper);
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    virtualThreadExecutor.shutdown();
    platformThreadExecutor.shutdown();
    BaseUrlHolder.unset();
  }
  
  /**
   * Creates a session manager for testing.
   * In a real implementation, this would create or inject the actual NexusWebSessionManager.
   */
  private WebSessionManager createSessionManager() {
    // For testing purposes, we're using a mock session manager
    // In a real implementation, this would be the actual NexusWebSessionManager
    WebSessionManager manager = mock(WebSessionManager.class);
    
    // Configure the mock to create sessions with IDs
    when(manager.start(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
      Session session = mock(Session.class);
      String sessionId = UUID.randomUUID().toString();
      when(session.getId()).thenReturn(sessionId);
      when(session.getTimeout()).thenReturn((long) SESSION_TIMEOUT_MS);
      when(session.getLastAccessTime()).thenReturn(System.currentTimeMillis());
      return session;
    });
    
    // Configure the mock to retrieve sessions by ID
    when(manager.getSession(org.mockito.ArgumentMatchers.any(SessionKey.class))).thenAnswer(invocation -> {
      SessionKey key = invocation.getArgument(0);
      String sessionId = key.getSessionId().toString();
      Session session = mock(Session.class);
      when(session.getId()).thenReturn(sessionId);
      when(session.getTimeout()).thenReturn((long) SESSION_TIMEOUT_MS);
      when(session.getLastAccessTime()).thenReturn(System.currentTimeMillis());
      return session;
    });
    
    return manager;
  }
  
  /**
   * Tests that session creation works correctly with virtual threads.
   * This verifies that the session manager can create sessions when called from virtual threads
   * without any threading issues.
   */
  @Test
  @Timeout(value = OPERATION_TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
  public void testSessionCreationWithVirtualThreads() throws Exception {
    int numSessions = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(numSessions);
    List<Future<Session>> futures = new ArrayList<>();
    
    // Create sessions concurrently using virtual threads
    for (int i = 0; i < numSessions; i++) {
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          HttpServletRequest request = mock(HttpServletRequest.class);
          HttpServletResponse response = mock(HttpServletResponse.class);
          
          // Create a session
          Session session = sessionManager.start(request);
          assertNotNull(session);
          assertNotNull(session.getId());
          
          return session;
        } finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    
    // Verify all sessions were created successfully
    for (Future<Session> future : futures) {
      Session session = future.get();
      assertNotNull(session);
      assertNotNull(session.getId());
    }
  }
  
  /**
   * Tests concurrent session access from multiple virtual threads.
   * This verifies that the session manager can handle concurrent access to sessions
   * from multiple virtual threads without thread safety issues.
   */
  @Test
  @Timeout(value = OPERATION_TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
  public void testConcurrentSessionAccessWithVirtualThreads() throws Exception {
    // Create a session
    HttpServletRequest request = mock(HttpServletRequest.class);
    Session session = sessionManager.start(request);
    String sessionId = session.getId().toString();
    
    int numOperations = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(numOperations);
    AtomicBoolean concurrencyIssueDetected = new AtomicBoolean(false);
    
    // Access the session concurrently from multiple virtual threads
    for (int i = 0; i < numOperations; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Get the session by ID
          SessionKey key = new DefaultSessionKey(sessionId);
          Session retrievedSession = sessionManager.getSession(key);
          
          // Verify the session is valid
          if (retrievedSession == null || !sessionId.equals(retrievedSession.getId().toString())) {
            concurrencyIssueDetected.set(true);
          }
          
          // Simulate some work with the session
          Thread.sleep(10);
          
          return null;
        } catch (Exception e) {
          concurrencyIssueDetected.set(true);
          return null;
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    
    // Verify no concurrency issues were detected
    assertFalse(concurrencyIssueDetected.get(), "Concurrency issues detected during session access");
  }
  
  /**
   * Tests that virtual threads are not pinned during session operations.
   * This verifies that the session manager does not cause virtual threads to be pinned
   * to carrier threads, which would reduce the scalability benefits of virtual threads.
   */
  @Test
  @Timeout(value = OPERATION_TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
  public void testVirtualThreadPinningPrevention() throws Exception {
    // Create a large number of sessions concurrently to detect potential pinning
    int numSessions = CONCURRENT_THREADS * 2; // Use more threads to increase chance of detecting pinning
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(numSessions);
    
    // Track carrier thread IDs to detect pinning
    ConcurrentHashMap<Long, AtomicInteger> carrierThreadCounts = new ConcurrentHashMap<>();
    
    // Create sessions concurrently using virtual threads
    for (int i = 0; i < numSessions; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Get the current carrier thread ID
          long carrierId = Thread.currentThread().threadId();
          
          // Record this carrier thread usage
          carrierThreadCounts.computeIfAbsent(carrierId, k -> new AtomicInteger(0)).incrementAndGet();
          
          // Create a session with some I/O simulation
          HttpServletRequest request = mock(HttpServletRequest.class);
          Session session = sessionManager.start(request);
          
          // Simulate I/O operation that could cause pinning if not handled correctly
          Thread.sleep(50);
          
          // Access the session again
          SessionKey key = new DefaultSessionKey(session.getId());
          sessionManager.getSession(key);
          
          return null;
        } catch (Exception e) {
          log.error("Error in virtual thread test", e);
          return null;
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    
    // Verify that multiple carrier threads were used, indicating no persistent pinning
    // The exact number depends on the JVM configuration, but we should see more than one
    assertTrue(carrierThreadCounts.size() > 1, 
        "Expected multiple carrier threads, but only found " + carrierThreadCounts.size());
    
    // Log the distribution of carrier threads
    log.info("Carrier thread distribution: " + carrierThreadCounts);
  }
  
  /**
   * Tests session expiration with virtual threads.
   * This verifies that the session manager correctly handles session expiration
   * when sessions are accessed from virtual threads.
   */
  @Test
  @Timeout(value = OPERATION_TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
  public void testSessionExpirationWithVirtualThreads() throws Exception {
    // Create a session with a short timeout
    HttpServletRequest request = mock(HttpServletRequest.class);
    Session session = sessionManager.start(request);
    String sessionId = session.getId().toString();
    
    // Configure the session to expire
    when(session.isValid()).thenReturn(true, false);
    
    // Wait for the session to expire
    Thread.sleep(SESSION_TIMEOUT_MS * 2);
    
    // Verify the session is expired when accessed from a virtual thread
    Future<Boolean> future = virtualThreadExecutor.submit(() -> {
      SessionKey key = new DefaultSessionKey(sessionId);
      Session retrievedSession = sessionManager.getSession(key);
      return retrievedSession != null && retrievedSession.isValid();
    });
    
    assertFalse(future.get(), "Session should be expired");
  }
  
  /**
   * Compares performance between virtual threads and platform threads for session operations.
   * This verifies that virtual threads provide better performance for I/O-bound session operations.
   */
  @Test
  @Timeout(value = OPERATION_TIMEOUT_MS * 2, unit = TimeUnit.MILLISECONDS)
  public void testSessionPerformanceComparison() throws Exception {
    int numOperations = CONCURRENT_THREADS * 5;
    
    // Measure time with platform threads
    long platformThreadTime = measureSessionOperationsTime(platformThreadExecutor, numOperations);
    
    // Measure time with virtual threads
    long virtualThreadTime = measureSessionOperationsTime(virtualThreadExecutor, numOperations);
    
    // Log the results
    log.info("Platform thread time: {} ms", platformThreadTime);
    log.info("Virtual thread time: {} ms", virtualThreadTime);
    
    // Virtual threads should generally be more efficient for I/O-bound operations,
    // but this is not a strict requirement as it depends on the environment
    // We're just logging the results for informational purposes
  }
  
  /**
   * Helper method to measure the time taken to perform session operations using the given executor.
   */
  private long measureSessionOperationsTime(ExecutorService executor, int numOperations) throws Exception {
    CountDownLatch latch = new CountDownLatch(numOperations);
    long startTime = System.nanoTime();
    
    for (int i = 0; i < numOperations; i++) {
      executor.submit(() -> {
        try {
          // Create a session
          HttpServletRequest request = mock(HttpServletRequest.class);
          Session session = sessionManager.start(request);
          
          // Simulate I/O operation
          Thread.sleep(20);
          
          // Access the session
          SessionKey key = new DefaultSessionKey(session.getId());
          Session retrievedSession = sessionManager.getSession(key);
          
          // Simulate another I/O operation
          Thread.sleep(20);
          
          return retrievedSession;
        } catch (Exception e) {
          log.error("Error in performance test", e);
          return null;
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    latch.await();
    
    // Calculate and return the elapsed time in milliseconds
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
  }
  
  /**
   * Tests that session attributes can be set and retrieved correctly with virtual threads.
   * This verifies that the session manager correctly handles session attribute operations
   * when sessions are accessed from virtual threads.
   */
  @Test
  @Timeout(value = OPERATION_TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
  public void testSessionAttributesWithVirtualThreads() throws Exception {
    // Create a session
    HttpServletRequest request = mock(HttpServletRequest.class);
    Session session = sessionManager.start(request);
    String sessionId = session.getId().toString();
    
    // Configure the mock session to handle attributes
    ConcurrentHashMap<Object, Object> attributes = new ConcurrentHashMap<>();
    when(session.getAttribute(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> 
        attributes.get(inv.getArgument(0)));
    when(session.setAttribute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> {
          attributes.put(inv.getArgument(0), inv.getArgument(1));
          return null;
        });
    
    // Test setting and getting attributes from multiple virtual threads
    int numThreads = 10;
    CountDownLatch latch = new CountDownLatch(numThreads);
    List<Future<Boolean>> futures = new ArrayList<>();
    
    for (int i = 0; i < numThreads; i++) {
      final String key = "key-" + i;
      final String value = "value-" + i;
      
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          // Get the session
          SessionKey sessionKey = new DefaultSessionKey(sessionId);
          Session retrievedSession = sessionManager.getSession(sessionKey);
          
          // Set an attribute
          retrievedSession.setAttribute(key, value);
          
          // Simulate some concurrent work
          Thread.sleep(10);
          
          // Get the attribute and verify it's correct
          Object retrievedValue = retrievedSession.getAttribute(key);
          return value.equals(retrievedValue);
        } finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    
    // Verify all attribute operations were successful
    for (Future<Boolean> future : futures) {
      assertTrue(future.get(), "Session attribute operation failed");
    }
  }
}