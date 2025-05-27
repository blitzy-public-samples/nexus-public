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

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.security.BreadActions;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.apache.shiro.authz.AuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenSecurityFacet} using Java 21 Virtual Threads.
 * 
 * This test class verifies that the MavenSecurityFacet correctly handles permission checking
 * when operations are performed concurrently using Virtual Threads.
 */
@Tag("virtualthread")
@ExtendWith(MockitoExtension.class)
public class MavenSecurityFacetVirtualThreadTest
    extends VirtualThreadTestSupport
{
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
  void setupConfig() throws Exception {
    when(request.getPath()).thenReturn("/mygroupid/myartifactid/1.0/myartifactid-1.0.jar");
    when(request.getAction()).thenReturn(HttpMethods.GET);

    when(repository.getFormat()).thenReturn(new Maven2Format());
    when(repository.getName()).thenReturn("MavenSecurityFacetTest");

    mavenSecurityFacet = new MavenSecurityFacet(securityContributor,
        variableResolverAdapter, contentPermissionChecker);

    mavenSecurityFacet.attach(repository);
  }

  @Test
  void testEnsurePermitted() throws Exception {
    when(contentPermissionChecker
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any()))
        .thenReturn(true);

    // No exception should be thrown
    mavenSecurityFacet.ensurePermitted(request);
  }

  @Test
  void testEnsurePermitted_notPermitted() throws Exception {
    assertThrows(AuthorizationException.class, () -> {
      mavenSecurityFacet.ensurePermitted(request);
    });

    verify(contentPermissionChecker)
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any());
  }
  
  @Test
  void testEnsurePermittedConcurrently() throws Exception {
    when(contentPermissionChecker
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any()))
        .thenReturn(true);
    
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      // Start multiple virtual threads that all try to check permissions at the same time
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            mavenSecurityFacet.ensurePermitted(request); // This should not throw an exception
            completionLatch.countDown();
          }
          catch (Exception e) {
            // Count down even if there's an exception to avoid test hanging
            completionLatch.countDown();
            throw new RuntimeException(e);
          }
        });
      }
      
      // Start all threads at once
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean allCompleted = completionLatch.await(5, TimeUnit.SECONDS);
      if (!allCompleted) {
        throw new AssertionError("Not all virtual threads completed in time");
      }
    }
  }
  
  @Test
  void testEnsurePermittedConcurrently_notPermitted() throws Exception {
    // Configure the mock to return false for permission check
    when(contentPermissionChecker
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any()))
        .thenReturn(false);
    
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger exceptionCount = new AtomicInteger(0);
    
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      // Start multiple virtual threads that all try to check permissions at the same time
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            try {
              mavenSecurityFacet.ensurePermitted(request); // This should throw an exception
            }
            catch (AuthorizationException e) {
              // Expected exception
              exceptionCount.incrementAndGet();
            }
            completionLatch.countDown();
          }
          catch (Exception e) {
            // Count down even if there's an unexpected exception
            completionLatch.countDown();
            throw new RuntimeException(e);
          }
        });
      }
      
      // Start all threads at once
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean allCompleted = completionLatch.await(5, TimeUnit.SECONDS);
      if (!allCompleted) {
        throw new AssertionError("Not all virtual threads completed in time");
      }
      
      // Verify that all threads got the expected exception
      if (exceptionCount.get() != threadCount) {
        throw new AssertionError("Expected " + threadCount + " authorization exceptions, but got " + 
            exceptionCount.get());
      }
    }
  }
  
  @Test
  void testEnsurePermittedConcurrently_mixedPermissions() throws Exception {
    // Configure the mock to alternate between permitted and not permitted
    AtomicInteger callCount = new AtomicInteger(0);
    when(contentPermissionChecker
        .isPermitted(eq("MavenSecurityFacetTest"), eq(Maven2Format.NAME), eq(BreadActions.READ), any()))
        .thenAnswer(invocation -> callCount.incrementAndGet() % 2 == 0); // Even calls return true, odd calls return false
    
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger exceptionCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      // Start multiple virtual threads that all try to check permissions at the same time
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            try {
              mavenSecurityFacet.ensurePermitted(request);
              successCount.incrementAndGet();
            }
            catch (AuthorizationException e) {
              // Expected for some threads
              exceptionCount.incrementAndGet();
            }
            completionLatch.countDown();
          }
          catch (Exception e) {
            // Count down even if there's an unexpected exception
            completionLatch.countDown();
            throw new RuntimeException(e);
          }
        });
      }
      
      // Start all threads at once
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean allCompleted = completionLatch.await(5, TimeUnit.SECONDS);
      if (!allCompleted) {
        throw new AssertionError("Not all virtual threads completed in time");
      }
      
      // Verify that we got a mix of successes and failures
      if (exceptionCount.get() == 0 || successCount.get() == 0) {
        throw new AssertionError("Expected a mix of successes and failures, but got " + 
            successCount.get() + " successes and " + exceptionCount.get() + " failures");
      }
      
      // Verify that all threads were accounted for
      if (exceptionCount.get() + successCount.get() != threadCount) {
        throw new AssertionError("Expected " + threadCount + " total results, but got " + 
            (exceptionCount.get() + successCount.get()));
      }
    }
  }
}