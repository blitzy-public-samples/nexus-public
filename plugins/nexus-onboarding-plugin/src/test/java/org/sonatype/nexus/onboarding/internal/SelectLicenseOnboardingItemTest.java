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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.nexus.onboarding.OnboardingItemPriority;
import org.sonatype.nexus.onboarding.capability.OnboardingCapability;
import org.sonatype.nexus.onboarding.capability.OnboardingCapabilityHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SelectLicenseOnboardingItem}.
 */
@ExtendWith(MockitoExtension.class)
public class SelectLicenseOnboardingItemTest
{
  @Mock
  private InstanceStatus instanceStatus;

  @Mock
  private OnboardingCapabilityHelper onboardingCapabilityHelper;

  @Mock
  private OnboardingCapability onboardingCapability;

  private SelectLicenseOnboardingItem underTest;

  @BeforeEach
  public void setUp() {
    when(onboardingCapabilityHelper.getOnboardingCapability()).thenReturn(onboardingCapability);
    underTest = new SelectLicenseOnboardingItem(instanceStatus, onboardingCapabilityHelper);
  }

  @Test
  public void shouldReturnCorrectPriority() {
    assertEquals(OnboardingItemPriority.CONFIGURE_ANONYMOUS_ACCESS + 1, underTest.getPriority());
  }

  @Test
  public void shouldDetermineWhenOnboardingItemApplies() {
    // Case 1: New instance but registration completed
    when(instanceStatus.isNew()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);
    assertFalse(underTest.applies());

    // Case 2: New instance and registration not completed
    when(instanceStatus.isNew()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);
    assertTrue(underTest.applies());

    // Case 3: Not a new instance but registration completed
    when(instanceStatus.isNew()).thenReturn(false);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);
    assertFalse(underTest.applies());

    // Case 4: Not a new instance and registration not completed
    when(instanceStatus.isNew()).thenReturn(false);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);
    assertFalse(underTest.applies());
  }
}