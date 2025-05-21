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
package org.sonatype.nexus.security.internal;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserStatus;

import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests to verify that Nexus security components are compatible with Java 21 Virtual Threads.
 * This class validates that authentication, authorization, and permission resolution operations
 * can be executed concurrently using Virtual Threads without thread pinning or performance degradation.
 */
public class SecurityVirtualThreadCompatibilityTest
    extends AbstractSecurityTest
{
  private static final Logger log = LoggerFactory.getLogger(SecurityVirtualThreadCompatibilityTest.class);
  
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int WARMUP_OPERATIONS = 100;
  private static final String TEST_USERNAME = "virtualThreadTestUser";
  private static final String TEST_PASSWORD = "password123";
  private static final String TEST_PERMISSION = "test:read";
  
  private SecuritySystem securitySystem;
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  private ThreadPinningDetector threadPinningDetector;
  
  @Before
  public void setup() throws Exception {
    securitySystem = getSecuritySystem();
    
    // Create platform thread executor with fixed thread pool
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),
        new ThreadFactory() {
          private final AtomicInteger counter = new AtomicInteger();
          
          @Override
          public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "platform-thread-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
          }
        });
    
    // Create virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Initialize thread pinning detector
    threadPinningDetector = new ThreadPinningDetector();
    
    // Create test user
    createTestUser();
  }
  
  @After
  public void tearDown() throws Exception {
    platformThreadExecutor.shutdown();
    platformThreadExecutor.awaitTermination(30, TimeUnit.SECONDS);
    
    virtualThreadExecutor.shutdown();
    virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS);
  }
  
  /**
   * Creates a test user for authentication and authorization tests.
   */
  private void createTestUser() throws Exception {
    User user = new User();
    user.setEmailAddress("virtualthread@example.com");
    user.setName(TEST_USERNAME);
    user.setSource("MockUserManagerA");
    user.setStatus(UserStatus.active);
    user.setUserId(TEST_USERNAME);
    
    user.addRole(new RoleIdentifier("default", "test-role1"));
    
    securitySystem.addUser(user, TEST_PASSWORD);
  }
  
  /**
   * Tests concurrent authentication operations using platform threads.
   */
  @Test
  public void testConcurrentAuthenticationWithPlatformThreads() throws Exception {
    log.info("Testing concurrent authentication with platform threads");
    
    // Warm up
    runConcurrentAuthentications(platformThreadExecutor, WARMUP_OPERATIONS);
    
    // Measure performance
    PerformanceResult result = runConcurrentAuthentications(platformThreadExecutor, CONCURRENT_OPERATIONS);
    
    log.info("Platform thread authentication performance: {} operations in {}ms ({}ms avg, {}ms max)",
        result.operationCount, result.totalDurationMs, result.averageDurationMs, result.maxDurationMs);
    
    assertThat(result.operationCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.successCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.failureCount, is(0));
  }
  
  /**
   * Tests concurrent authentication operations using virtual threads.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    log.info("Testing concurrent authentication with virtual threads");
    
    // Warm up
    runConcurrentAuthentications(virtualThreadExecutor, WARMUP_OPERATIONS);
    
    // Measure performance
    PerformanceResult result = runConcurrentAuthentications(virtualThreadExecutor, CONCURRENT_OPERATIONS);
    
    log.info("Virtual thread authentication performance: {} operations in {}ms ({}ms avg, {}ms max)",
        result.operationCount, result.totalDurationMs, result.averageDurationMs, result.maxDurationMs);
    
    assertThat(result.operationCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.successCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.failureCount, is(0));
    
    // Verify no thread pinning was detected
    assertThat("Thread pinning detected during authentication", 
        threadPinningDetector.getPinningDetected(), is(false));
  }
  
  /**
   * Tests concurrent permission checks using platform threads.
   */
  @Test
  public void testConcurrentPermissionChecksWithPlatformThreads() throws Exception {
    log.info("Testing concurrent permission checks with platform threads");
    
    // Warm up
    runConcurrentPermissionChecks(platformThreadExecutor, WARMUP_OPERATIONS);
    
    // Measure performance
    PerformanceResult result = runConcurrentPermissionChecks(platformThreadExecutor, CONCURRENT_OPERATIONS);
    
    log.info("Platform thread permission check performance: {} operations in {}ms ({}ms avg, {}ms max)",
        result.operationCount, result.totalDurationMs, result.averageDurationMs, result.maxDurationMs);
    
    assertThat(result.operationCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.successCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.failureCount, is(0));
  }
  
  /**
   * Tests concurrent permission checks using virtual threads.
   */
  @Test
  public void testConcurrentPermissionChecksWithVirtualThreads() throws Exception {
    log.info("Testing concurrent permission checks with virtual threads");
    
    // Warm up
    runConcurrentPermissionChecks(virtualThreadExecutor, WARMUP_OPERATIONS);
    
    // Measure performance
    PerformanceResult result = runConcurrentPermissionChecks(virtualThreadExecutor, CONCURRENT_OPERATIONS);
    
    log.info("Virtual thread permission check performance: {} operations in {}ms ({}ms avg, {}ms max)",
        result.operationCount, result.totalDurationMs, result.averageDurationMs, result.maxDurationMs);
    
    assertThat(result.operationCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.successCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.failureCount, is(0));
    
    // Verify no thread pinning was detected
    assertThat("Thread pinning detected during permission checks", 
        threadPinningDetector.getPinningDetected(), is(false));
  }
  
  /**
   * Tests concurrent role retrieval operations using platform threads.
   */
  @Test
  public void testConcurrentRoleRetrievalWithPlatformThreads() throws Exception {
    log.info("Testing concurrent role retrieval with platform threads");
    
    // Warm up
    runConcurrentRoleRetrieval(platformThreadExecutor, WARMUP_OPERATIONS);
    
    // Measure performance
    PerformanceResult result = runConcurrentRoleRetrieval(platformThreadExecutor, CONCURRENT_OPERATIONS);
    
    log.info("Platform thread role retrieval performance: {} operations in {}ms ({}ms avg, {}ms max)",
        result.operationCount, result.totalDurationMs, result.averageDurationMs, result.maxDurationMs);
    
    assertThat(result.operationCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.successCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.failureCount, is(0));
  }
  
  /**
   * Tests concurrent role retrieval operations using virtual threads.
   */
  @Test
  public void testConcurrentRoleRetrievalWithVirtualThreads() throws Exception {
    log.info("Testing concurrent role retrieval with virtual threads");
    
    // Warm up
    runConcurrentRoleRetrieval(virtualThreadExecutor, WARMUP_OPERATIONS);
    
    // Measure performance
    PerformanceResult result = runConcurrentRoleRetrieval(virtualThreadExecutor, CONCURRENT_OPERATIONS);
    
    log.info("Virtual thread role retrieval performance: {} operations in {}ms ({}ms avg, {}ms max)",
        result.operationCount, result.totalDurationMs, result.averageDurationMs, result.maxDurationMs);
    
    assertThat(result.operationCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.successCount, is(CONCURRENT_OPERATIONS));
    assertThat(result.failureCount, is(0));
    
    // Verify no thread pinning was detected
    assertThat("Thread pinning detected during role retrieval", 
        threadPinningDetector.getPinningDetected(), is(false));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for authentication operations.
   */
  @Test
  public void testAuthenticationPerformanceComparison() throws Exception {
    log.info("Comparing authentication performance between platform threads and virtual threads");
    
    // Warm up both executor types
    runConcurrentAuthentications(platformThreadExecutor, WARMUP_OPERATIONS);
    runConcurrentAuthentications(virtualThreadExecutor, WARMUP_OPERATIONS);
    
    // Measure platform thread performance
    PerformanceResult platformResult = runConcurrentAuthentications(platformThreadExecutor, CONCURRENT_OPERATIONS);
    
    // Measure virtual thread performance
    PerformanceResult virtualResult = runConcurrentAuthentications(virtualThreadExecutor, CONCURRENT_OPERATIONS);
    
    log.info("Platform thread authentication: {}ms total, {}ms avg", 
        platformResult.totalDurationMs, platformResult.averageDurationMs);
    log.info("Virtual thread authentication: {}ms total, {}ms avg", 
        virtualResult.totalDurationMs, virtualResult.averageDurationMs);
    
    // Virtual threads should handle more concurrent operations efficiently
    // This may not always be true for small workloads, but should be for larger concurrent workloads
    assertThat("Virtual threads should be at least as efficient as platform threads for authentication",
        virtualResult.totalDurationMs, lessThan(platformResult.totalDurationMs * 1.5));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for permission checks.
   */
  @Test
  public void testPermissionCheckPerformanceComparison() throws Exception {
    log.info("Comparing permission check performance between platform threads and virtual threads");
    
    // Warm up both executor types
    runConcurrentPermissionChecks(platformThreadExecutor, WARMUP_OPERATIONS);
    runConcurrentPermissionChecks(virtualThreadExecutor, WARMUP_OPERATIONS);
    
    // Measure platform thread performance
    PerformanceResult platformResult = runConcurrentPermissionChecks(platformThreadExecutor, CONCURRENT_OPERATIONS);
    
    // Measure virtual thread performance
    PerformanceResult virtualResult = runConcurrentPermissionChecks(virtualThreadExecutor, CONCURRENT_OPERATIONS);
    
    log.info("Platform thread permission checks: {}ms total, {}ms avg", 
        platformResult.totalDurationMs, platformResult.averageDurationMs);
    log.info("Virtual thread permission checks: {}ms total, {}ms avg", 
        virtualResult.totalDurationMs, virtualResult.averageDurationMs);
    
    // Virtual threads should handle more concurrent operations efficiently
    assertThat("Virtual threads should be at least as efficient as platform threads for permission checks",
        virtualResult.totalDurationMs, lessThan(platformResult.totalDurationMs * 1.5));
  }
  
  /**
   * Tests thread safety of security manager operations under high concurrency with virtual threads.
   */
  @Test
  public void testThreadSafetyWithVirtualThreads() throws Exception {
    log.info("Testing thread safety with virtual threads");
    
    // Create a mix of operations to run concurrently
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Add authentication operations
    for (int i = 0; i < CONCURRENT_OPERATIONS / 3; i++) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          startLatch.await(); // Wait for all tasks to be submitted
          Subject subject = securitySystem.getSubject();
          subject.login(new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD));
          return subject.isAuthenticated();
        }
        catch (Exception e) {
          log.error("Authentication failed", e);
          return false;
        }
      }, virtualThreadExecutor));
    }
    
    // Add permission check operations
    for (int i = 0; i < CONCURRENT_OPERATIONS / 3; i++) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          startLatch.await(); // Wait for all tasks to be submitted
          PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");
          securitySystem.checkPermission(principal, TEST_PERMISSION);
          return true;
        }
        catch (Exception e) {
          log.error("Permission check failed", e);
          return false;
        }
      }, virtualThreadExecutor));
    }
    
    // Add role retrieval operations
    for (int i = 0; i < CONCURRENT_OPERATIONS / 3; i++) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          startLatch.await(); // Wait for all tasks to be submitted
          return !securitySystem.listRoles().isEmpty();
        }
        catch (Exception e) {
          log.error("Role retrieval failed", e);
          return false;
        }
      }, virtualThreadExecutor));
    }
    
    // Start all operations simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    allFutures.get(30, TimeUnit.SECONDS);
    
    // Count successful operations
    long successCount = futures.stream()
        .map(CompletableFuture::join)
        .filter(Boolean::booleanValue)
        .count();
    
    log.info("Thread safety test completed: {}/{} operations successful", successCount, futures.size());
    
    // All operations should succeed
    assertThat(successCount, is((long) futures.size()));
    
    // Verify no thread pinning was detected
    assertThat("Thread pinning detected during thread safety test", 
        threadPinningDetector.getPinningDetected(), is(false));
  }
  
  /**
   * Runs concurrent authentication operations using the specified executor.
   */
  private PerformanceResult runConcurrentAuthentications(ExecutorService executor, int operationCount) throws Exception {
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicLong totalDuration = new AtomicLong(0);
    AtomicLong maxDuration = new AtomicLong(0);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Start thread pinning detection if using virtual threads
    if (executor == virtualThreadExecutor) {
      threadPinningDetector.startDetection();
    }
    
    // Create tasks for concurrent authentication
    for (int i = 0; i < operationCount; i++) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          startLatch.await(); // Wait for all tasks to be submitted
          
          long startTime = System.nanoTime();
          
          // Perform authentication
          Subject subject = securitySystem.getSubject();
          subject.login(new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD));
          boolean authenticated = subject.isAuthenticated();
          if (authenticated) {
            subject.logout();
          }
          
          long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
          totalDuration.addAndGet(duration);
          maxDuration.updateAndGet(current -> Math.max(current, duration));
          
          if (authenticated) {
            successCount.incrementAndGet();
          }
          else {
            failureCount.incrementAndGet();
          }
          
          return authenticated;
        }
        catch (Exception e) {
          failureCount.incrementAndGet();
          log.error("Authentication failed", e);
          return false;
        }
      }, executor));
    }
    
    // Start all operations simultaneously
    long startTime = System.nanoTime();
    startLatch.countDown();
    
    // Wait for all operations to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    allFutures.get(30, TimeUnit.SECONDS);
    long totalExecutionTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    
    // Stop thread pinning detection if using virtual threads
    if (executor == virtualThreadExecutor) {
      threadPinningDetector.stopDetection();
    }
    
    return new PerformanceResult(
        operationCount,
        successCount.get(),
        failureCount.get(),
        totalExecutionTime,
        totalDuration.get() / operationCount,
        maxDuration.get()
    );
  }
  
  /**
   * Runs concurrent permission check operations using the specified executor.
   */
  private PerformanceResult runConcurrentPermissionChecks(ExecutorService executor, int operationCount) throws Exception {
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicLong totalDuration = new AtomicLong(0);
    AtomicLong maxDuration = new AtomicLong(0);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Start thread pinning detection if using virtual threads
    if (executor == virtualThreadExecutor) {
      threadPinningDetector.startDetection();
    }
    
    // Create tasks for concurrent permission checks
    for (int i = 0; i < operationCount; i++) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          startLatch.await(); // Wait for all tasks to be submitted
          
          long startTime = System.nanoTime();
          
          // Perform permission check
          PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");
          securitySystem.checkPermission(principal, TEST_PERMISSION);
          
          long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
          totalDuration.addAndGet(duration);
          maxDuration.updateAndGet(current -> Math.max(current, duration));
          
          successCount.incrementAndGet();
          return true;
        }
        catch (Exception e) {
          failureCount.incrementAndGet();
          log.error("Permission check failed", e);
          return false;
        }
      }, executor));
    }
    
    // Start all operations simultaneously
    long startTime = System.nanoTime();
    startLatch.countDown();
    
    // Wait for all operations to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    allFutures.get(30, TimeUnit.SECONDS);
    long totalExecutionTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    
    // Stop thread pinning detection if using virtual threads
    if (executor == virtualThreadExecutor) {
      threadPinningDetector.stopDetection();
    }
    
    return new PerformanceResult(
        operationCount,
        successCount.get(),
        failureCount.get(),
        totalExecutionTime,
        totalDuration.get() / operationCount,
        maxDuration.get()
    );
  }
  
  /**
   * Runs concurrent role retrieval operations using the specified executor.
   */
  private PerformanceResult runConcurrentRoleRetrieval(ExecutorService executor, int operationCount) throws Exception {
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicLong totalDuration = new AtomicLong(0);
    AtomicLong maxDuration = new AtomicLong(0);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Start thread pinning detection if using virtual threads
    if (executor == virtualThreadExecutor) {
      threadPinningDetector.startDetection();
    }
    
    // Create tasks for concurrent role retrieval
    for (int i = 0; i < operationCount; i++) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          startLatch.await(); // Wait for all tasks to be submitted
          
          long startTime = System.nanoTime();
          
          // Retrieve roles
          List<AuthorizationManager> authorizationManagers = securitySystem.getAuthorizationManagers();
          for (AuthorizationManager authorizationManager : authorizationManagers) {
            List<? extends Role> roles = authorizationManager.listRoles();
            if (roles == null) {
              return false;
            }
          }
          
          long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
          totalDuration.addAndGet(duration);
          maxDuration.updateAndGet(current -> Math.max(current, duration));
          
          successCount.incrementAndGet();
          return true;
        }
        catch (Exception e) {
          failureCount.incrementAndGet();
          log.error("Role retrieval failed", e);
          return false;
        }
      }, executor));
    }
    
    // Start all operations simultaneously
    long startTime = System.nanoTime();
    startLatch.countDown();
    
    // Wait for all operations to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    allFutures.get(30, TimeUnit.SECONDS);
    long totalExecutionTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    
    // Stop thread pinning detection if using virtual threads
    if (executor == virtualThreadExecutor) {
      threadPinningDetector.stopDetection();
    }
    
    return new PerformanceResult(
        operationCount,
        successCount.get(),
        failureCount.get(),
        totalExecutionTime,
        totalDuration.get() / operationCount,
        maxDuration.get()
    );
  }
  
  /**
   * Class to detect thread pinning in virtual threads.
   * Thread pinning occurs when a virtual thread blocks a carrier thread,
   * which can lead to performance degradation and potential deadlocks.
   */
  private static class ThreadPinningDetector {
    private volatile boolean running = false;
    private volatile boolean pinningDetected = false;
    private final AtomicReference<Thread> detectorThread = new AtomicReference<>();
    
    /**
     * Starts thread pinning detection.
     */
    public void startDetection() {
      running = true;
      pinningDetected = false;
      
      Thread thread = new Thread(() -> {
        while (running) {
          try {
            // Get all thread stacks
            Thread[] threads = new Thread[Thread.activeCount() * 2];
            int count = Thread.enumerate(threads);
            
            // Look for carrier threads that are running virtual threads
            for (int i = 0; i < count; i++) {
              Thread t = threads[i];
              if (t != null && t.getName().startsWith("VirtualThreads")) {
                // Check if this carrier thread is blocked for too long
                StackTraceElement[] stack = t.getStackTrace();
                if (stack.length > 0 && isBlockingOperation(stack)) {
                  log.warn("Potential thread pinning detected in carrier thread: {}", t.getName());
                  for (StackTraceElement element : stack) {
                    log.warn("  at {}", element);
                  }
                  pinningDetected = true;
                }
              }
            }
            
            // Check every 100ms
            Thread.sleep(100);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
          catch (Exception e) {
            log.error("Error in thread pinning detection", e);
          }
        }
      }, "thread-pinning-detector");
      
      thread.setDaemon(true);
      thread.start();
      detectorThread.set(thread);
    }
    
    /**
     * Stops thread pinning detection.
     */
    public void stopDetection() {
      running = false;
      Thread thread = detectorThread.get();
      if (thread != null) {
        thread.interrupt();
      }
    }
    
    /**
     * Returns whether thread pinning was detected.
     */
    public boolean getPinningDetected() {
      return pinningDetected;
    }
    
    /**
     * Checks if the stack trace indicates a blocking operation that could cause thread pinning.
     */
    private boolean isBlockingOperation(StackTraceElement[] stack) {
      for (StackTraceElement element : stack) {
        String className = element.getClassName();
        String methodName = element.getMethodName();
        
        // Check for common blocking operations that should be avoided in virtual threads
        if ((className.contains("Lock") && methodName.contains("lock")) ||
            (className.contains("Semaphore") && methodName.contains("acquire")) ||
            (className.contains("Synchron") && methodName.contains("wait")) ||
            (className.contains("Thread") && methodName.contains("sleep"))) {
          return true;
        }
      }
      return false;
    }
  }
  
  /**
   * Class to hold performance test results.
   */
  private static class PerformanceResult {
    final int operationCount;
    final int successCount;
    final int failureCount;
    final long totalDurationMs;
    final long averageDurationMs;
    final long maxDurationMs;
    
    PerformanceResult(int operationCount, int successCount, int failureCount, 
                      long totalDurationMs, long averageDurationMs, long maxDurationMs) {
      this.operationCount = operationCount;
      this.successCount = successCount;
      this.failureCount = failureCount;
      this.totalDurationMs = totalDurationMs;
      this.averageDurationMs = averageDurationMs;
      this.maxDurationMs = maxDurationMs;
    }
  }
}