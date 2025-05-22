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
package org.sonatype.nexus.repository.maven.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.security.BreadActions;
import org.sonatype.nexus.testcommon.VirtualThreadTestGroup;

import org.apache.shiro.authz.AuthorizationException;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenSecurityFacet} with Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
@Category(VirtualThreadTestGroup.class)
public class MavenSecurityFacetVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 50;
  private static final int TIMEOUT_SECONDS = 10;

  @Mock
  Request request;

  @Mock
  Repository repository;

  @Mock
  ContentPermissionChecker contentPermissionChecker;

  @Mock
  MavenFormatSecurityContributor securityContributor;

  @Mock
  VariableResolverAdapter variableResolverAdapter;

  MavenSecurityFacet mavenSecurityFacet;

  @BeforeEach
  public void setupConfig() {
    when(request.getPath()).thenReturn("/mygroupid/myartifactid/1.0/myartifactid-1.0.jar");
    when(request.getAction()).thenReturn(HttpMethods.GET);

    when(repository.getFormat()).thenReturn(new Maven2Format());
    when(repository.getName()).thenReturn("MavenSecurityFacetTest");

    mavenSecurityFacet = new MavenSecurityFacet(securityContributor,
        variableResolverAdapter, contentPermissionChecker);

    mavenSecurityFacet.attach(repository);
  }

  @Test
  public void testEnsurePermittedWithVirtualThreads() throws Exception {
    when(contentPermissionChecker
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any()))
        .thenReturn(true);

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger failureCount = new AtomicInteger(0);

    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Test the permission check
            mavenSecurityFacet.ensurePermitted(request);
          }
          catch (Exception e) {
            failureCount.incrementAndGet();
            log.error("Virtual thread test failed", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      if (!completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail("Test timed out waiting for virtual threads to complete");
      }
      
      // Verify no failures occurred
      if (failureCount.get() > 0) {
        fail("Expected permitted operations to succeed, but " + failureCount.get() + " failures occurred");
      }
    }
  }

  @Test
  public void testEnsurePermitted_notPermittedWithVirtualThreads() throws Exception {
    // Configure the mock to return false for permission check
    when(contentPermissionChecker
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any()))
        .thenReturn(false);

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);

    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Test the permission check - should throw AuthorizationException
            assertThrows(AuthorizationException.class, () -> {
              mavenSecurityFacet.ensurePermitted(request);
            });
            
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Virtual thread test failed", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      if (!completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail("Test timed out waiting for virtual threads to complete");
      }
      
      // Verify all threads correctly detected the authorization failure
      if (successCount.get() != CONCURRENT_THREADS) {
        fail("Expected all " + CONCURRENT_THREADS + " threads to detect authorization failure, but only " 
            + successCount.get() + " did");
      }
    }
    
    // Verify the permission check was called
    verify(contentPermissionChecker)
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any());
  }

  @Test
  public void testEnsurePermittedWithMixedPermissionsInVirtualThreads() throws Exception {
    // Set up a counter to track which thread is calling
    AtomicInteger threadCounter = new AtomicInteger(0);
    
    // Configure the mock to alternate between permitted and not permitted
    when(contentPermissionChecker
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any()))
        .thenAnswer(invocation -> threadCounter.incrementAndGet() % 2 == 0);

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger permittedCount = new AtomicInteger(0);
    AtomicInteger notPermittedCount = new AtomicInteger(0);

    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            try {
              mavenSecurityFacet.ensurePermitted(request);
              permittedCount.incrementAndGet();
            }
            catch (AuthorizationException e) {
              notPermittedCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Virtual thread test failed", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      if (!completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail("Test timed out waiting for virtual threads to complete");
      }
      
      // Verify we got a mix of permitted and not permitted results
      log.info("Permitted: {}, Not Permitted: {}", permittedCount.get(), notPermittedCount.get());
      if (permittedCount.get() == 0 || notPermittedCount.get() == 0) {
        fail("Expected a mix of permitted and not permitted results, but got: Permitted=" 
            + permittedCount.get() + ", Not Permitted=" + notPermittedCount.get());
      }
      
      // Verify the total count matches our expected thread count
      if (permittedCount.get() + notPermittedCount.get() != CONCURRENT_THREADS) {
        fail("Expected " + CONCURRENT_THREADS + " total results, but got " 
            + (permittedCount.get() + notPermittedCount.get()));
      }
    }
  }
  
  @Test
  public void testEnsurePermittedWithDynamicPermissionChangesInVirtualThreads() throws Exception {
    // Start with permission granted
    when(contentPermissionChecker
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any()))
        .thenReturn(true);

    CountDownLatch firstPhaseLatch = new CountDownLatch(CONCURRENT_THREADS / 2);
    CountDownLatch secondPhaseLatch = new CountDownLatch(CONCURRENT_THREADS / 2);
    AtomicInteger firstPhaseFailures = new AtomicInteger(0);
    AtomicInteger secondPhaseFailures = new AtomicInteger(0);

    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // First half of threads should succeed (permission granted)
      for (int i = 0; i < CONCURRENT_THREADS / 2; i++) {
        executor.submit(() -> {
          try {
            // Test the permission check - should succeed
            assertDoesNotThrow(() -> mavenSecurityFacet.ensurePermitted(request));
          }
          catch (Exception e) {
            firstPhaseFailures.incrementAndGet();
            log.error("First phase virtual thread test failed", e);
          }
          finally {
            firstPhaseLatch.countDown();
          }
        });
      }

      // Wait for first phase to complete
      if (!firstPhaseLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail("Test timed out waiting for first phase virtual threads to complete");
      }
      
      // Change permission to denied for second phase
      when(contentPermissionChecker
          .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any()))
          .thenReturn(false);
      
      // Second half of threads should fail (permission denied)
      for (int i = 0; i < CONCURRENT_THREADS / 2; i++) {
        executor.submit(() -> {
          try {
            // Test the permission check - should throw AuthorizationException
            assertThrows(AuthorizationException.class, () -> {
              mavenSecurityFacet.ensurePermitted(request);
            });
          }
          catch (Exception e) {
            secondPhaseFailures.incrementAndGet();
            log.error("Second phase virtual thread test failed", e);
          }
          finally {
            secondPhaseLatch.countDown();
          }
        });
      }

      // Wait for second phase to complete
      if (!secondPhaseLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail("Test timed out waiting for second phase virtual threads to complete");
      }
      
      // Verify no failures occurred in either phase
      if (firstPhaseFailures.get() > 0) {
        fail("Expected first phase permitted operations to succeed, but " + firstPhaseFailures.get() + " failures occurred");
      }
      
      if (secondPhaseFailures.get() > 0) {
        fail("Expected second phase permission denials to be detected, but " + secondPhaseFailures.get() + " failures occurred");
      }
    }
  }
}