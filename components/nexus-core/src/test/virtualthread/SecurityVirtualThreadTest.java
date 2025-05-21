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
package org.sonatype.nexus.virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authc.AuthenticationException;
import org.sonatype.nexus.security.authz.AuthorizationException;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserSearchCriteria;

import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for validating security operations with Java 21 Virtual Threads in the Nexus Core component.
 * This class tests authentication, authorization, and security configuration operations under
 * high-concurrency Virtual Thread scenarios. It verifies that security components maintain proper
 * thread safety, context propagation, and resource management when executed across numerous
 * lightweight virtual threads.
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class SecurityVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int TIMEOUT_SECONDS = 30;

  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private AuthorizationManager authorizationManager;

  @Mock
  private UserManager userManager;

  @Mock
  private Subject subject;

  private ExecutorService virtualThreadExecutor;

  @Before
  public void setUp() throws Exception {
    // Initialize mocks
    when(securitySystem.getAuthorizationManager(anyString())).thenReturn(authorizationManager);
    when(securitySystem.getSubject()).thenReturn(subject);

    // Create a virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Clear any existing Shiro thread context
    ThreadContext.remove();
  }

  @After
  public void tearDown() throws Exception {
    // Shutdown executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(10, TimeUnit.SECONDS);
    }

    // Clear Shiro thread context
    ThreadContext.remove();
  }

  /**
   * Tests authentication flows with concurrent Virtual Thread execution.
   * This test verifies that the SecuritySystem can handle multiple authentication
   * requests concurrently using Virtual Threads without thread safety issues.
   */
  @Test
  public void testConcurrentAuthentication() throws Exception {
    // Set up mock behavior for authentication
    when(securitySystem.authenticate(any(UsernamePasswordToken.class)))
        .thenAnswer(invocation -> {
          UsernamePasswordToken token = invocation.getArgument(0);
          // Simulate some processing time
          Thread.sleep(5);
          // Return a mock subject for valid credentials, throw exception for invalid
          if ("validuser".equals(token.getUsername()) && "validpassword".equals(new String(token.getPassword()))) {
            return subject;
          }
          else {
            throw new AuthenticationException("Invalid credentials");
          }
        });

    // Counters for tracking results
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);

    // Submit authentication tasks to virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Alternate between valid and invalid credentials
          String username = (index % 2 == 0) ? "validuser" : "invaliduser";
          String password = (index % 2 == 0) ? "validpassword" : "invalidpassword";
          UsernamePasswordToken token = new UsernamePasswordToken(username, password);

          try {
            Subject authenticatedSubject = securitySystem.authenticate(token);
            if (authenticatedSubject != null) {
              successCount.incrementAndGet();
            }
          }
          catch (AuthenticationException e) {
            failureCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          log.error("Unexpected error during authentication", e);
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);

      futures.add(future);
    }

    // Wait for all authentication attempts to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("Authentication operations timed out", completed, is(true));

    // Wait for all futures to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // Verify results
    assertThat("No unexpected errors should occur", errorCount.get(), is(0));
    assertThat("Successful authentications should match expected count", 
        successCount.get(), is(CONCURRENT_THREADS / 2));
    assertThat("Failed authentications should match expected count", 
        failureCount.get(), is(CONCURRENT_THREADS / 2));

    // Verify the authentication method was called the expected number of times
    verify(securitySystem, times(CONCURRENT_THREADS)).authenticate(any(UsernamePasswordToken.class));
  }

  /**
   * Tests authorization checks across numerous parallel Virtual Threads.
   * This test verifies that the SecuritySystem can handle multiple authorization
   * checks concurrently using Virtual Threads without thread safety issues.
   */
  @Test
  public void testConcurrentAuthorization() throws Exception {
    // Set up mock behavior for authorization
    when(subject.isPermitted(anyString()))
        .thenAnswer(invocation -> {
          String permission = invocation.getArgument(0);
          // Simulate some processing time
          Thread.sleep(5);
          // Return true for certain permissions, false for others
          return permission.startsWith("allowed:");
        });

    // Counters for tracking results
    AtomicInteger permittedCount = new AtomicInteger(0);
    AtomicInteger deniedCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS * OPERATIONS_PER_THREAD);

    // Submit authorization tasks to virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
          try {
            // Alternate between allowed and denied permissions
            String permission = (j % 2 == 0) ? "allowed:read" : "denied:write";

            boolean isPermitted = subject.isPermitted(permission);
            if (isPermitted) {
              permittedCount.incrementAndGet();
            }
            else {
              deniedCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Unexpected error during authorization", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        }
      }, virtualThreadExecutor);

      futures.add(future);
    }

    // Wait for all authorization checks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("Authorization operations timed out", completed, is(true));

    // Wait for all futures to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // Verify results
    assertThat("No unexpected errors should occur", errorCount.get(), is(0));
    int totalChecks = CONCURRENT_THREADS * OPERATIONS_PER_THREAD;
    assertThat("Permitted authorizations should match expected count", 
        permittedCount.get(), is(totalChecks / 2));
    assertThat("Denied authorizations should match expected count", 
        deniedCount.get(), is(totalChecks / 2));

    // Verify the isPermitted method was called the expected number of times
    verify(subject, times(totalChecks)).isPermitted(anyString());
  }

  /**
   * Tests security context propagation during Virtual Thread handoffs.
   * This test verifies that the Shiro SecurityManager correctly maintains and propagates
   * the security context when Virtual Threads are suspended and resumed.
   */
  @Test
  public void testSecurityContextPropagation() throws Exception {
    // Set up a principal collection for the test
    SimplePrincipalCollection principals = new SimplePrincipalCollection("testuser", "test-realm");
    
    // Set up a latch to coordinate thread execution
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track any context propagation errors
    AtomicInteger contextErrorCount = new AtomicInteger(0);
    
    // Submit tasks to virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Bind the security context to this thread
          ThreadContext.bind(principals);
          
          // Wait for all threads to be ready (this will cause a Virtual Thread handoff)
          startLatch.await();
          
          // Verify the security context is still available after the handoff
          PrincipalCollection currentPrincipals = ThreadContext.getSecurityManager().getPrincipals();
          if (currentPrincipals == null || !currentPrincipals.equals(principals)) {
            contextErrorCount.incrementAndGet();
          }
          
          // Simulate some I/O work that will cause another handoff
          Thread.sleep(10);
          
          // Verify the security context again after the second handoff
          currentPrincipals = ThreadContext.getSecurityManager().getPrincipals();
          if (currentPrincipals == null || !currentPrincipals.equals(principals)) {
            contextErrorCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          log.error("Error in security context propagation test", e);
          contextErrorCount.incrementAndGet();
        }
        finally {
          // Clean up the thread context
          ThreadContext.unbindSecurityManager();
          ThreadContext.unbindSubject();
          completionLatch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Signal all threads to proceed
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("Context propagation operations timed out", completed, is(true));
    
    // Wait for all futures to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Verify no context propagation errors occurred
    assertThat("No security context propagation errors should occur", 
        contextErrorCount.get(), is(0));
  }

  /**
   * Tests user management operations with concurrent Virtual Thread execution.
   * This test verifies that the SecuritySystem can handle multiple user management
   * operations concurrently using Virtual Threads without thread safety issues.
   */
  @Test
  public void testConcurrentUserManagement() throws Exception {
    // Set up mock behavior for user management
    when(securitySystem.searchUsers(any(UserSearchCriteria.class)))
        .thenAnswer(invocation -> {
          // Simulate some processing time
          Thread.sleep(5);
          // Return a list of mock users
          List<User> users = new ArrayList<>();
          for (int i = 0; i < 5; i++) {
            User user = Mockito.mock(User.class);
            when(user.getUserId()).thenReturn("user" + i);
            users.add(user);
          }
          return users;
        });

    // Counters for tracking results
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);

    // Submit user management tasks to virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Create a search criteria
          UserSearchCriteria criteria = new UserSearchCriteria();
          
          // Search for users
          List<User> users = securitySystem.searchUsers(criteria);
          
          // Verify the result
          if (users != null && users.size() == 5) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          log.error("Unexpected error during user management", e);
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);

      futures.add(future);
    }

    // Wait for all user management operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("User management operations timed out", completed, is(true));

    // Wait for all futures to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // Verify results
    assertThat("No unexpected errors should occur", errorCount.get(), is(0));
    assertThat("Successful operations should match expected count", 
        successCount.get(), is(CONCURRENT_THREADS));

    // Verify the searchUsers method was called the expected number of times
    verify(securitySystem, times(CONCURRENT_THREADS)).searchUsers(any(UserSearchCriteria.class));
  }

  /**
   * Tests error handling during concurrent security operations with Virtual Threads.
   * This test verifies that exceptions are properly propagated and handled when
   * security operations fail under high concurrency with Virtual Threads.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    // Set up mock behavior to throw exceptions
    doThrow(new UserNotFoundException("User not found"))
        .when(securitySystem).getUser(eq("missing-user"));
    
    doThrow(new AuthorizationException("Not authorized"))
        .when(authorizationManager).isPermitted(eq("missing-user"), anyString());

    // Counters for tracking results
    AtomicInteger userNotFoundCount = new AtomicInteger(0);
    AtomicInteger authzExceptionCount = new AtomicInteger(0);
    AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS * 2); // Two operations per thread

    // Submit tasks to virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Try to get a non-existent user
          try {
            securitySystem.getUser("missing-user");
          }
          catch (UserNotFoundException e) {
            userNotFoundCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }

          // Try an unauthorized operation
          try {
            authorizationManager.isPermitted("missing-user", "some:permission");
          }
          catch (AuthorizationException e) {
            authzExceptionCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        }
        catch (Exception e) {
          log.error("Unexpected error during error handling test", e);
          unexpectedErrorCount.incrementAndGet();
          // Ensure we still count down the latch
          latch.countDown();
          latch.countDown();
        }
      }, virtualThreadExecutor);

      futures.add(future);
    }

    // Wait for all operations to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("Error handling operations timed out", completed, is(true));

    // Wait for all futures to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // Verify results
    assertThat("No unexpected errors should occur", unexpectedErrorCount.get(), is(0));
    assertThat("UserNotFoundException count should match expected", 
        userNotFoundCount.get(), is(CONCURRENT_THREADS));
    assertThat("AuthorizationException count should match expected", 
        authzExceptionCount.get(), is(CONCURRENT_THREADS));

    // Verify the methods were called the expected number of times
    verify(securitySystem, times(CONCURRENT_THREADS)).getUser("missing-user");
    verify(authorizationManager, times(CONCURRENT_THREADS)).isPermitted(eq("missing-user"), anyString());
  }

  /**
   * Tests the performance of security operations with Virtual Threads compared to platform threads.
   * This test measures the throughput and latency of security operations under high concurrency
   * with both thread types to validate the performance benefits of Virtual Threads.
   */
  @Test
  public void testSecurityOperationsPerformance() throws Exception {
    // Set up mock behavior for a simple permission check
    when(subject.isPermitted(anyString())).thenReturn(true);
    when(securitySystem.getSubject()).thenReturn(subject);

    // Run performance test with platform threads
    long platformThreadDuration = measurePerformance(false);
    log.info("Platform thread duration: {} ms", platformThreadDuration);

    // Run performance test with virtual threads
    long virtualThreadDuration = measurePerformance(true);
    log.info("Virtual thread duration: {} ms", virtualThreadDuration);

    // Virtual threads should generally be faster or at least not significantly slower
    // We're using a very lenient assertion here because the test environment may vary
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadDuration, lessThan(platformThreadDuration * 1.5));
  }

  /**
   * Measures the performance of security operations using either platform or virtual threads.
   *
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @return the duration in milliseconds
   */
  private long measurePerformance(boolean useVirtualThreads) throws Exception {
    // Create appropriate executor based on thread type
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(100, Thread.ofPlatform().factory());

    try {
      int operationsPerThread = 100;
      int threadCount = 1000;
      CountDownLatch latch = new CountDownLatch(threadCount * operationsPerThread);

      // Record start time
      long startTime = System.currentTimeMillis();

      // Submit tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          for (int j = 0; j < operationsPerThread; j++) {
            try {
              // Perform a simple security operation
              securitySystem.getSubject().isPermitted("test:permission");
            }
            finally {
              latch.countDown();
            }
          }
        }, executor);

        futures.add(future);
      }

      // Wait for all operations to complete
      latch.await();

      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // Calculate duration
      return System.currentTimeMillis() - startTime;
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}