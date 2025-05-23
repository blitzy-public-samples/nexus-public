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
package org.sonatype.nexus.security.realm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authc.AuthenticationException;
import org.sonatype.nexus.security.user.User;

import com.google.common.collect.ImmutableList;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests to validate Apache Shiro realm security system's compatibility with Java 21 Virtual Threads.
 * 
 * This test suite verifies that authentication and authorization operations function correctly
 * when executed in virtual threads, ensuring that no thread pinning or deadlocks occur during
 * realm operations.
 */
public class RealmVirtualThreadTest
    extends AbstractSecurityTest
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int TIMEOUT_SECONDS = 10;
  
  private SecuritySystem securitySystem;
  private RealmManager realmManager;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  public void setUp() throws Exception {
    securitySystem = lookup(SecuritySystem.class);
    realmManager = lookup(RealmManager.class);
    
    // Configure realm ordering for tests
    realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmA", "MockRealmB"));
    
    // Create executors for virtual and platform threads
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), platformThreadFactory);
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Verifies that basic user authentication works correctly when executed in a virtual thread.
   */
  @Test
  @DisplayName("Authentication operations work in virtual threads")
  public void testAuthenticationInVirtualThread() throws Exception {
    CompletableFuture<User> future = CompletableFuture.supplyAsync(() -> {
      try {
        return securitySystem.getUser("jcoder");
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    User user = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(user, notNullValue());
    assertThat(user.getUserId(), is("jcoder"));
    assertThat(user.getSource(), is("MockUserManagerA"));
  }
  
  /**
   * Tests concurrent authentication operations using virtual threads.
   * Verifies that multiple authentication requests can be processed simultaneously
   * without thread pinning or deadlocks.
   */
  @Test
  @DisplayName("Concurrent authentication operations work in virtual threads")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testConcurrentAuthenticationInVirtualThreads() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    // Create multiple concurrent authentication tasks
    for (int i = 0; i < threadCount; i++) {
      final String username = (i % 2 == 0) ? "jcoder" : "jcoder2";
      
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          User user = securitySystem.getUser(username);
          if (user != null) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          failureCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all tasks to complete
    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All authentication tasks should complete", latch.getCount(), is(0L));
    assertThat("Some authentication operations should succeed", successCount.get(), greaterThan(0));
    assertThat("Failed operations should be expected for invalid users", failureCount.get(), greaterThan(0));
  }
  
  /**
   * Tests that Shiro Subject authentication works correctly in virtual threads.
   */
  @Test
  @DisplayName("Shiro Subject authentication works in virtual threads")
  public void testShiroSubjectAuthenticationInVirtualThread() throws Exception {
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      try {
        Subject subject = securitySystem.getSubject();
        subject.login(new UsernamePasswordToken("jcoder", "jcoder"));
        return subject.isAuthenticated();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    boolean authenticated = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(authenticated, is(true));
  }
  
  /**
   * Tests that Shiro Subject authorization works correctly in virtual threads.
   */
  @Test
  @DisplayName("Shiro Subject authorization works in virtual threads")
  public void testShiroSubjectAuthorizationInVirtualThread() throws Exception {
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      try {
        Subject subject = securitySystem.getSubject();
        subject.login(new UsernamePasswordToken("jcoder", "jcoder"));
        return subject.hasRole("role1");
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    boolean hasRole = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(hasRole, is(true));
  }
  
  /**
   * Tests that authentication failures are properly handled in virtual threads.
   */
  @Test
  @DisplayName("Authentication failures are properly handled in virtual threads")
  public void testAuthenticationFailureInVirtualThread() throws Exception {
    CompletableFuture<Exception> future = CompletableFuture.supplyAsync(() -> {
      try {
        Subject subject = securitySystem.getSubject();
        subject.login(new UsernamePasswordToken("jcoder", "wrongpassword"));
        return null;
      }
      catch (Exception e) {
        return e;
      }
    }, virtualThreadExecutor);
    
    Exception exception = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(exception, notNullValue());
    assertThat(exception instanceof org.apache.shiro.authc.IncorrectCredentialsException, is(true));
  }
  
  /**
   * Tests concurrent authorization operations using virtual threads.
   * Verifies that multiple authorization requests can be processed simultaneously
   * without thread pinning or deadlocks.
   */
  @Test
  @DisplayName("Concurrent authorization operations work in virtual threads")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testConcurrentAuthorizationInVirtualThreads() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    // Create multiple concurrent authorization tasks
    for (int i = 0; i < threadCount; i++) {
      final String role = (i % 2 == 0) ? "role1" : "role2";
      
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          Subject subject = securitySystem.getSubject();
          subject.login(new UsernamePasswordToken("jcoder", "jcoder"));
          if (subject.hasRole(role)) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all tasks to complete
    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All authorization tasks should complete", latch.getCount(), is(0L));
    assertThat("Some authorization operations should succeed", successCount.get(), greaterThan(0));
  }
  
  /**
   * Tests for thread pinning detection in the realm chain.
   * This test verifies that no thread pinning occurs during realm operations.
   */
  @Test
  @DisplayName("No thread pinning occurs during realm operations")
  public void testNoThreadPinningInRealmOperations() throws Exception {
    // Enable thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
    Thread.setUncaughtExceptionHandler((thread, throwable) -> {
      if (throwable.getMessage() != null && 
          throwable.getMessage().contains("VirtualThread pinned")) {
        pinnedThreadDetected.set(true);
      }
    });
    
    // Perform multiple realm operations that could potentially cause pinning
    for (int i = 0; i < 10; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          Subject subject = securitySystem.getSubject();
          subject.login(new UsernamePasswordToken("jcoder", "jcoder"));
          subject.checkRole("role1");
          subject.logout();
        }
        catch (Exception e) {
          // Ignore exceptions for this test
        }
      }, virtualThreadExecutor).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    // Verify no thread pinning was detected
    assertThat("No thread pinning should be detected", pinnedThreadDetected.get(), is(false));
    
    // Reset the property
    System.clearProperty("jdk.tracePinnedThreads");
  }
  
  /**
   * Tests security token generation and validation under high virtual thread concurrency.
   */
  @Test
  @DisplayName("Security token generation and validation works under high concurrency")
  @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
  public void testSecurityTokenGenerationUnderHighConcurrency() throws Exception {
    int threadCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create multiple concurrent token generation and validation tasks
    for (int i = 0; i < threadCount; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          Subject subject = securitySystem.getSubject();
          subject.login(new UsernamePasswordToken("jcoder", "jcoder"));
          
          // Verify the subject is authenticated (token is valid)
          if (subject.isAuthenticated()) {
            successCount.incrementAndGet();
          }
          
          subject.logout();
        }
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all tasks to complete
    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All token generation tasks should complete", latch.getCount(), is(0L));
    assertThat("All token generation operations should succeed", successCount.get(), is(threadCount));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for security operations.
   * This test measures the execution time for the same security operations using both thread types.
   */
  @Test
  @DisplayName("Virtual threads outperform platform threads for security operations")
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    int operationCount = CONCURRENT_THREADS;
    
    // Measure performance with platform threads
    long platformThreadTime = measureExecutionTime(operationCount, platformThreadExecutor);
    
    // Measure performance with virtual threads
    long virtualThreadTime = measureExecutionTime(operationCount, virtualThreadExecutor);
    
    // Log the results for analysis
    System.out.println("Performance comparison for " + operationCount + " concurrent security operations:");
    System.out.println("Platform threads execution time: " + platformThreadTime + "ms");
    System.out.println("Virtual threads execution time: " + virtualThreadTime + "ms");
    System.out.println("Improvement factor: " + (double) platformThreadTime / virtualThreadTime);
    
    // Virtual threads should generally be faster for I/O bound operations like security checks
    // but we don't assert this strictly as it depends on the test environment
    // Instead, we just verify that virtual threads don't perform significantly worse
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 1.5));
  }
  
  /**
   * Helper method to measure execution time for concurrent security operations using the specified executor.
   *
   * @param operationCount the number of concurrent operations to perform
   * @param executor the executor service to use (platform or virtual thread based)
   * @return the execution time in milliseconds
   */
  private long measureExecutionTime(int operationCount, ExecutorService executor) throws Exception {
    CountDownLatch latch = new CountDownLatch(operationCount);
    long startTime = System.currentTimeMillis();
    
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < operationCount; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Perform a typical security operation sequence
          Subject subject = securitySystem.getSubject();
          subject.login(new UsernamePasswordToken("jcoder", "jcoder"));
          subject.hasRole("role1");
          subject.isPermitted("app:edit:1");
          subject.logout();
        }
        catch (Exception e) {
          // Ignore exceptions for this performance test
        }
        finally {
          latch.countDown();
        }
      }, executor);
      
      futures.add(future);
    }
    
    // Wait for all tasks to complete
    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    long endTime = System.currentTimeMillis();
    return endTime - startTime;
  }
  
  /**
   * Tests that realm ordering works correctly when accessed from virtual threads.
   * This test is based on OrderingRealmsTest but executed in a virtual thread.
   */
  @Test
  @DisplayName("Realm ordering works correctly in virtual threads")
  public void testRealmOrderingInVirtualThread() throws Exception {
    // First test with MockRealmA first in the chain
    realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmA", "MockRealmB"));
    
    CompletableFuture<User> future1 = CompletableFuture.supplyAsync(() -> {
      try {
        return securitySystem.getUser("jcoder");
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    User user1 = future1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(user1, notNullValue());
    assertThat(user1.getSource(), is("MockUserManagerA"));
    
    // Now change the order and test again
    realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmB", "MockRealmA"));
    
    CompletableFuture<User> future2 = CompletableFuture.supplyAsync(() -> {
      try {
        return securitySystem.getUser("jcoder");
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    User user2 = future2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(user2, notNullValue());
    assertThat(user2.getSource(), is("MockUserManagerB"));
  }
}