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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.testcommon.Java21TestGroup;

import org.apache.shiro.authz.AuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
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
@Category(Java21TestGroup.class)
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

  private static final int TIMEOUT_SECONDS = 10;

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

  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  void setupConfig() throws Exception {
    when(request.getPath()).thenReturn("/some/path.txt");
    when(request.getAction()).thenReturn(HttpMethods.GET);

    when(repository.getFormat()).thenReturn(new Format("test") { });
    when(repository.getName()).thenReturn("SecurityFacetSupportTest");

    testSecurityFacetSupport = new TestSecurityFacetSupport(securityContributor,
        variableResolverAdapter, contentPermissionChecker);

    testSecurityFacetSupport.attach(repository);
    
    // Create virtual thread executor for Java 21 tests
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Test
  @DisplayName("Should allow permitted actions")
  void ensurePermittedShouldAllowPermittedActions() throws Exception {
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(true);
    
    assertDoesNotThrow(() -> testSecurityFacetSupport.ensurePermitted(request),
        "Permitted action should have been allowed");
  }

  @Test
  @DisplayName("Should throw AuthorizationException for non-permitted actions")
  void ensurePermittedShouldThrowExceptionForNonPermittedActions() throws Exception {
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(false);

    assertThrows(AuthorizationException.class, 
        () -> testSecurityFacetSupport.ensurePermitted(request),
        "AuthorizationException should have been thrown");

    verify(contentPermissionChecker).isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any());
  }
  
  @Test
  @DisplayName("Should work correctly with virtual threads for permitted actions")
  void ensurePermittedShouldWorkWithVirtualThreadsForPermittedActions() throws Exception {
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(true);
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        testSecurityFacetSupport.ensurePermitted(request);
      }
      catch (Exception e) {
        throw new RuntimeException("Should not have thrown exception", e);
      }
    }, virtualThreadExecutor);
    
    assertDoesNotThrow(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "Virtual thread execution should complete without exceptions");
  }
  
  @Test
  @DisplayName("Should throw AuthorizationException with virtual threads for non-permitted actions")
  void ensurePermittedShouldThrowExceptionWithVirtualThreadsForNonPermittedActions() throws Exception {
    when(contentPermissionChecker.isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any()))
        .thenReturn(false);
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        testSecurityFacetSupport.ensurePermitted(request);
        throw new RuntimeException("Should have thrown AuthorizationException");
      }
      catch (AuthorizationException e) {
        // Expected exception
      }
      catch (Exception e) {
        throw new RuntimeException("Unexpected exception type", e);
      }
    }, virtualThreadExecutor);
    
    assertDoesNotThrow(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "Virtual thread execution should complete with expected AuthorizationException");
    
    verify(contentPermissionChecker).isPermitted(eq("SecurityFacetSupportTest"), eq("test"), eq(READ), any());
  }
}
