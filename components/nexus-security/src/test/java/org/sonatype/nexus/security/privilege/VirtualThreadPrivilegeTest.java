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
package org.sonatype.nexus.security.privilege;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.PermissionResolver;
import org.apache.shiro.authz.permission.WildcardPermissionResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests for privilege operations using Java 21's Virtual Threads.
 */
public class VirtualThreadPrivilegeTest
    extends TestSupport
{
  private final PermissionResolver permissionResolver = new WildcardPermissionResolver();

  /**
   * Tests basic permission implication with a virtual thread.
   */
  @Test
  public void testBasicPermissionImplicationWithVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
      Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");
      
      assertThat(authorizedPermission.implies(requiredPermission), is(true));
    }).join();
  }

  /**
   * Tests concurrent permission evaluation with a high number of virtual threads.
   * This test creates 1000 virtual threads, each evaluating a permission implication.
   */
  @Test
  @Timeout(value = 30)
  public void testConcurrentPermissionEvaluationWithVirtualThreads() throws Exception {
    int threadCount = 1000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique permission for each thread to avoid any caching effects
            Permission authorizedPermission = new ApplicationPermission("feature" + index, "action", "anotherAction");
            Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature" + index + ":action");
            
            if (authorizedPermission.implies(requiredPermission)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete or timeout
      latch.await(20, TimeUnit.SECONDS);
      
      // Verify all permission checks were successful
      assertThat(successCount.get(), is(threadCount));
    }
  }

  /**
   * Tests complex permission evaluation with nested permissions using virtual threads.
   */
  @Test
  public void testComplexPermissionEvaluationWithVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < 100; i++) {
        futures.add(executor.submit(() -> {
          Permission authorizedPermission = new ApplicationPermission("feature:method", "action", "anotherAction");
          Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:method:action");
          
          assertThat(authorizedPermission.implies(requiredPermission), is(true));
          
          // Test with wildcard permissions
          Permission wildcardPermission = new ApplicationPermission("feature:*", "action", "anotherAction");
          Permission specificPermission = permissionResolver.resolvePermission("nexus:feature:specific:action");
          
          assertThat(wildcardPermission.implies(specificPermission), is(true));
        }));
      }
      
      // Wait for all futures to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for privilege operations.
   * This test creates the same number of platform and virtual threads and measures execution time.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    int threadCount = 500;
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(50)) { // Limited pool size for platform threads
        runConcurrentPermissionChecks(executor, threadCount);
      }
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        runConcurrentPermissionChecks(executor, threadCount);
      }
    });
    
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should generally be more efficient for this I/O-bound operation
    // but this is not a strict requirement as it depends on the environment
    // We're logging the times for informational purposes
  }

  /**
   * Tests high concurrency with a very large number of virtual threads.
   * This test creates 10,000 virtual threads to verify scalability.
   */
  @Test
  @Timeout(value = 60)
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    int threadCount = 10_000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
            Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");
            
            if (authorizedPermission.implies(requiredPermission)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete or timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all permission checks were successful
      assertThat("All threads should complete in time", completed, is(true));
      assertThat(successCount.get(), is(threadCount));
    }
  }

  /**
   * Tests permission resolution under load with simulated delay to verify
   * that virtual threads handle blocking operations efficiently.
   */
  @Test
  @Timeout(value = 30)
  public void testPermissionResolutionUnderLoadWithDelay() throws Exception {
    int threadCount = 200;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Simulate some I/O or processing delay
            Thread.sleep(50);
            
            Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
            Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");
            
            assertThat(authorizedPermission.implies(requiredPermission), is(true));
            
            // Additional delay after permission check
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // With platform threads, this would take at least threadCount * 100ms sequentially
      // With virtual threads, it should be much faster due to efficient scheduling
      long startTime = System.currentTimeMillis();
      latch.await(10, TimeUnit.SECONDS);
      long duration = System.currentTimeMillis() - startTime;
      
      // The total time should be significantly less than sequential execution time
      // which would be threadCount * 100ms
      long sequentialTime = threadCount * 100L;
      log.info("Concurrent execution time: {} ms, Sequential would be: {} ms", duration, sequentialTime);
      
      // Should be significantly faster than sequential execution
      assertThat(duration, lessThan(sequentialTime / 2));
    }
  }

  /**
   * Helper method to run concurrent permission checks using the provided executor.
   */
  private void runConcurrentPermissionChecks(ExecutorService executor, int threadCount) throws Exception {
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<Future<?>> futures = new ArrayList<>();
    
    for (int i = 0; i < threadCount; i++) {
      futures.add(executor.submit(() -> {
        try {
          Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
          Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");
          
          assertThat(authorizedPermission.implies(requiredPermission), is(true));
        } finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all threads to complete or timeout
    latch.await(20, TimeUnit.SECONDS);
    
    // Check for any exceptions
    for (Future<?> future : futures) {
      future.get(1, TimeUnit.SECONDS); // This will throw if any task failed
    }
  }

  /**
   * Helper method to measure execution time of a runnable.
   */
  private long measureExecutionTime(Runnable runnable) throws Exception {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }
}