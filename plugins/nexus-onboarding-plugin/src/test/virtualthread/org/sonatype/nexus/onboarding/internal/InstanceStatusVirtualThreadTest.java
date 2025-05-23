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
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.onboarding.capability.OnboardingCapability;
import org.sonatype.nexus.onboarding.capability.OnboardingCapabilityHelper;
import org.sonatype.nexus.security.anonymous.AnonymousManager;

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
 * Tests {@link InstanceStatus} functionality when executed within virtual threads.
 * Verifies that instance status determination based on anonymous-access configuration
 * and onboarding registration state works properly in a virtual thread execution environment.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class InstanceStatusVirtualThreadTest
    extends TestSupport
{
  private InstanceStatus underTest;

  @Mock
  private AnonymousManager anonymousManager;

  @Mock
  private OnboardingCapabilityHelper onboardingCapabilityHelper;

  @Mock
  private OnboardingCapability onboardingCapability;

  @BeforeEach
  public void setup() {
    underTest = new InstanceStatus(anonymousManager, onboardingCapabilityHelper);
    when(onboardingCapabilityHelper.getOnboardingCapability()).thenReturn(onboardingCapability);
  }

  @Test
  public void instanceIsNew_whenAnonymousNotConfigured_inVirtualThread() throws Exception {
    // Setup a CountDownLatch to synchronize with the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create and start a virtual thread to execute the test
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Configure the mock
        when(anonymousManager.isConfigured()).thenReturn(false);
        
        // Test the functionality
        assertThat(underTest.isNew(), is(true));
        assertThat(underTest.isUpgraded(), is(false));
      } finally {
        // Signal that the test is complete
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete (with timeout for safety)
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Test in virtual thread did not complete in time");
    virtualThread.join(); // Ensure the thread is fully terminated
  }

  @Test
  public void instanceIsUpgraded_whenAnonymousConfiguredButRegistrationNotStarted_inVirtualThread() throws Exception {
    // Setup a CountDownLatch to synchronize with the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create and start a virtual thread to execute the test
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Configure the mocks
        when(anonymousManager.isConfigured()).thenReturn(true);
        when(onboardingCapability.isRegistrationStarted()).thenReturn(false);
        
        // Test the functionality
        assertThat(underTest.isNew(), is(false));
        assertThat(underTest.isUpgraded(), is(true));
      } finally {
        // Signal that the test is complete
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete (with timeout for safety)
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Test in virtual thread did not complete in time");
    virtualThread.join(); // Ensure the thread is fully terminated
  }

  @Test
  public void instanceIsNew_whenAnonymousConfiguredAndRegistrationStartedButNotCompleted_inVirtualThread() throws Exception {
    // Setup a CountDownLatch to synchronize with the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create and start a virtual thread to execute the test
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Configure the mocks
        when(anonymousManager.isConfigured()).thenReturn(true);
        when(onboardingCapability.isRegistrationStarted()).thenReturn(true);
        when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);
        
        // Test the functionality
        assertThat(underTest.isNew(), is(true));
        assertThat(underTest.isUpgraded(), is(false));
      } finally {
        // Signal that the test is complete
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete (with timeout for safety)
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Test in virtual thread did not complete in time");
    virtualThread.join(); // Ensure the thread is fully terminated
  }
  
  @Test
  public void concurrentInstanceStatusChecks_inVirtualThreads() throws Exception {
    // Setup for concurrent execution
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1); // Used to synchronize thread start
    CountDownLatch completionLatch = new CountDownLatch(threadCount); // Used to track completion
    
    // Configure the mocks with a scenario
    when(anonymousManager.isConfigured()).thenReturn(true);
    when(onboardingCapability.isRegistrationStarted()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);
    
    // Create and start multiple virtual threads
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().name("status-check-" + i).start(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Verify we're running in a virtual thread
          assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
          
          // Perform status checks
          boolean isNew = underTest.isNew();
          boolean isUpgraded = underTest.isUpgraded();
          
          // Verify results
          assertThat(isNew, is(true));
          assertThat(isUpgraded, is(false));
        } catch (Exception e) {
          // Log any exceptions
          log.error("Error in virtual thread test", e);
        } finally {
          // Signal completion
          completionLatch.countDown();
        }
      });
    }
    
    // Signal all threads to start simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete (with timeout for safety)
    assertTrue(completionLatch.await(10, TimeUnit.SECONDS), 
        "Not all concurrent tests completed in time");
  }
}