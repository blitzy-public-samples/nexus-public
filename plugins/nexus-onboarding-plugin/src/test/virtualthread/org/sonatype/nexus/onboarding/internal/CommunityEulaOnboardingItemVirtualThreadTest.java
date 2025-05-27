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
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.NexusKeyValue;

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
 * Tests {@link CommunityEulaOnboardingItem} in a virtual thread environment.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
class CommunityEulaOnboardingItemVirtualThreadTest
    extends TestSupport
{
  @Mock
  private ApplicationVersion mockApplicationVersion;

  @Mock
  private GlobalKeyValueStore mockGlobalKeyValueStore;

  @InjectMocks
  private CommunityEulaOnboardingItem underTest;

  @BeforeEach
  void setUp() {
    when(mockApplicationVersion.getEdition()).thenReturn("COMMUNITY");
  }

  /**
   * Tests that applies() returns true when running in COMMUNITY edition with EULA not accepted,
   * executed within a virtual thread.
   */
  @Test
  void testAppliesWhenCommunityAndEulaNotAccepted() throws Exception {
    when(mockGlobalKeyValueStore.getKey("nexus.community.eula.accepted")).thenReturn(Optional.empty());
    
    // Execute test in a virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    final boolean[] result = new boolean[1];
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Execute the actual test
        result[0] = underTest.applies();
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    
    // Verify the result
    assertTrue(result[0], "applies() should return true for COMMUNITY edition with EULA not accepted");
  }

  /**
   * Tests that applies() returns false when running in COMMUNITY edition with EULA accepted,
   * executed within a virtual thread.
   */
  @Test
  void testAppliesWhenCommunityAndEulaAccepted() throws Exception {
    NexusKeyValue eulaStatus = new NexusKeyValue();
    eulaStatus.setValue(Map.of("accepted", true));
    when(mockGlobalKeyValueStore.getKey("nexus.community.eula.accepted")).thenReturn(Optional.of(eulaStatus));
    
    // Execute test in a virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    final boolean[] result = new boolean[1];
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Execute the actual test
        result[0] = underTest.applies();
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    
    // Verify the result
    assertFalse(result[0], "applies() should return false for COMMUNITY edition with EULA accepted");
  }

  /**
   * Tests that applies() returns false when running in a non-COMMUNITY edition,
   * executed within a virtual thread.
   */
  @Test
  void testAppliesWhenNotCommunity() throws Exception {
    when(mockApplicationVersion.getEdition()).thenReturn("PRO");
    
    // Execute test in a virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    final boolean[] result = new boolean[1];
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Execute the actual test
        result[0] = underTest.applies();
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    
    // Verify the result
    assertFalse(result[0], "applies() should return false for non-COMMUNITY edition");
  }
}