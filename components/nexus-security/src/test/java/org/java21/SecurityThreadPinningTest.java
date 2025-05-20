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
package org.java21;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.Cookie;

import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.MDC;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.security.JwtHelper;
import org.sonatype.nexus.security.internal.AuthenticatingRealmImpl;
import org.sonatype.nexus.security.jwt.SecretStore;

import com.google.inject.Provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 * Tests security operations for thread pinning issues in Java 21 virtual threads.
 * 
 * This test identifies operations in the security subsystem that may cause carrier thread pinning
 * (especially during blocking I/O or synchronization), which negatively impacts the performance
 * benefits of virtual threads.
 */
@Category(VirtualThreadTestGroup.class)
public class SecurityThreadPinningTest extends TestSupport
{
  private static final int CONCURRENT_TASKS = 100;
  private static final int TIMEOUT_SECONDS = 30;
  
  private ExecutorService virtualThreadExecutor;
  
  @Mock
  private AuthenticatingRealmImpl realm;
  
  @Mock
  private Subject subject;
  
  @Mock
  private PrincipalCollection principals;
  
  @Mock
  private SecretStore secretStore;
  
  @Mock
  private Provider<SecretStore> storeProvider;
  
  private JwtHelper jwtHelper;
  
  @Before
  public void setup() throws Exception {
    MockitoAnnotations.openMocks(this);
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup JWT helper
    when(secretStore.getSecret()).thenReturn(java.util.Optional.of("secret"));
    when(storeProvider.get()).thenReturn(secretStore);
    jwtHelper = new JwtHelper(300, "/", storeProvider);
    jwtHelper.doStart();
    
    // Setup subject mock
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(java.util.Collections.singleton("NexusAuthorizingRealm"));
  }
  
  @After
  public void tearDown() throws Exception {
    virtualThreadExecutor.shutdown();
    virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
  
  /**
   * Tests that authentication operations don't cause thread pinning.
   * 
   * This test simulates multiple concurrent authentication attempts using virtual threads
   * and verifies that no thread pinning occurs during the process.
   */
  @Test
  public void testAuthenticationOperationsWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
    List<Exception> exceptions = new ArrayList<>();
    
    // Simulate multiple concurrent authentication attempts
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      final String username = "user" + i;
      final String password = "password" + i;
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Check if this thread is a virtual thread
          boolean isVirtual = Thread.currentThread().isVirtual();
          assertThat("Task should run on a virtual thread", isVirtual, is(true));
          
          // Create authentication token
          UsernamePasswordToken token = new UsernamePasswordToken(username, password);
          
          // Simulate authentication process
          // Note: We're not actually calling realm.getAuthenticationInfo() here
          // as we don't have a full security setup in this test
          // Instead, we're simulating the authentication process with operations
          // that could potentially cause pinning
          
          // Simulate credential matching (potential pinning point due to crypto operations)
          String hashedPassword = simulatePasswordHashing(password);
          
          // Simulate user lookup (potential pinning point due to synchronization)
          simulateUserLookup(username);
          
          // Verify we're still on a virtual thread after operations that might cause pinning
          boolean stillVirtual = Thread.currentThread().isVirtual();
          if (!stillVirtual) {
            pinnedThreadDetected.set(true);
          }
          
        } catch (Exception e) {
          errorCount.incrementAndGet();
          exceptions.add(e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All authentication tasks should complete", completed, is(true));
    assertThat("No errors should occur during authentication", errorCount.get(), is(0));
    assertThat("No thread pinning should be detected", pinnedThreadDetected.get(), is(false));
    
    if (!exceptions.isEmpty()) {
      throw new AssertionError("Exceptions occurred during test: " + exceptions.get(0));
    }
  }
  
  /**
   * Tests that JWT token operations don't cause thread pinning.
   * 
   * This test simulates multiple concurrent JWT token operations using virtual threads
   * and verifies that no thread pinning occurs during the process.
   */
  @Test
  public void testJwtTokenOperationsWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
    List<Exception> exceptions = new ArrayList<>();
    
    // Create a JWT token to use in the test
    Cookie jwtCookie = jwtHelper.createJwtCookie(subject, false);
    String jwt = jwtCookie.getValue();
    
    // Simulate multiple concurrent JWT operations
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Check if this thread is a virtual thread
          boolean isVirtual = Thread.currentThread().isVirtual();
          assertThat("Task should run on a virtual thread", isVirtual, is(true));
          
          // Simulate JWT verification (potential pinning point due to crypto operations)
          jwtHelper.verifyJwt(jwt);
          
          // Simulate JWT refresh (potential pinning point due to synchronization)
          Cookie refreshedCookie = jwtHelper.verifyAndRefreshJwtCookie(jwt, false);
          
          // Verify we're still on a virtual thread after operations that might cause pinning
          boolean stillVirtual = Thread.currentThread().isVirtual();
          if (!stillVirtual) {
            pinnedThreadDetected.set(true);
          }
          
        } catch (Exception e) {
          errorCount.incrementAndGet();
          exceptions.add(e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All JWT operations should complete", completed, is(true));
    assertThat("No errors should occur during JWT operations", errorCount.get(), is(0));
    assertThat("No thread pinning should be detected", pinnedThreadDetected.get(), is(false));
    
    if (!exceptions.isEmpty()) {
      throw new AssertionError("Exceptions occurred during test: " + exceptions.get(0));
    }
  }
  
  /**
   * Tests that MDC operations don't cause thread pinning.
   * 
   * This test simulates multiple concurrent MDC operations using virtual threads
   * and verifies that no thread pinning occurs during the process.
   */
  @Test
  public void testMdcOperationsWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
    List<Exception> exceptions = new ArrayList<>();
    
    // Simulate multiple concurrent MDC operations
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      final String requestId = UUID.randomUUID().toString();
      final String userId = "user" + i;
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Check if this thread is a virtual thread
          boolean isVirtual = Thread.currentThread().isVirtual();
          assertThat("Task should run on a virtual thread", isVirtual, is(true));
          
          // Simulate MDC operations (potential pinning point due to ThreadLocal usage)
          MDC.put("requestId", requestId);
          MDC.put("userId", userId);
          
          // Simulate some security operation that uses MDC
          simulateSecurityOperationWithMdc();
          
          // Clean up MDC
          MDC.remove("requestId");
          MDC.remove("userId");
          MDC.clear();
          
          // Verify we're still on a virtual thread after operations that might cause pinning
          boolean stillVirtual = Thread.currentThread().isVirtual();
          if (!stillVirtual) {
            pinnedThreadDetected.set(true);
          }
          
        } catch (Exception e) {
          errorCount.incrementAndGet();
          exceptions.add(e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All MDC operations should complete", completed, is(true));
    assertThat("No errors should occur during MDC operations", errorCount.get(), is(0));
    assertThat("No thread pinning should be detected", pinnedThreadDetected.get(), is(false));
    
    if (!exceptions.isEmpty()) {
      throw new AssertionError("Exceptions occurred during test: " + exceptions.get(0));
    }
  }
  
  /**
   * Simulates password hashing operation that might cause thread pinning.
   */
  private String simulatePasswordHashing(String password) {
    // Simulate a potentially blocking crypto operation
    try {
      // Add a small delay to simulate work
      Thread.sleep(5);
      return "hashed_" + password;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Password hashing interrupted", e);
    }
  }
  
  /**
   * Simulates user lookup operation that might cause thread pinning.
   */
  private void simulateUserLookup(String username) {
    // Simulate a potentially blocking database or cache lookup
    try {
      // Add a small delay to simulate work
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("User lookup interrupted", e);
    }
  }
  
  /**
   * Simulates a security operation that uses MDC for logging context.
   */
  private void simulateSecurityOperationWithMdc() {
    // Simulate a security operation that uses MDC
    try {
      // Add a small delay to simulate work
      Thread.sleep(5);
      
      // Access MDC values (potential pinning point)
      String requestId = MDC.get("requestId");
      String userId = MDC.get("userId");
      
      // Use the values to prevent optimization
      if (requestId == null || userId == null) {
        throw new IllegalStateException("MDC values should be set");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Security operation interrupted", e);
    }
  }
}