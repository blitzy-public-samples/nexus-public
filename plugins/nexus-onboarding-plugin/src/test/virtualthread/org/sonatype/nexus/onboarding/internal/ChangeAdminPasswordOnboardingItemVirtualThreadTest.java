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
package org.sonatype.nexus.onboarding.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserStatus;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ChangeAdminPasswordOnboardingItem} in a virtual thread execution environment.
 * This test validates that the onboarding item correctly determines when an admin password
 * change is required when executed within virtual threads.
 */
@ExtendWith(MockitoExtension.class)
@Tag("Java21TestGroup")
@Tag("VirtualThreadTestGroup")
public class ChangeAdminPasswordOnboardingItemVirtualThreadTest
    extends TestSupport
{
  @Mock
  private SecuritySystem securitySystem;

  private ChangeAdminPasswordOnboardingItem underTest;

  @BeforeEach
  public void setup() {
    underTest = new ChangeAdminPasswordOnboardingItem(securitySystem);
  }

  @Test
  public void testApplies() throws Exception {
    User user = new User();
    user.setStatus(UserStatus.changepassword);

    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    runInVirtualThread(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Test the applies method
      assertThat(underTest.applies(), is(true));
    });
  }

  @Test
  public void testApplies_statusActive() throws Exception {
    User user = new User();
    user.setStatus(UserStatus.active);

    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    runInVirtualThread(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Test the applies method
      assertThat(underTest.applies(), is(false));
    });
  }

  @Test
  public void testApplies_statusDisabled() throws Exception {
    User user = new User();
    user.setStatus(UserStatus.disabled);

    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    runInVirtualThread(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Test the applies method
      assertThat(underTest.applies(), is(false));
    });
  }

  @Test
  public void testApplies_statusLocked() throws Exception {
    User user = new User();
    user.setStatus(UserStatus.locked);

    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    runInVirtualThread(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Test the applies method
      assertThat(underTest.applies(), is(false));
    });
  }

  @Test
  public void testApplies_userNotFound() throws Exception {
    when(securitySystem.getUser("admin", "default")).thenThrow(new UserNotFoundException("admin"));

    runInVirtualThread(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Test the applies method
      assertThat(underTest.applies(), is(false));
    });
  }

  @Test
  public void testApplies_userManagerNotFound() throws Exception {
    when(securitySystem.getUser("admin", "default")).thenThrow(new NoSuchUserManagerException("default"));

    runInVirtualThread(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Test the applies method
      assertThat(underTest.applies(), is(false));
    });
  }
  
  /**
   * Helper method to run a test in a virtual thread and wait for its completion.
   *
   * @param runnable the test code to execute in a virtual thread
   * @throws Exception if the test execution fails or times out
   */
  private void runInVirtualThread(Runnable runnable) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Thread virtualThread = Thread.ofVirtual().name("virtual-test-thread").start(() -> {
      try {
        runnable.run();
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete, with a timeout
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("Test in virtual thread did not complete within timeout");
    }
  }
}