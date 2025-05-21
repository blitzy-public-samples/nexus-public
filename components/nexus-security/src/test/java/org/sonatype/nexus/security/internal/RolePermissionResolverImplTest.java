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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.authz.AuthorizationConfigurationChanged;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.role.NoSuchRoleException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RolePermissionResolverImpl}.
 */
@ExtendWith(MockitoExtension.class)
public class RolePermissionResolverImplTest
    extends TestSupport
{
  private RolePermissionResolverImpl underTest;

  private SecurityConfigurationManager securityConfigurationManager;

  @Mock
  private EventManager eventManager;

  @BeforeEach
  void setUp() throws Exception {
    securityConfigurationManager = mock(SecurityConfigurationManager.class);
    when(securityConfigurationManager.readRole(any())).thenThrow(new NoSuchRoleException("Role not found"));
    underTest = new RolePermissionResolverImpl(securityConfigurationManager, Collections.emptyList(),
        eventManager, 10);
  }

  @Test
  void resolvePermissionsInRole_roleNotFoundCache() throws Exception {
    underTest.resolvePermissionsInRole("role1");
    verify(securityConfigurationManager).readRole(any());

    //just call it 3 more times for fun
    underTest.resolvePermissionsInRole("role1");
    underTest.resolvePermissionsInRole("role1");
    underTest.resolvePermissionsInRole("role1");

    //should still have only been called once
    verify(securityConfigurationManager).readRole(any());

    //simulate event being fired, which clears cache
    underTest.on(new AuthorizationConfigurationChanged());

    underTest.resolvePermissionsInRole("role1");
    verify(securityConfigurationManager, times(2)).readRole(any());

    //and finally make sure we are hitting cache again
    underTest.resolvePermissionsInRole("role1");
    underTest.resolvePermissionsInRole("role1");
    underTest.resolvePermissionsInRole("role1");
    verify(securityConfigurationManager, times(2)).readRole(any());
  }

  @Test
  void resolvePermissionsInRole_withVirtualThreads() throws Exception {
    // Create multiple virtual threads to test concurrent access
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    List<Exception> exceptions = new CopyOnWriteArrayList<>();

    // Start virtual threads that all try to resolve the same role
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().name("virtual-thread-" + i).start(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          underTest.resolvePermissionsInRole("role1");
        }
        catch (Exception e) {
          exceptions.add(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Release all threads at once to maximize concurrency
    startLatch.countDown();
    completionLatch.await();

    // Verify no exceptions occurred
    assertTrue(exceptions.isEmpty(), "Exceptions occurred during concurrent access: " + exceptions);
    
    // Verify the cache worked - securityConfigurationManager.readRole should only be called once
    verify(securityConfigurationManager).readRole(any());
  }

  @Test
  void resolvePermissionsInRole_cacheInvalidationWithVirtualThreads() throws Exception {
    // Create multiple virtual threads to test concurrent access with cache invalidation
    int threadCount = 5;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch firstBatchLatch = new CountDownLatch(threadCount);
    CountDownLatch secondBatchLatch = new CountDownLatch(threadCount);
    List<Exception> exceptions = new CopyOnWriteArrayList<>();

    // First batch of virtual threads
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().name("virtual-thread-first-" + i).start(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          underTest.resolvePermissionsInRole("role1");
        }
        catch (Exception e) {
          exceptions.add(e);
        }
        finally {
          firstBatchLatch.countDown();
        }
      });
    }

    // Release all threads at once
    startLatch.countDown();
    firstBatchLatch.await();

    // Simulate event being fired, which clears cache
    underTest.on(new AuthorizationConfigurationChanged());

    // Second batch of virtual threads after cache invalidation
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().name("virtual-thread-second-" + i).start(() -> {
        try {
          underTest.resolvePermissionsInRole("role1");
        }
        catch (Exception e) {
          exceptions.add(e);
        }
        finally {
          secondBatchLatch.countDown();
        }
      });
    }

    secondBatchLatch.await();

    // Verify no exceptions occurred
    assertTrue(exceptions.isEmpty(), "Exceptions occurred during concurrent access: " + exceptions);
    
    // Verify the cache was invalidated - securityConfigurationManager.readRole should be called twice
    // Once for the first batch (cached) and once for the second batch (after invalidation)
    verify(securityConfigurationManager, times(2)).readRole(any());
  }
}