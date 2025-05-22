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
package org.sonatype.nexus.rapture.internal.security;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.common.wonderland.AuthTicketService;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousManager;
import org.sonatype.nexus.security.authz.Permission;
import org.sonatype.nexus.security.authz.WildcardPermission2;
import org.sonatype.nexus.security.privilege.Privilege;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link SecurityComponent} with Java 21 Virtual Threads to validate authentication and authorization
 * operations under high concurrency.
 *
 * @since 3.60
 */
public class RaptureSecurityVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int PERMISSION_COUNT = 100;
  private static final String TEST_USERNAME = "testuser";
  private static final String TEST_PASSWORD = "testpassword";
  private static final String TEST_REALM = "testrealm";

  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private AnonymousManager anonymousManager;

  @Mock
  private AuthTicketService authTickets;

  @Mock
  private EventManager eventManager;

  @Mock
  private Subject subject;

  @Mock
  private PrincipalCollection principals;

  private SecurityComponent underTest;

  @Before
  public void setup() {
    // Setup mocks for SecurityComponent
    when(securitySystem.getSubject()).thenReturn(subject);
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Set.of(TEST_REALM));
    when(subject.getPrincipal()).thenReturn(TEST_USERNAME);
    when(subject.isAuthenticated()).thenReturn(true);

    // Setup anonymous configuration
    AnonymousConfiguration anonConfig = mock(AnonymousConfiguration.class);
    when(anonConfig.isEnabled()).thenReturn(true);
    when(anonConfig.getUserId()).thenReturn("anonymous");
    when(anonymousManager.getConfiguration()).thenReturn(anonConfig);

    // Setup permissions
    List<Privilege> privileges = new ArrayList<>();
    for (int i = 0; i < PERMISSION_COUNT; i++) {
      Privilege privilege = mock(Privilege.class);
      Permission permission = new WildcardPermission2("test:permission:" + i);
      when(privilege.getPermission()).thenReturn(permission);
      privileges.add(privilege);
    }
    when(securitySystem.listPrivileges()).thenReturn(privileges);
    when(subject.isPermitted(any(List.class))).thenReturn(new boolean[PERMISSION_COUNT]);

    // Setup auth ticket service
    when(authTickets.createTicket(any(), any())).thenReturn("test-ticket");

    // Setup security manager for ThreadContext
    SecurityUtils.setSecurityManager(new DefaultSecurityManager());

    // Create the component under test
    underTest = new SecurityComponent(securitySystem, anonymousManager, authTickets);
  }

  @After
  public void cleanup() {
    ThreadContext.unbindSubject();
    ThreadContext.unbindSecurityManager();
  }

  /**
   * Tests authentication with many concurrent virtual threads to validate thread safety
   * and performance under high load.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Encode credentials
    String base64Username = Strings2.encodeBase64(TEST_USERNAME);
    String base64Password = Strings2.encodeBase64(TEST_PASSWORD);

    // Measure memory before test
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    long memoryBefore = memoryBean.getHeapMemoryUsage().getUsed();
    
    // Create and start virtual threads
    long startTime = System.nanoTime();
    
    for (int i = 0; i < threadCount; i++) {
      Thread.startVirtualThread(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Bind subject to thread context (simulating what happens in a real request)
          ThreadContext.bind(subject);
          
          try {
            // Perform authentication
            UserXO user = underTest.authenticate(base64Username, base64Password);
            
            // Verify authentication result
            if (user != null && user.isAuthenticated()) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          } finally {
            // Clean up thread context
            ThreadContext.unbindSubject();
            completionLatch.countDown();
          }
        } catch (Exception e) {
          failureCount.incrementAndGet();
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    
    // Measure memory after test
    long memoryAfter = memoryBean.getHeapMemoryUsage().getUsed();
    long memoryUsed = memoryAfter - memoryBefore;
    
    // Log performance metrics
    log.info("Authentication test with {} virtual threads completed in {} ms", threadCount, durationMs);
    log.info("Memory used: {} bytes", memoryUsed);
    log.info("Successful authentications: {}", successCount.get());
    log.info("Failed authentications: {}", failureCount.get());
    
    // Verify results
    assertThat("All threads should complete", completed, is(true));
    assertThat("All authentications should succeed", successCount.get(), equalTo(threadCount));
    assertThat("No authentications should fail", failureCount.get(), equalTo(0));
    
    // Verify the authenticate method was called the expected number of times
    verify(subject, times(threadCount)).login(any(UsernamePasswordToken.class));
  }

  /**
   * Tests permission checking with many concurrent virtual threads to validate thread safety
   * and performance under high load.
   */
  @Test
  public void testConcurrentPermissionCheckingWithVirtualThreads() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Measure memory before test
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    long memoryBefore = memoryBean.getHeapMemoryUsage().getUsed();
    
    // Create and start virtual threads
    long startTime = System.nanoTime();
    
    for (int i = 0; i < threadCount; i++) {
      Thread.startVirtualThread(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Bind subject to thread context (simulating what happens in a real request)
          ThreadContext.bind(subject);
          
          try {
            // Perform permission checking
            List<PermissionXO> permissions = underTest.getPermissions();
            
            // Verify permission checking result
            if (permissions != null) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          } finally {
            // Clean up thread context
            ThreadContext.unbindSubject();
            completionLatch.countDown();
          }
        } catch (Exception e) {
          failureCount.incrementAndGet();
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    
    // Measure memory after test
    long memoryAfter = memoryBean.getHeapMemoryUsage().getUsed();
    long memoryUsed = memoryAfter - memoryBefore;
    
    // Log performance metrics
    log.info("Permission checking test with {} virtual threads completed in {} ms", threadCount, durationMs);
    log.info("Memory used: {} bytes", memoryUsed);
    log.info("Successful permission checks: {}", successCount.get());
    log.info("Failed permission checks: {}", failureCount.get());
    
    // Verify results
    assertThat("All threads should complete", completed, is(true));
    assertThat("All permission checks should succeed", successCount.get(), equalTo(threadCount));
    assertThat("No permission checks should fail", failureCount.get(), equalTo(0));
    
    // Verify the permission checking methods were called the expected number of times
    verify(securitySystem, times(threadCount)).listPrivileges();
    verify(subject, times(threadCount)).isPermitted(any(List.class));
  }

  /**
   * Compares performance between platform threads and virtual threads for authentication operations.
   */
  @Test
  public void testAuthenticationPerformanceComparison() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    String base64Username = Strings2.encodeBase64(TEST_USERNAME);
    String base64Password = Strings2.encodeBase64(TEST_PASSWORD);
    
    // Test with platform threads
    long platformThreadTime = runAuthenticationTest(threadCount, base64Username, base64Password, false);
    log.info("Authentication with {} platform threads completed in {} ms", threadCount, platformThreadTime);
    
    // Test with virtual threads
    long virtualThreadTime = runAuthenticationTest(threadCount, base64Username, base64Password, true);
    log.info("Authentication with {} virtual threads completed in {} ms", threadCount, virtualThreadTime);
    
    // Log performance comparison
    double speedupFactor = (double) platformThreadTime / virtualThreadTime;
    log.info("Virtual threads were {}x faster than platform threads for authentication", speedupFactor);
    
    // Virtual threads should be faster or at least not significantly slower
    assertThat("Virtual threads should not be significantly slower than platform threads", 
        speedupFactor, greaterThan(0.8));
  }

  /**
   * Helper method to run authentication test with either platform or virtual threads.
   */
  private long runAuthenticationTest(int threadCount, String base64Username, String base64Password, boolean useVirtualThreads) 
      throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    long startTime = System.nanoTime();
    
    if (useVirtualThreads) {
      // Use virtual threads
      for (int i = 0; i < threadCount; i++) {
        Thread.startVirtualThread(() -> runAuthenticationTask(
            startLatch, completionLatch, base64Username, base64Password));
      }
    } else {
      // Use platform threads with an executor
      try (ExecutorService executor = Executors.newFixedThreadPool(Math.min(threadCount, 200))) {
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> runAuthenticationTask(
              startLatch, completionLatch, base64Username, base64Password));
        }
        executor.shutdown();
      }
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(60, TimeUnit.SECONDS);
    long endTime = System.nanoTime();
    
    return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
  }

  /**
   * Helper method to run a single authentication task.
   */
  private void runAuthenticationTask(CountDownLatch startLatch, CountDownLatch completionLatch, 
                                    String base64Username, String base64Password) {
    try {
      startLatch.await();
      ThreadContext.bind(subject);
      try {
        underTest.authenticate(base64Username, base64Password);
      } finally {
        ThreadContext.unbindSubject();
        completionLatch.countDown();
      }
    } catch (Exception e) {
      completionLatch.countDown();
    }
  }

  /**
   * Tests that Shiro's ThreadContext binding works correctly with virtual threads.
   * This is critical for security operations as ThreadContext is used to store the current subject.
   */
  @Test
  public void testThreadContextBindingWithVirtualThreads() throws Exception {
    int threadCount = 100; // Smaller count for this specific test
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    Set<String> threadIds = new HashSet<>();
    
    // Create multiple virtual threads that bind and verify subjects
    for (int i = 0; i < threadCount; i++) {
      final int threadNum = i;
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await();
          
          // Create a unique subject for this thread
          Subject threadSubject = mock(Subject.class);
          PrincipalCollection threadPrincipals = mock(PrincipalCollection.class);
          String threadUsername = "user-" + threadNum;
          
          when(threadSubject.getPrincipals()).thenReturn(threadPrincipals);
          when(threadPrincipals.getRealmNames()).thenReturn(Set.of(TEST_REALM));
          when(threadSubject.getPrincipal()).thenReturn(threadUsername);
          when(threadSubject.isAuthenticated()).thenReturn(true);
          
          // Bind the subject to this thread
          ThreadContext.bind(threadSubject);
          
          try {
            // Verify that we get back the same subject we bound
            Subject retrievedSubject = ThreadContext.getSubject();
            assertThat(retrievedSubject, notNullValue());
            assertThat(retrievedSubject.getPrincipal(), equalTo(threadUsername));
            
            // Add this thread's ID to the set (for verification)
            synchronized (threadIds) {
              threadIds.add(threadUsername);
            }
            
            // Small delay to increase chance of thread interleaving
            Thread.sleep(10);
            
            // Verify again that we still have the correct subject
            retrievedSubject = ThreadContext.getSubject();
            assertThat(retrievedSubject, notNullValue());
            assertThat(retrievedSubject.getPrincipal(), equalTo(threadUsername));
          } finally {
            ThreadContext.unbindSubject();
            completionLatch.countDown();
          }
        } catch (Exception e) {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads
    startLatch.countDown();
    
    // Wait for completion
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All threads should complete", completed, is(true));
    assertThat("Each thread should have a unique subject", threadIds.size(), equalTo(threadCount));
  }

  /**
   * Tests the authenticationToken method with virtual threads to validate token generation
   * under high concurrency.
   */
  @Test
  public void testConcurrentTokenGenerationWithVirtualThreads() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Encode credentials
    String base64Username = Strings2.encodeBase64(TEST_USERNAME);
    String base64Password = Strings2.encodeBase64(TEST_PASSWORD);
    
    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await();
          ThreadContext.bind(subject);
          
          try {
            String token = underTest.authenticationToken(base64Username, base64Password);
            if (token != null && !token.isEmpty()) {
              successCount.incrementAndGet();
            }
          } finally {
            ThreadContext.unbindSubject();
            completionLatch.countDown();
          }
        } catch (Exception e) {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads
    startLatch.countDown();
    
    // Wait for completion
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All threads should complete", completed, is(true));
    assertThat("All token generations should succeed", successCount.get(), equalTo(threadCount));
    
    // Verify the token service was called the expected number of times
    verify(authTickets, times(threadCount)).createTicket(any(), any());
  }
}