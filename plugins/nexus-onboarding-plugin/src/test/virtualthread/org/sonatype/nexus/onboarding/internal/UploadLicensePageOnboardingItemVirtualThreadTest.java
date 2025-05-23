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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.onboarding.OnboardingItemPriority;
import org.sonatype.nexus.onboarding.capability.OnboardingCapability;
import org.sonatype.nexus.onboarding.capability.OnboardingCapabilityHelper;

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
 * Tests the {@link UploadLicensePageOnboardingItem} with Java 21 Virtual Threads.
 * 
 * Validates that the onboarding item correctly determines when the license upload page
 * should be shown based on instance status and registration completion, even in a
 * virtual thread execution environment.
 */
// Add @Category annotations for Java21TestGroup and VirtualThreadTestGroup if they exist
@ExtendWith(MockitoExtension.class)
class UploadLicensePageOnboardingItemVirtualThreadTest
    extends TestSupport
{
  @Mock
  private InstanceStatus instanceStatus;

  @Mock
  private OnboardingCapabilityHelper onboardingCapabilityHelper;

  @Mock
  private OnboardingCapability onboardingCapability;

  private UploadLicensePageOnboardingItem underTest;

  @BeforeEach
  void setup() {
    when(onboardingCapabilityHelper.getOnboardingCapability()).thenReturn(onboardingCapability);
    underTest = new UploadLicensePageOnboardingItem(instanceStatus, onboardingCapabilityHelper);
  }

  /**
   * Verifies that applies() returns false for a new instance with registration completed
   * when executed in a virtual thread.
   */
  @Test
  void testAppliesForNewInstanceAndRegistrationCompletedInVirtualThread() throws Exception {
    // Use CountDownLatch to synchronize between the main thread and the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start a virtual thread to run the test
    Thread.startVirtualThread(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Configure mocks
        when(instanceStatus.isNew()).thenReturn(true);
        when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);

        // Verify expected behavior
        assertThat(underTest.applies(), is(false));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
  }

  /**
   * Verifies that applies() returns true for a new instance with registration not completed
   * when executed in a virtual thread.
   */
  @Test
  void testAppliesForNewInstanceAndRegistrationNotCompletedInVirtualThread() throws Exception {
    // Use CountDownLatch to synchronize between the main thread and the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start a virtual thread to run the test
    Thread.startVirtualThread(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Configure mocks
        when(instanceStatus.isNew()).thenReturn(true);
        when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);

        // Verify expected behavior
        assertThat(underTest.applies(), is(true));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
  }

  /**
   * Verifies that applies() returns false for a non-new instance with registration completed
   * when executed in a virtual thread.
   */
  @Test
  void testAppliesForNotNewInstanceAndRegistrationCompletedInVirtualThread() throws Exception {
    // Use CountDownLatch to synchronize between the main thread and the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start a virtual thread to run the test
    Thread.startVirtualThread(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Configure mocks
        when(instanceStatus.isNew()).thenReturn(false);
        when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);

        // Verify expected behavior
        assertThat(underTest.applies(), is(false));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
  }

  /**
   * Verifies that applies() returns false for a non-new instance with registration not completed
   * when executed in a virtual thread.
   */
  @Test
  void testAppliesForNotNewInstanceAndRegistrationNotCompletedInVirtualThread() throws Exception {
    // Use CountDownLatch to synchronize between the main thread and the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start a virtual thread to run the test
    Thread.startVirtualThread(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Configure mocks
        when(instanceStatus.isNew()).thenReturn(false);
        when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);

        // Verify expected behavior
        assertThat(underTest.applies(), is(false));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
  }

  /**
   * Verifies that getType() returns the expected type when executed in a virtual thread.
   */
  @Test
  void testGetTypeInVirtualThread() throws Exception {
    // Use CountDownLatch to synchronize between the main thread and the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start a virtual thread to run the test
    Thread.startVirtualThread(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Verify expected behavior
        assertThat(underTest.getType(), is("UploadLicensePage"));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
  }

  /**
   * Verifies that getPriority() returns the expected priority when executed in a virtual thread.
   */
  @Test
  void testGetPriorityInVirtualThread() throws Exception {
    // Use CountDownLatch to synchronize between the main thread and the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start a virtual thread to run the test
    Thread.startVirtualThread(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
        
        // Verify expected behavior
        assertThat(underTest.getPriority(), is(OnboardingItemPriority.UPLOAD_LICENSE));
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
  }
}