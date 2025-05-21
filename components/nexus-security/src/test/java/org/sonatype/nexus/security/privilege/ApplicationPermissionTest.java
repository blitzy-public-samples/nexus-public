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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.PermissionResolver;
import org.apache.shiro.authz.permission.WildcardPermissionResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Tests for {@link ApplicationPermission}.
 */
public class ApplicationPermissionTest
    extends TestSupport
{
  private final PermissionResolver permissionResolver = new WildcardPermissionResolver();

  @Test
  @DisplayName("Simple application permissions should match")
  public void simpleApplicationPermissionsMatch() {
    Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");

    // simulate @RequiresPermissions resolution on UI resources
    Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");

    assertTrue(authorizedPermission.implies(requiredPermission));
  }

  @Test
  @DisplayName("Complex application permissions should match")
  public void complexApplicationPermissionsMatch() {
    Permission authorizedPermission = new ApplicationPermission("feature:method", "action", "anotherAction");

    // simulate @RequiresPermissions resolution on UI resources
    Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:method:action");

    assertTrue(authorizedPermission.implies(requiredPermission));
  }

  @Test
  @DisplayName("Permission should not match when action is not authorized")
  public void permissionShouldNotMatchWhenActionNotAuthorized() {
    Permission authorizedPermission = new ApplicationPermission("feature", "action");

    // simulate @RequiresPermissions resolution on UI resources
    Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:differentAction");

    assertFalse(authorizedPermission.implies(requiredPermission));
  }

  @Nested
  @DisplayName("Virtual Thread tests")
  class VirtualThreadTests {

    @Test
    @DisplayName("Permission checks should work correctly with virtual threads")
    public void permissionChecksWithVirtualThreads() throws Exception {
      // Create permissions to test
      Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
      Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");
      
      // Use virtual thread per task executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<Boolean> result = executor.submit(() -> authorizedPermission.implies(requiredPermission));
        
        assertTrue(result.get(5, TimeUnit.SECONDS), "Permission check should work in virtual thread");
      }
    }
    
    @Test
    @DisplayName("Permission checks should be thread-safe under high concurrency")
    public void permissionChecksUnderHighConcurrency() throws Exception {
      // Create permissions to test
      Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
      Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");
      
      final int threadCount = 1000;
      final CountDownLatch startLatch = new CountDownLatch(1);
      final AtomicBoolean failed = new AtomicBoolean(false);
      final List<Future<Boolean>> results = new ArrayList<>();
      
      // Use virtual thread per task executor for high concurrency
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit many concurrent permission checks
        for (int i = 0; i < threadCount; i++) {
          results.add(executor.submit(() -> {
            startLatch.await(); // Wait for all threads to be ready
            return authorizedPermission.implies(requiredPermission);
          }));
        }
        
        // Start all threads at once
        startLatch.countDown();
        
        // Verify all results
        for (Future<Boolean> result : results) {
          if (!result.get(10, TimeUnit.SECONDS)) {
            failed.set(true);
          }
        }
        
        assertFalse(failed.get(), "All permission checks should succeed under high concurrency");
      }
    }
    
    @Test
    @DisplayName("Multiple different permission checks should work concurrently")
    public void multipleDifferentPermissionChecksConcurrently() throws Exception {
      // Create multiple different permissions
      Permission auth1 = new ApplicationPermission("feature1", "action1");
      Permission auth2 = new ApplicationPermission("feature2", "action2");
      Permission auth3 = new ApplicationPermission("feature3", "action3", "action4");
      
      Permission req1 = permissionResolver.resolvePermission("nexus:feature1:action1");
      Permission req2 = permissionResolver.resolvePermission("nexus:feature2:action2");
      Permission req3 = permissionResolver.resolvePermission("nexus:feature3:action3");
      Permission req4 = permissionResolver.resolvePermission("nexus:feature3:action4");
      Permission req5 = permissionResolver.resolvePermission("nexus:feature1:actionX"); // Should not match
      
      // Use virtual thread per task executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<Boolean> result1 = executor.submit(() -> auth1.implies(req1));
        Future<Boolean> result2 = executor.submit(() -> auth2.implies(req2));
        Future<Boolean> result3 = executor.submit(() -> auth3.implies(req3));
        Future<Boolean> result4 = executor.submit(() -> auth3.implies(req4));
        Future<Boolean> result5 = executor.submit(() -> auth1.implies(req5));
        
        assertAll(
            () -> assertTrue(result1.get(5, TimeUnit.SECONDS), "Permission check 1 should succeed"),
            () -> assertTrue(result2.get(5, TimeUnit.SECONDS), "Permission check 2 should succeed"),
            () -> assertTrue(result3.get(5, TimeUnit.SECONDS), "Permission check 3 should succeed"),
            () -> assertTrue(result4.get(5, TimeUnit.SECONDS), "Permission check 4 should succeed"),
            () -> assertFalse(result5.get(5, TimeUnit.SECONDS), "Permission check 5 should fail")
        );
      }
    }
  }
}