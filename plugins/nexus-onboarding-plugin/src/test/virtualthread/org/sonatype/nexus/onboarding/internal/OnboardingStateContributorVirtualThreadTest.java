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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.onboarding.OnboardingConfiguration;
import org.sonatype.nexus.onboarding.OnboardingItem;
import org.sonatype.nexus.onboarding.OnboardingManager;
import org.sonatype.nexus.security.config.AdminPasswordFileManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OnboardingStateContributor} when executed within virtual threads.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class OnboardingStateContributorVirtualThreadTest
    extends TestSupport
{
  @Mock
  private OnboardingConfiguration onboardingConfiguration;

  @Mock
  private OnboardingManager onboardingManager;

  @Mock
  private AdminPasswordFileManager adminPasswordFileManager;

  @Mock
  private OnboardingItem onboardingItem1;

  @Mock
  private OnboardingItem onboardingItem2;

  private OnboardingStateContributor underTest;

  @BeforeEach
  public void setup() {
    when(onboardingConfiguration.isEnabled()).thenReturn(true);
    when(onboardingManager.getOnboardingItems()).thenReturn(Arrays.asList(onboardingItem1, onboardingItem2));
    when(onboardingManager.needsOnboarding()).thenReturn(true);
    when(adminPasswordFileManager.exists()).thenReturn(true);
    when(adminPasswordFileManager.getPath()).thenReturn("path/to/file");

    underTest = new OnboardingStateContributor(onboardingConfiguration, onboardingManager, adminPasswordFileManager);
  }

  /**
   * Tests the getState method when executed in a virtual thread.
   */
  @Test
  public void testGetState() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Map<String, Object>[] stateHolder = new Map[1];
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Execute the test
        stateHolder[0] = underTest.getState();
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();
    
    // Verify the results
    Map<String, Object> state = stateHolder[0];
    assertNotNull(state);
    assertEquals(2, state.size());
    assertEquals(true, state.get("onboarding.required"));
    assertEquals("path/to/file", state.get("admin.password.file"));
  }

  /**
   * Tests the getState method when no items are available and executed in a virtual thread.
   */
  @Test
  public void testGetState_noItems() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Map<String, Object>[] stateHolder = new Map[1];
    
    // Configure the mocks for this test case
    when(onboardingManager.needsOnboarding()).thenReturn(false);
    when(adminPasswordFileManager.exists()).thenReturn(false);
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Execute the test
        stateHolder[0] = underTest.getState();
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();
    
    // Verify the results
    assertNull(stateHolder[0]);
  }

  /**
   * Tests the caching behavior of getState method when executed in a virtual thread.
   */
  @Test
  public void testGetState_cacheOnboardingState() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Map<String, Object>[] stateHolder = new Map[3];
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // First call - should have onboarding.required = true
        stateHolder[0] = underTest.getState();
        
        // Change the mock behavior
        when(onboardingManager.needsOnboarding()).thenReturn(false);
        
        // Second call - should have onboarding.required = null due to caching
        stateHolder[1] = underTest.getState();
        
        // Change the mock behavior back
        when(onboardingManager.needsOnboarding()).thenReturn(true);
        
        // Third call - should still have onboarding.required = null due to caching
        stateHolder[2] = underTest.getState();
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();
    
    // Verify the results
    assertEquals(true, stateHolder[0].get("onboarding.required"));
    assertNull(stateHolder[1].get("onboarding.required"));
    assertNull(stateHolder[2].get("onboarding.required"));
  }

  /**
   * Tests the getState method when admin password file doesn't exist and executed in a virtual thread.
   */
  @Test
  public void testGetState_noAdminPasswordFile() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Map<String, Object>[] stateHolder = new Map[1];
    
    // Configure the mocks for this test case
    when(adminPasswordFileManager.exists()).thenReturn(false);
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Execute the test
        stateHolder[0] = underTest.getState();
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    virtualThread.join();
    
    // Verify the results
    assertNull(stateHolder[0].get("admin.password.file"));
  }
  
  /**
   * Tests concurrent access to getState method from multiple virtual threads.
   */
  @Test
  public void testGetState_concurrentAccess() throws Exception {
    int threadCount = 5;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final Map<String, Object>[][] stateHolders = new Map[threadCount][1];
    Thread[] threads = new Thread[threadCount];
    
    // Create multiple virtual threads that will start simultaneously
    for (int i = 0; i < threadCount; i++) {
      final int threadIndex = i;
      threads[i] = Thread.ofVirtual().start(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Verify we're running in a virtual thread
          assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
          
          // Execute the test
          stateHolders[threadIndex][0] = underTest.getState();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Signal all threads to start simultaneously
    startLatch.countDown();
    
    // Wait for all virtual threads to complete
    assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "Not all virtual threads completed in time");
    
    // Join all threads
    for (Thread thread : threads) {
      thread.join();
    }
    
    // Verify the results from all threads
    for (int i = 0; i < threadCount; i++) {
      Map<String, Object> state = stateHolders[i][0];
      assertNotNull(state);
      assertEquals(2, state.size());
      assertEquals(true, state.get("onboarding.required"));
      assertEquals("path/to/file", state.get("admin.password.file"));
    }
  }
}