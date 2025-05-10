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
package org.sonatype.nexus.repository.security;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.view.Request;

import org.apache.shiro.authz.AuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.BreadActions.READ;

@ExtendWith(MockitoExtension.class)
public class SecurityFacetSupportTest
    extends TestSupport
{
  private static final class TestSecurityFacetSupport
      extends SecurityFacetSupport
  {
    public TestSecurityFacetSupport(final RepositoryFormatSecurityContributor securityContributor,
                                    final VariableResolverAdapter variableResolverAdapter,
                                    final ContentPermissionChecker contentPermissionChecker)
    {
      super(securityContributor, variableResolverAdapter, contentPermissionChecker);
    }
  }

  @Mock
  Request request;

  @Mock
  Repository repository;

  @Mock
  ContentPermissionChecker contentPermissionChecker;

  @Mock
  VariableResolverAdapter variableResolverAdapter;

  @Mock
  RepositoryFormatSecurityContributor securityContributor;

  TestSecurityFacetSupport testSecurityFacetSupport;

  @BeforeEach
  public void setupConfig() throws Exception {
    when(request.getPath()).thenReturn("/some/path.txt");
    when(request.getAction()).thenReturn(HttpMethods.GET);

    when(repository.getFormat()).thenReturn(new Format("test") { });
    when(repository.getName()).thenReturn("SecurityFacetSupportTest");

    testSecurityFacetSupport = new TestSecurityFacetSupport(securityContributor,
        variableResolverAdapter, contentPermissionChecker);

    testSecurityFacetSupport.attach(repository);
  }

  @Test
  public void testEnsurePermitted_permitted() throws Exception {
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(true);
    // No exception should be thrown
    testSecurityFacetSupport.ensurePermitted(request);
  }

  @Test
  public void testEnsurePermitted_notPermitted() throws Exception {
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(false);

    assertThrows(AuthorizationException.class, () -> {
      testSecurityFacetSupport.ensurePermitted(request);
    }, "AuthorizationException should have been thrown");

    verify(contentPermissionChecker).isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any());
  }
  
  @Test
  public void testConcurrentPermissionChecks() throws Exception {
    // Configure mock to return true for permission checks
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(true);
    
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            testSecurityFacetSupport.ensurePermitted(request);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            // Unexpected exception
            log.error("Unexpected exception during concurrent permission check", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      
      // Verify all permission checks were successful
      assertEquals(threadCount, successCount.get(), "Not all permission checks were successful");
      
      // Verify the permission checker was called the expected number of times
      verify(contentPermissionChecker, times(threadCount))
          .isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any());
    }
  }
  
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Configure mock to simulate a slow permission check that could cause thread pinning
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenAnswer(invocation -> {
          // Simulate a slow operation that might cause thread pinning if not handled properly
          Thread.sleep(50);
          return true;
        });
    
    int threadCount = 20;
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger concurrentExecutions = new AtomicInteger(0);
    AtomicInteger maxConcurrent = new AtomicInteger(0);
    
    // Use system property to detect thread pinning if enabled
    // Note: In a real environment, you would use -Djdk.tracePinnedThreads=full
    String originalPinningProperty = System.getProperty("jdk.tracePinnedThreads");
    try {
      // Create virtual thread executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit multiple concurrent tasks using virtual threads
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              // Track concurrent executions to verify virtual threads are not being pinned
              int current = concurrentExecutions.incrementAndGet();
              maxConcurrent.updateAndGet(max -> Math.max(max, current));
              
              // Perform the permission check that might cause pinning
              testSecurityFacetSupport.ensurePermitted(request);
            } 
            finally {
              concurrentExecutions.decrementAndGet();
              completionLatch.countDown();
            }
          });
        }
        
        // Wait for all threads to complete
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      }
      
      // If virtual threads are working correctly without pinning, we should have seen
      // close to threadCount concurrent executions (allowing for some scheduling variation)
      log.info("Maximum concurrent executions: {}", maxConcurrent.get());
      assertTrue(maxConcurrent.get() > 1, 
          "Expected multiple concurrent executions, but max was: " + maxConcurrent.get());
      
      // Verify the permission checker was called the expected number of times
      verify(contentPermissionChecker, times(threadCount))
          .isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any());
    }
    finally {
      // Restore original system property
      if (originalPinningProperty != null) {
        System.setProperty("jdk.tracePinnedThreads", originalPinningProperty);
      } else {
        System.clearProperty("jdk.tracePinnedThreads");
      }
    }
  }
  
  @Test
  public void testAlternatingPermissionResults() throws Exception {
    // Configure mock to alternate between permitted and not permitted
    AtomicInteger callCount = new AtomicInteger(0);
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenAnswer(invocation -> callCount.incrementAndGet() % 2 == 0); // Even calls return true, odd calls return false
    
    int threadCount = 10;
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger permittedCount = new AtomicInteger(0);
    AtomicInteger deniedCount = new AtomicInteger(0);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            testSecurityFacetSupport.ensurePermitted(request);
            permittedCount.incrementAndGet();
          } 
          catch (AuthorizationException e) {
            deniedCount.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
    }
    
    // Verify we got the expected mix of permitted and denied results
    assertEquals(threadCount, permittedCount.get() + deniedCount.get(), 
        "Total of permitted and denied counts should equal thread count");
    
    // We should have approximately half permitted and half denied
    log.info("Permission results - Permitted: {}, Denied: {}", permittedCount.get(), deniedCount.get());
    
    // Verify the permission checker was called the expected number of times
    verify(contentPermissionChecker, times(threadCount))
        .isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any());
  }
}