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

import org.apache.shiro.authz.permission.DomainPermission;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;

public class SecurityTrialTest
    extends TestSupport
{
  @Test
  public void wildcardPermissionString() {
    assertDoesNotThrow(() -> new WildcardPermission("foo:bar:*:baz"));
  }

  @Test
  public void domainPermissionString() {
    assertDoesNotThrow(() -> new DomainPermission("foo,bar", "read,write"));
  }

  private static class CustomPermission
      extends DomainPermission
  {
    CustomPermission(final String actions, final String targets) {
      super(actions, targets);
    }
  }

  @Test
  public void customDomainPermissionString() {
    assertDoesNotThrow(() -> new CustomPermission("foo,bar", "read,write"));
  }

  @Test
  public void impliedPermission() {
    WildcardPermission granted = new WildcardPermission("test:*");
    WildcardPermission permission = new WildcardPermission("test:foo");
    assertTrue(granted.implies(permission));
  }
  
  @Test
  public void permissionEvaluationInVirtualThread() {
    // Test permission evaluation in a virtual thread (Java 21 feature)
    Thread virtualThread = Thread.ofVirtual().name("permission-test-virtual-thread").start(() -> {
      WildcardPermission granted = new WildcardPermission("test:*");
      WildcardPermission permission = new WildcardPermission("test:foo");
      assertTrue(granted.implies(permission));
    });
    
    // Wait for the virtual thread to complete
    try {
      virtualThread.join();
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  @Test
  public void parallelPermissionEvaluationWithVirtualThreads() throws Exception {
    // Test permission evaluation in multiple virtual threads running in parallel
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create different permission patterns to test
            WildcardPermission granted = new WildcardPermission("domain" + (index % 5) + ":*");
            WildcardPermission permission = new WildcardPermission("domain" + (index % 5) + ":action");
            assertTrue(granted.implies(permission));
            
            // Test custom permissions as well
            CustomPermission customGranted = new CustomPermission("action1,action2", "target1,target2");
            assertTrue(customGranted.implies(customGranted));
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete or timeout after 5 seconds
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    }
  }
  
  @Test
  public void multiplePermissionAssertions() {
    // Test multiple permission assertions in a single test using assertAll
    assertAll("Multiple permission checks",
        () -> {
          WildcardPermission p1 = new WildcardPermission("domain1:action1");
          WildcardPermission p2 = new WildcardPermission("domain1:action1");
          assertTrue(p1.implies(p2));
        },
        () -> {
          WildcardPermission p1 = new WildcardPermission("domain2:*");
          WildcardPermission p2 = new WildcardPermission("domain2:action1");
          assertTrue(p1.implies(p2));
        },
        () -> {
          WildcardPermission p1 = new WildcardPermission("*:action1");
          WildcardPermission p2 = new WildcardPermission("domain3:action1");
          assertTrue(p1.implies(p2));
        }
    );
  }
}
