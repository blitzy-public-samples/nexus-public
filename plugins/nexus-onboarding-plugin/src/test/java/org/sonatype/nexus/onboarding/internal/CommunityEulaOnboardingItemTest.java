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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.NexusKeyValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CommunityEulaOnboardingItem}.
 * Updated for JUnit Jupiter 5.10.1 and Mockito 5.8.0 compatibility with Java 21.
 */
@ExtendWith(MockitoExtension.class)
public class CommunityEulaOnboardingItemTest
    extends TestSupport
{
  @Mock
  private ApplicationVersion mockApplicationVersion;

  @Mock
  private GlobalKeyValueStore mockGlobalKeyValueStore;

  @InjectMocks
  private CommunityEulaOnboardingItem underTest;

  private static final String EULA_KEY = "nexus.community.eula.accepted";
  private static final String COMMUNITY = "COMMUNITY";
  private static final String PRO = "PRO";

  @BeforeEach
  public void setUp() {
    when(mockApplicationVersion.getEdition()).thenReturn(COMMUNITY);
  }

  @Test
  public void testAppliesWhenCommunityAndEulaNotAccepted() {
    when(mockGlobalKeyValueStore.getKey(EULA_KEY)).thenReturn(Optional.empty());
    assertTrue(underTest.applies());
  }

  @Test
  public void testAppliesWhenCommunityAndEulaAccepted() {
    NexusKeyValue eulaStatus = new NexusKeyValue();
    eulaStatus.setValue(Map.of("accepted", true));
    when(mockGlobalKeyValueStore.getKey(EULA_KEY)).thenReturn(Optional.of(eulaStatus));
    assertFalse(underTest.applies());
  }

  @Test
  public void testAppliesWhenNotCommunity() {
    when(mockApplicationVersion.getEdition()).thenReturn(PRO);
    assertFalse(underTest.applies());
  }
  
  @Test
  public void testVirtualThreadCompatibility() throws Exception {
    // Test that the class works correctly when called from a virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        // Setup the test conditions
        when(mockGlobalKeyValueStore.getKey(EULA_KEY)).thenReturn(Optional.empty());
        
        // Verify behavior is the same in a virtual thread
        assertTrue(underTest.applies());
        
        // Test with different conditions
        NexusKeyValue eulaStatus = new NexusKeyValue();
        eulaStatus.setValue(Map.of("accepted", true));
        when(mockGlobalKeyValueStore.getKey(EULA_KEY)).thenReturn(Optional.of(eulaStatus));
        assertFalse(underTest.applies());
        
        return null;
      }).get(); // Wait for completion
    }
  }
}