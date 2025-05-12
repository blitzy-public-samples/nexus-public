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
 * 
 * Validates the behavior of the EULA onboarding item under different conditions:
 * - When running Community Edition with EULA not accepted
 * - When running Community Edition with EULA already accepted
 * - When running Professional Edition (where EULA doesn't apply)
 */
@ExtendWith(MockitoExtension.class)
class CommunityEulaOnboardingItemTest
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
   * Verifies that the onboarding item applies when running Community Edition
   * and the EULA has not been accepted yet.
   */
  @Test
  void appliesWhenCommunityAndEulaNotAccepted() {
    when(mockGlobalKeyValueStore.getKey("nexus.community.eula.accepted")).thenReturn(Optional.empty());
    assertTrue(underTest.applies());
  }

  /**
   * Verifies that the onboarding item does not apply when running Community Edition
   * but the EULA has already been accepted.
   */
  @Test
  void appliesWhenCommunityAndEulaAccepted() {
    NexusKeyValue eulaStatus = new NexusKeyValue();
    eulaStatus.setValue(Map.of("accepted", true));
    when(mockGlobalKeyValueStore.getKey("nexus.community.eula.accepted")).thenReturn(Optional.of(eulaStatus));
    assertFalse(underTest.applies());
  }

  /**
   * Verifies that the onboarding item does not apply when running Professional Edition,
   * regardless of EULA acceptance status.
   */
  @Test
  void appliesWhenNotCommunity() {
    when(mockApplicationVersion.getEdition()).thenReturn("PRO");
    assertFalse(underTest.applies());
  }
}
