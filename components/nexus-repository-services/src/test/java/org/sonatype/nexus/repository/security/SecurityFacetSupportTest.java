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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.view.Request;

import org.apache.shiro.authz.AuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.BreadActions.READ;

@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(org.sonatype.nexus.java21.Java21TestGroup.class)
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
  void setupConfig() throws Exception {
    when(request.getPath()).thenReturn("/some/path.txt");
    when(request.getAction()).thenReturn(HttpMethods.GET);

    when(repository.getFormat()).thenReturn(new Format("test") { });
    when(repository.getName()).thenReturn("SecurityFacetSupportTest");

    testSecurityFacetSupport = new TestSecurityFacetSupport(securityContributor,
        variableResolverAdapter, contentPermissionChecker);

    testSecurityFacetSupport.attach(repository);
  }

  @Test
  @DisplayName("Should allow access when permission is granted")
  void ensurePermittedWhenPermissionGranted() throws Exception {
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(true);
    
    assertDoesNotThrow(() -> testSecurityFacetSupport.ensurePermitted(request),
        "permitted action should have been permitted");
  }

  @Test
  @DisplayName("Should deny access when permission is not granted")
  void ensurePermittedWhenPermissionDenied() throws Exception {
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(false);

    assertThrows(AuthorizationException.class, 
        () -> testSecurityFacetSupport.ensurePermitted(request),
        "AuthorizationException should have been thrown");

    verify(contentPermissionChecker).isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any());
  }
  
  @Test
  @DisplayName("Should work correctly with virtual threads")
  void ensurePermittedWithVirtualThreads() throws Exception {
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Configure mock to return true for permission check
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(true);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple tasks to virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            testSecurityFacetSupport.ensurePermitted(request);
            latch.countDown();
          } 
          catch (Exception e) {
            // If any exception occurs, the test will fail because the latch won't count down fully
          }
        });
      }
      
      // Wait for all threads to complete or timeout
      boolean completed = latch.await(5, TimeUnit.SECONDS);
      assertDoesNotThrow(() -> {
        if (!completed) {
          throw new AssertionError("Not all virtual threads completed security checks successfully");
        }
      });
    }
  }
  
  @Test
  @DisplayName("Should correctly deny access with virtual threads")
  void ensurePermittedDeniedWithVirtualThreads() throws Exception {
    // Configure mock to return false for permission check
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(false);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit task to virtual thread and get future
      Exception exception = executor.submit(() -> {
        try {
          testSecurityFacetSupport.ensurePermitted(request);
          return null; // No exception thrown (this would be unexpected)
        } 
        catch (Exception e) {
          return e; // Return the exception that was thrown
        }
      }).get(5, TimeUnit.SECONDS);
      
      // Verify that the correct exception type was thrown
      assertDoesNotThrow(() -> {
        if (!(exception instanceof AuthorizationException)) {
          throw new AssertionError("Expected AuthorizationException but got: " + 
              (exception == null ? "no exception" : exception.getClass().getName()));
        }
      });
    }
  }
}
