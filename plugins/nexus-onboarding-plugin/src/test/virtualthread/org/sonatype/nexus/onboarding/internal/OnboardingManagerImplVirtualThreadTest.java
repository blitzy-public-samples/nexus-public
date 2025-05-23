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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.Java21TestGroup;
import org.sonatype.nexus.common.app.VirtualThreadTestGroup;
import org.sonatype.nexus.onboarding.OnboardingConfiguration;
import org.sonatype.nexus.onboarding.OnboardingItem;

import com.google.common.collect.ImmutableSet;
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
 * Tests {@link OnboardingManagerImpl} in virtual threads.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class OnboardingManagerImplVirtualThreadTest
    extends TestSupport
{
  @Mock
  private OnboardingConfiguration onboardingConfiguration;

  @Mock
  private OnboardingItem onboardingItem1;

  @Mock
  private OnboardingItem onboardingItem2;

  @Mock
  private OnboardingItem onboardingItem3;

  private OnboardingManagerImpl underTest;

  @BeforeEach
  public void setup() {
    when(onboardingConfiguration.isEnabled()).thenReturn(true);
    when(onboardingItem1.applies()).thenReturn(true);
    when(onboardingItem1.getType()).thenReturn("type1");
    when(onboardingItem1.getPriority()).thenReturn(2);
    when(onboardingItem2.applies()).thenReturn(true);
    when(onboardingItem2.getType()).thenReturn("type2");
    when(onboardingItem2.getPriority()).thenReturn(1);
    when(onboardingItem3.applies()).thenReturn(true);
    when(onboardingItem3.getType()).thenReturn("type3");
    when(onboardingItem3.getPriority()).thenReturn(0);

    underTest = new OnboardingManagerImpl(ImmutableSet.of(onboardingItem1, onboardingItem2, onboardingItem3),
        onboardingConfiguration);
  }

  @Test
  public void testNeedsOnboardingInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final boolean[] result = new boolean[1];
    final boolean[] isVirtual = new boolean[1];

    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtual[0] = Thread.currentThread().isVirtual();
        // Execute the test
        result[0] = underTest.needsOnboarding();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the thread was virtual
    assertTrue(isVirtual[0], "Test should run in a virtual thread");
    // Verify the result
    assertThat(result[0], is(true));
  }

  @Test
  public void testNeedsOnboardingNotAllItemsInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final boolean[] result = new boolean[1];
    final boolean[] isVirtual = new boolean[1];

    // Configure the mocks for this specific test
    when(onboardingItem1.applies()).thenReturn(false);
    when(onboardingItem2.applies()).thenReturn(false);
    when(onboardingItem3.applies()).thenReturn(true);

    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtual[0] = Thread.currentThread().isVirtual();
        // Execute the test
        result[0] = underTest.needsOnboarding();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the thread was virtual
    assertTrue(isVirtual[0], "Test should run in a virtual thread");
    // Verify the result
    assertThat(result[0], is(true));
  }

  @Test
  public void testGetOnboardingItemsInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final List<OnboardingItem>[] items = new List[1];
    final boolean[] isVirtual = new boolean[1];

    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtual[0] = Thread.currentThread().isVirtual();
        // Execute the test
        items[0] = underTest.getOnboardingItems();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the thread was virtual
    assertTrue(isVirtual[0], "Test should run in a virtual thread");
    // Verify the results
    assertThat(items[0].size(), is(3));
    assertThat(items[0].get(0).getType(), is("type3"));
    assertThat(items[0].get(1).getType(), is("type2"));
    assertThat(items[0].get(2).getType(), is("type1"));
  }

  @Test
  public void testGetOnboardingItemsNoItemsInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final List<OnboardingItem>[] items = new List[1];
    final boolean[] isVirtual = new boolean[1];

    // Create a new instance with no items
    OnboardingManagerImpl emptyManager = new OnboardingManagerImpl(Collections.emptySet(), onboardingConfiguration);

    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtual[0] = Thread.currentThread().isVirtual();
        // Execute the test
        items[0] = emptyManager.getOnboardingItems();
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the thread was virtual
    assertTrue(isVirtual[0], "Test should run in a virtual thread");
    // Verify the result
    assertThat(items[0].size(), is(0));
  }

  @Test
  public void testConcurrentAccessInVirtualThreads() throws Exception {
    final int threadCount = 5;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final boolean[] allVirtual = {true};
    
    // Start multiple virtual threads that will all access the manager concurrently
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Check if this is a virtual thread
          if (!Thread.currentThread().isVirtual()) {
            allVirtual[0] = false;
          }
          
          // Access the manager methods
          underTest.needsOnboarding();
          underTest.getOnboardingItems();
        }
        catch (Exception e) {
          log.error("Error in virtual thread test", e);
          allVirtual[0] = false;
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Release all threads to run concurrently
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(10, TimeUnit.SECONDS);
    
    // Verify all threads were virtual
    assertTrue(allVirtual[0], "All threads should be virtual threads");
  }
}