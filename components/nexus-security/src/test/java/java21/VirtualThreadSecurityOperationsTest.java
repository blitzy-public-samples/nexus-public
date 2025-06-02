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
package java21;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.Cookie;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.session.mgt.SessionContext;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.AbstractSessionDAO;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.session.mgt.WebSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.JwtHelper;
import org.sonatype.nexus.security.PasswordHelper;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authc.AuthenticationFailureException;
import org.sonatype.nexus.security.jwt.JwtVerificationException;
import org.sonatype.nexus.security.jwt.SecretStore;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.inject.Provider;

/**
 * Tests security operations using Java 21 Virtual Threads to verify high-concurrency performance
 * and compatibility with Shiro's authentication and authorization framework.
 * 
 * @since 3.60
 */
@DisplayName("Virtual Thread Security Operations Tests")
public class VirtualThreadSecurityOperationsTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private Subject subject;
  
  @Mock
  private PrincipalCollection principals;
  
  @Mock
  private SecretStore secretStore;
  
  @Mock
  private Provider<SecretStore> storeProvider;
  
  private JwtHelper jwtHelper;
  private DefaultSecurityManager securityManager;
  private TestSessionManager sessionManager;
  private TestSessionDAO sessionDAO;
  
  @BeforeEach
  public void setup() throws Exception {
    MockitoAnnotations.openMocks(this);
    
    // Setup JWT helper
    when(secretStore.getSecret()).thenReturn(Optional.of("secret"));
    when(storeProvider.get()).thenReturn(secretStore);
    jwtHelper = new JwtHelper(300, "/", storeProvider);
    jwtHelper.doStart();
    
    // Setup subject mock
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Collections.singleton("NexusAuthorizingRealm"));
    
    // Setup Shiro security manager with test session manager
    securityManager = new DefaultSecurityManager();
    sessionDAO = new TestSessionDAO();
    sessionManager = new TestSessionManager();
    sessionManager.setSessionDAO(sessionDAO);
    securityManager.setSessionManager(sessionManager);
    
    // Add a test realm
    TestRealm realm = new TestRealm();
    securityManager.setRealm(realm);
  }
  
  /**
   * Tests that JWT validation works correctly when executed with Virtual Threads.
   */
  @Test
  @DisplayName("JWT validation with Virtual Threads")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testJwtValidationWithVirtualThreads() throws Exception {
    // Create a valid JWT token
    String validJwt = createValidJwt();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> futures = new ArrayList<>();
      
      // Submit multiple concurrent JWT validation tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        futures.add(executor.submit(() -> {
          try {
            DecodedJWT decodedJWT = jwtHelper.verifyJwt(validJwt);
            return decodedJWT != null && 
                   JwtHelper.ISSUER.equals(decodedJWT.getClaim("iss").asString());
          } 
          catch (JwtVerificationException e) {
            return false;
          }
        }));
      }
      
      // Verify all operations completed successfully
      for (Future<Boolean> future : futures) {
        assertTrue(future.get(), "JWT validation should succeed");
      }
    }
  }
  
  /**
   * Tests that JWT refresh works correctly when executed with Virtual Threads.
   */
  @Test
  @DisplayName("JWT refresh with Virtual Threads")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testJwtRefreshWithVirtualThreads() throws Exception {
    // Create a valid JWT token
    String validJwt = createValidJwt();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Cookie>> futures = new ArrayList<>();
      
      // Submit multiple concurrent JWT refresh tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        futures.add(executor.submit(() -> {
          return jwtHelper.verifyAndRefreshJwtCookie(validJwt, false);
        }));
      }
      
      // Verify all operations completed successfully
      for (Future<Cookie> future : futures) {
        Cookie refreshedCookie = future.get();
        assertNotNull(refreshedCookie, "Refreshed cookie should not be null");
        assertEquals(JwtHelper.JWT_COOKIE_NAME, refreshedCookie.getName(), "Cookie name should match");
        assertTrue(refreshedCookie.isHttpOnly(), "Cookie should be HTTP only");
      }
    }
  }
  
  /**
   * Tests Shiro session creation and validation with Virtual Threads.
   */
  @Test
  @DisplayName("Shiro session management with Virtual Threads")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testShiroSessionWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Session>> futures = new ArrayList<>();
      
      // Submit multiple concurrent session creation tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          SessionContext context = new SessionContext();
          context.setHost("localhost");
          Session session = sessionManager.start(context);
          session.setAttribute("testAttribute", "value-" + index);
          return session;
        }));
      }
      
      // Verify all sessions were created successfully
      List<Session> sessions = new ArrayList<>();
      for (Future<Session> future : futures) {
        Session session = future.get();
        assertNotNull(session, "Session should not be null");
        assertNotNull(session.getId(), "Session ID should not be null");
        sessions.add(session);
      }
      
      // Verify session attributes can be accessed concurrently
      List<Future<String>> attributeFutures = new ArrayList<>();
      for (int i = 0; i < sessions.size(); i++) {
        final Session session = sessions.get(i);
        final int index = i;
        attributeFutures.add(executor.submit(() -> {
          return (String) session.getAttribute("testAttribute");
        }));
      }
      
      // Verify all attributes were retrieved correctly
      for (int i = 0; i < attributeFutures.size(); i++) {
        String attribute = attributeFutures.get(i).get();
        assertEquals("value-" + i, attribute, "Session attribute should match");
      }
    }
  }
  
  /**
   * Tests authentication operations with Virtual Threads.
   */
  @Test
  @DisplayName("Authentication operations with Virtual Threads")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testAuthenticationWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> futures = new ArrayList<>();
      
      // Submit multiple concurrent authentication tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        futures.add(executor.submit(() -> {
          try {
            AuthenticationToken token = new UsernamePasswordToken("admin", "password");
            AuthenticationInfo info = securityManager.authenticate(token);
            return info != null;
          } 
          catch (AuthenticationException e) {
            return false;
          }
        }));
      }
      
      // Verify all operations completed successfully
      for (Future<Boolean> future : futures) {
        assertTrue(future.get(), "Authentication should succeed");
      }
    }
  }
  
  /**
   * Tests thread pinning detection with synchronized blocks in Virtual Threads.
   */
  @Test
  @DisplayName("Thread pinning detection with Virtual Threads")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testThreadPinningDetection() throws Exception {
    // Create a shared object for synchronization
    final Object lock = new Object();
    final AtomicInteger pinnedThreadCount = new AtomicInteger(0);
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
    final ConcurrentHashMap<String, AtomicInteger> carrierThreadCounts = new ConcurrentHashMap<>();
    
    // Create virtual threads directly
    List<Thread> threads = new ArrayList<>();
    ThreadFactory factory = Thread.ofVirtual().factory();
    
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      Thread thread = factory.newThread(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Get the carrier thread name before synchronization
          String beforeCarrier = Thread.currentThread().toString();
          beforeCarrier = beforeCarrier.contains("@") ? 
              beforeCarrier.substring(beforeCarrier.indexOf('@') + 1) : beforeCarrier;
          
          // Enter synchronized block which will cause pinning
          synchronized (lock) {
            // Get the carrier thread name during synchronization (should be the same due to pinning)
            String duringCarrier = Thread.currentThread().toString();
            duringCarrier = duringCarrier.contains("@") ? 
                duringCarrier.substring(duringCarrier.indexOf('@') + 1) : duringCarrier;
            
            // If carrier thread is the same, we detected pinning
            if (beforeCarrier.equals(duringCarrier)) {
              pinnedThreadCount.incrementAndGet();
              carrierThreadCounts.computeIfAbsent(duringCarrier, k -> new AtomicInteger()).incrementAndGet();
            }
            
            // Simulate some work inside the synchronized block
            try {
              Thread.sleep(10);
            } 
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        } 
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } 
        finally {
          completionLatch.countDown();
        }
      });
      
      threads.add(thread);
      thread.start();
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "All threads should complete within timeout");
    
    // Verify that thread pinning was detected
    assertTrue(pinnedThreadCount.get() > 0, 
        "Thread pinning should be detected in synchronized blocks");
    
    // Log carrier thread distribution
    log.info("Carrier thread distribution during pinning: {}", carrierThreadCounts);
  }
  
  /**
   * Compares performance between platform threads and virtual threads for security operations.
   */
  @Test
  @DisplayName("Performance comparison: Platform vs Virtual Threads")
  @Timeout(value = TIMEOUT_SECONDS * 2, unit = TimeUnit.SECONDS)
  public void testPerformanceComparison() throws Exception {
    final int operationCount = 10000;
    final String validJwt = createValidJwt();
    
    // Measure platform threads performance
    long platformThreadsTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
        List<Future<Boolean>> futures = new ArrayList<>();
        
        for (int i = 0; i < operationCount; i++) {
          futures.add(executor.submit(() -> {
            try {
              DecodedJWT decodedJWT = jwtHelper.verifyJwt(validJwt);
              return decodedJWT != null;
            } 
            catch (JwtVerificationException e) {
              return false;
            }
          }));
        }
        
        for (Future<Boolean> future : futures) {
          assertTrue(future.get(), "JWT validation should succeed");
        }
      }
    });
    
    // Measure virtual threads performance
    long virtualThreadsTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<Boolean>> futures = new ArrayList<>();
        
        for (int i = 0; i < operationCount; i++) {
          futures.add(executor.submit(() -> {
            try {
              DecodedJWT decodedJWT = jwtHelper.verifyJwt(validJwt);
              return decodedJWT != null;
            } 
            catch (JwtVerificationException e) {
              return false;
            }
          }));
        }
        
        for (Future<Boolean> future : futures) {
          assertTrue(future.get(), "JWT validation should succeed");
        }
      }
    });
    
    log.info("Performance comparison for {} operations:", operationCount);
    log.info("Platform threads: {} ms", platformThreadsTime);
    log.info("Virtual threads: {} ms", virtualThreadsTime);
    log.info("Improvement factor: {}", (double) platformThreadsTime / virtualThreadsTime);
  }
  
  /**
   * Tests that I/O-bound operations in the security subsystem benefit from Virtual Threads.
   */
  @Test
  @DisplayName("I/O-bound security operations with Virtual Threads")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testIOBoundOperationsWithVirtualThreads() throws Exception {
    // Simulate I/O-bound security operations (e.g., database lookups, external service calls)
    final AtomicBoolean allOperationsCompleted = new AtomicBoolean(true);
    final CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Create virtual threads directly
    List<Thread> threads = new ArrayList<>();
    ThreadFactory factory = Thread.ofVirtual().factory();
    
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int delay = 50 + (i % 10) * 5; // Varying I/O delays
      
      Thread thread = factory.newThread(() -> {
        try {
          // Simulate I/O operation with varying delays
          Thread.sleep(delay);
          
          // Simulate security operation after I/O
          String validJwt = createValidJwt();
          DecodedJWT decodedJWT = jwtHelper.verifyJwt(validJwt);
          
          if (decodedJWT == null || !JwtHelper.ISSUER.equals(decodedJWT.getClaim("iss").asString())) {
            allOperationsCompleted.set(false);
          }
        } 
        catch (Exception e) {
          allOperationsCompleted.set(false);
        } 
        finally {
          completionLatch.countDown();
        }
      });
      
      threads.add(thread);
      thread.start();
    }
    
    // Wait for all operations to complete
    assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "All I/O-bound operations should complete within timeout");
    
    // Verify all operations completed successfully
    assertTrue(allOperationsCompleted.get(), "All I/O-bound operations should succeed");
  }
  
  /**
   * Tests concurrent session access patterns with Virtual Threads.
   */
  @Test
  @DisplayName("Concurrent session access with Virtual Threads")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testConcurrentSessionAccess() throws Exception {
    // Create a shared session
    SessionContext context = new SessionContext();
    context.setHost("localhost");
    Session sharedSession = sessionManager.start(context);
    sharedSession.setAttribute("counter", new AtomicInteger(0));
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Integer>> futures = new ArrayList<>();
      
      // Submit multiple concurrent session access tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        futures.add(executor.submit(() -> {
          // Get the counter from the session and increment it
          AtomicInteger counter = (AtomicInteger) sharedSession.getAttribute("counter");
          return counter.incrementAndGet();
        }));
      }
      
      // Verify all operations completed
      for (Future<Integer> future : futures) {
        int value = future.get();
        assertTrue(value > 0 && value <= CONCURRENT_OPERATIONS, 
            "Counter value should be between 1 and " + CONCURRENT_OPERATIONS);
      }
      
      // Verify final counter value
      AtomicInteger finalCounter = (AtomicInteger) sharedSession.getAttribute("counter");
      assertEquals(CONCURRENT_OPERATIONS, finalCounter.get(), 
          "Final counter value should match the number of operations");
    }
  }
  
  /**
   * Creates a valid JWT token for testing.
   */
  private String createValidJwt() {
    String userSessionId = UUID.randomUUID().toString();
    return JWT.create()
        .withIssuer(JwtHelper.ISSUER)
        .withExpiresAt(new java.util.Date(System.currentTimeMillis() + 100000))
        .withClaim(JwtHelper.USER_SESSION_ID, userSessionId)
        .withClaim(JwtHelper.USER, "admin")
        .withClaim(JwtHelper.REALM, "NexusAuthorizingRealm")
        .sign(Algorithm.HMAC256("secret"));
  }
  
  /**
   * Measures execution time of a runnable operation.
   */
  private long measureExecutionTime(Runnable operation) throws Exception {
    long startTime = System.currentTimeMillis();
    operation.run();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Test implementation of WebSessionManager for testing session operations.
   */
  private static class TestSessionManager extends DefaultSessionManager implements WebSessionManager {
    @Override
    public boolean isServletContainerSessions() {
      return false;
    }
  }
  
  /**
   * Test implementation of SessionDAO for testing session persistence.
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
   * Test implementation of Realm for testing authentication operations.
   */
  private static class TestRealm implements Realm {
    @Override
    public String getName() {
      return "TestRealm";
    }
    
    @Override
    public boolean supports(AuthenticationToken token) {
      return token instanceof UsernamePasswordToken;
    }
    
    @Override
    public AuthenticationInfo getAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
      // Always authenticate successfully for testing
      if (token instanceof UsernamePasswordToken) {
        UsernamePasswordToken upToken = (UsernamePasswordToken) token;
        if ("admin".equals(upToken.getUsername())) {
          return mock(AuthenticationInfo.class);
        }
      }
      throw new AuthenticationException("Authentication failed");
    }
  }
}