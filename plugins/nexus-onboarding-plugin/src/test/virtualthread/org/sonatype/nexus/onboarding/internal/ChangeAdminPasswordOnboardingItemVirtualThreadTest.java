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
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserStatus;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ChangeAdminPasswordOnboardingItem} running in a virtual thread environment.
 * 
 * This test validates that the onboarding item correctly determines when an admin password change
 * is required across various admin user statuses and exception scenarios when executed within
 * virtual threads. The test ensures that onboarding components properly leverage Virtual Threads
 * without thread pinning issues, particularly for I/O-bound operations like security system interactions.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
class ChangeAdminPasswordOnboardingItemVirtualThreadTest
    extends TestSupport
{
  @Mock
  private SecuritySystem securitySystem;

  private ChangeAdminPasswordOnboardingItem underTest;

  @BeforeEach
  void setup() {
    underTest = new ChangeAdminPasswordOnboardingItem(securitySystem);
  }

  /**
   * Tests that the onboarding item correctly identifies when an admin password change is required
   * when executed in a virtual thread.
   */
  @Test
  void testAppliesInVirtualThread() throws Exception {
    // Create a user with changepassword status
    User user = new User();
    user.setStatus(UserStatus.changepassword);

    // Configure the mock
    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> appliesResult = new AtomicReference<>();
    AtomicReference<Boolean> isVirtualThread = new AtomicReference<>();

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtualThread.set(Thread.currentThread().isVirtual());
        
        // Call the method under test
        appliesResult.set(underTest.applies());
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    
    // Verify the test ran in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the applies method returned the expected result
    assertThat("Admin with changepassword status should require password change", 
        appliesResult.get(), is(true));
  }

  /**
   * Tests that the onboarding item correctly identifies when an admin password change is not required
   * for an active user when executed in a virtual thread.
   */
  @Test
  void testAppliesStatusActiveInVirtualThread() throws Exception {
    // Create a user with active status
    User user = new User();
    user.setStatus(UserStatus.active);

    // Configure the mock
    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> appliesResult = new AtomicReference<>();
    AtomicReference<Boolean> isVirtualThread = new AtomicReference<>();

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtualThread.set(Thread.currentThread().isVirtual());
        
        // Call the method under test
        appliesResult.set(underTest.applies());
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    
    // Verify the test ran in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the applies method returned the expected result
    assertThat("Admin with active status should not require password change", 
        appliesResult.get(), is(false));
  }

  /**
   * Tests that the onboarding item correctly identifies when an admin password change is not required
   * for a disabled user when executed in a virtual thread.
   */
  @Test
  void testAppliesStatusDisabledInVirtualThread() throws Exception {
    // Create a user with disabled status
    User user = new User();
    user.setStatus(UserStatus.disabled);

    // Configure the mock
    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> appliesResult = new AtomicReference<>();
    AtomicReference<Boolean> isVirtualThread = new AtomicReference<>();

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtualThread.set(Thread.currentThread().isVirtual());
        
        // Call the method under test
        appliesResult.set(underTest.applies());
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    
    // Verify the test ran in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the applies method returned the expected result
    assertThat("Admin with disabled status should not require password change", 
        appliesResult.get(), is(false));
  }

  /**
   * Tests that the onboarding item correctly identifies when an admin password change is not required
   * for a locked user when executed in a virtual thread.
   */
  @Test
  void testAppliesStatusLockedInVirtualThread() throws Exception {
    // Create a user with locked status
    User user = new User();
    user.setStatus(UserStatus.locked);

    // Configure the mock
    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> appliesResult = new AtomicReference<>();
    AtomicReference<Boolean> isVirtualThread = new AtomicReference<>();

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtualThread.set(Thread.currentThread().isVirtual());
        
        // Call the method under test
        appliesResult.set(underTest.applies());
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    
    // Verify the test ran in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the applies method returned the expected result
    assertThat("Admin with locked status should not require password change", 
        appliesResult.get(), is(false));
  }

  /**
   * Tests that the onboarding item correctly handles a UserNotFoundException when executed in a virtual thread.
   */
  @Test
  void testAppliesUserNotFoundInVirtualThread() throws Exception {
    // Configure the mock to throw UserNotFoundException
    when(securitySystem.getUser("admin", "default")).thenThrow(new UserNotFoundException("admin"));

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> appliesResult = new AtomicReference<>();
    AtomicReference<Boolean> isVirtualThread = new AtomicReference<>();

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtualThread.set(Thread.currentThread().isVirtual());
        
        // Call the method under test
        appliesResult.set(underTest.applies());
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    
    // Verify the test ran in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the applies method returned the expected result
    assertThat("UserNotFoundException should result in no password change required", 
        appliesResult.get(), is(false));
  }

  /**
   * Tests that the onboarding item correctly handles a NoSuchUserManagerException when executed in a virtual thread.
   */
  @Test
  void testAppliesUserManagerNotFoundInVirtualThread() throws Exception {
    // Configure the mock to throw NoSuchUserManagerException
    when(securitySystem.getUser("admin", "default")).thenThrow(new NoSuchUserManagerException("default"));

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> appliesResult = new AtomicReference<>();
    AtomicReference<Boolean> isVirtualThread = new AtomicReference<>();

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtualThread.set(Thread.currentThread().isVirtual());
        
        // Call the method under test
        appliesResult.set(underTest.applies());
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    
    // Verify the test ran in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the applies method returned the expected result
    assertThat("NoSuchUserManagerException should result in no password change required", 
        appliesResult.get(), is(false));
  }
}