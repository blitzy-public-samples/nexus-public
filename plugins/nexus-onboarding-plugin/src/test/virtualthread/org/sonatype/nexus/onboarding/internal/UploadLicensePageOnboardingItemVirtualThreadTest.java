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
import org.sonatype.nexus.common.app.Java21TestGroup;
import org.sonatype.nexus.common.app.VirtualThreadTestGroup;
import org.sonatype.nexus.onboarding.OnboardingItemPriority;
import org.sonatype.nexus.onboarding.capability.OnboardingCapability;
import org.sonatype.nexus.onboarding.capability.OnboardingCapabilityHelper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests {@link UploadLicensePageOnboardingItem} in a virtual thread environment.
 * 
 * This test ensures that the onboarding item correctly determines when the license upload page
 * should be shown based on instance status and registration completion, even when executed
 * within Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class UploadLicensePageOnboardingItemVirtualThreadTest
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
  public void setup() {
    when(onboardingCapabilityHelper.getOnboardingCapability()).thenReturn(onboardingCapability);
    underTest = new UploadLicensePageOnboardingItem(instanceStatus, onboardingCapabilityHelper);
  }

  @Test
  public void testAppliesForNewInstanceAndRegistrationCompleted() throws Exception {
    // Set up test conditions
    when(instanceStatus.isNew()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);
    
    // Run test in a virtual thread
    runInVirtualThreadAndWait(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Verify the applies() method returns the expected result
      assertThat(underTest.applies(), is(false));
    });
  }

  @Test
  public void testAppliesForNewInstanceAndRegistrationNotCompleted() throws Exception {
    // Set up test conditions
    when(instanceStatus.isNew()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);
    
    // Run test in a virtual thread
    runInVirtualThreadAndWait(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Verify the applies() method returns the expected result
      assertThat(underTest.applies(), is(true));
    });
  }

  @Test
  public void testAppliesForNotNewInstanceAndRegistrationCompleted() throws Exception {
    // Set up test conditions
    when(instanceStatus.isNew()).thenReturn(false);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);
    
    // Run test in a virtual thread
    runInVirtualThreadAndWait(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Verify the applies() method returns the expected result
      assertThat(underTest.applies(), is(false));
    });
  }

  @Test
  public void testAppliesForNotNewInstanceAndRegistrationNotCompleted() throws Exception {
    // Set up test conditions
    when(instanceStatus.isNew()).thenReturn(false);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);
    
    // Run test in a virtual thread
    runInVirtualThreadAndWait(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Verify the applies() method returns the expected result
      assertThat(underTest.applies(), is(false));
    });
  }

  @Test
  public void testGetType() throws Exception {
    // Run test in a virtual thread
    runInVirtualThreadAndWait(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Verify the getType() method returns the expected result
      assertThat(underTest.getType(), is("UploadLicensePage"));
    });
  }

  @Test
  public void testGetPriority() throws Exception {
    // Run test in a virtual thread
    runInVirtualThreadAndWait(() -> {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      // Verify the getPriority() method returns the expected result
      assertThat(underTest.getPriority(), is(OnboardingItemPriority.UPLOAD_LICENSE));
    });
  }
  
  /**
   * Helper method to run a test in a virtual thread and wait for its completion.
   * 
   * @param runnable the test code to execute in a virtual thread
   * @throws Exception if the test fails or times out
   */
  private void runInVirtualThreadAndWait(Runnable runnable) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      try {
        runnable.run();
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete, with a timeout to prevent test hangs
    if (!latch.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("Test in virtual thread did not complete within timeout");
    }
  }
}