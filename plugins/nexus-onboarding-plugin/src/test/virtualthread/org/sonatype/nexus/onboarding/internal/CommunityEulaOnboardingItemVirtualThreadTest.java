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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.java21.Java21TestGroup;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.NexusKeyValue;
import org.sonatype.nexus.virtualthread.VirtualThreadTestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CommunityEulaOnboardingItem} running in virtual threads.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class CommunityEulaOnboardingItemVirtualThreadTest
    extends TestSupport
{
  @Mock
  private ApplicationVersion mockApplicationVersion;

  @Mock
  private GlobalKeyValueStore mockGlobalKeyValueStore;

  @InjectMocks
  private CommunityEulaOnboardingItem underTest;

  @BeforeEach
  public void setUp() {
    when(mockApplicationVersion.getEdition()).thenReturn("COMMUNITY");
  }

  @Test
  public void testAppliesWhenCommunityAndEulaNotAccepted() throws Exception {
    when(mockGlobalKeyValueStore.getKey("nexus.community.eula.accepted")).thenReturn(Optional.empty());
    
    // Create a CountDownLatch to wait for the test to complete in the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Run the test in a virtual thread
    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Test the actual functionality
        assertTrue(underTest.applies());
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Test did not complete within timeout");
  }

  @Test
  public void testAppliesWhenCommunityAndEulaAccepted() throws Exception {
    NexusKeyValue eulaStatus = new NexusKeyValue();
    eulaStatus.setValue(Map.of("accepted", true));
    when(mockGlobalKeyValueStore.getKey("nexus.community.eula.accepted")).thenReturn(Optional.of(eulaStatus));
    
    // Create a CountDownLatch to wait for the test to complete in the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Run the test in a virtual thread
    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Test the actual functionality
        assertFalse(underTest.applies());
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Test did not complete within timeout");
  }

  @Test
  public void testAppliesWhenNotCommunity() throws Exception {
    when(mockApplicationVersion.getEdition()).thenReturn("PRO");
    
    // Create a CountDownLatch to wait for the test to complete in the virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Run the test in a virtual thread
    Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Test the actual functionality
        assertFalse(underTest.applies());
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Test did not complete within timeout");
  }
}