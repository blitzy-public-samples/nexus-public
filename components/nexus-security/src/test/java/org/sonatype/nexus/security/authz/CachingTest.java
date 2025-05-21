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
package org.sonatype.nexus.security.authz;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.realm.MockRealmB;
import org.sonatype.nexus.security.user.User;

import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class CachingTest
    extends AbstractSecurityTest
{
  @Test
  void shouldClearCacheWhenUserIsUpdated() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);

    MockRealmB mockRealmB = (MockRealmB) this.lookup(Realm.class, "MockRealmB");

    // cache should be empty to start
    Assertions.assertTrue(mockRealmB.getAuthorizationCache().keys().isEmpty());

    Assertions.assertTrue(securitySystem.isPermitted(
        new SimplePrincipalCollection("jcool", mockRealmB.getName()), "test:heHasIt"));

    // now something will be in the cache, just make sure
    Assertions.assertFalse(mockRealmB.getAuthorizationCache().keys().isEmpty());

    // now if we update a user the cache should be cleared
    User user = securitySystem.getUser("bburton", "MockUserManagerB");
    // different user, doesn't matter, in the future we should get a little more fine grained
    securitySystem.updateUser(user);

    // empty again
    Assertions.assertTrue(mockRealmB.getAuthorizationCache().keys().isEmpty());
  }
  
  @Test
  void shouldClearCacheWhenUserIsUpdatedWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);
    MockRealmB mockRealmB = (MockRealmB) this.lookup(Realm.class, "MockRealmB");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 10;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Clear the cache initially
      Assertions.assertTrue(mockRealmB.getAuthorizationCache().keys().isEmpty());
      
      // First populate the cache with permissions check
      Assertions.assertTrue(securitySystem.isPermitted(
          new SimplePrincipalCollection("jcool", mockRealmB.getName()), "test:heHasIt"));
      
      // Verify cache is populated
      Assertions.assertFalse(mockRealmB.getAuthorizationCache().keys().isEmpty());
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Get user and update it to trigger cache clearing
            User user = securitySystem.getUser("bburton", "MockUserManagerB");
            securitySystem.updateUser(user);
            
            // Verify cache is cleared after update
            if (!mockRealmB.getAuthorizationCache().keys().isEmpty()) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify no errors occurred
      Assertions.assertEquals(0, errorCount.get(), "Errors occurred during virtual thread execution");
      
      // Final verification that cache is empty
      Assertions.assertTrue(mockRealmB.getAuthorizationCache().keys().isEmpty());
    } finally {
      executor.shutdown();
    }
  }
}