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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.onboarding.OnboardingItemPriority;
import org.sonatype.nexus.onboarding.capability.OnboardingCapability;
import org.sonatype.nexus.onboarding.capability.OnboardingCapabilityHelper;

@ExtendWith(MockitoExtension.class)
public class SelectLicenseOnboardingItemTest
    extends TestSupport {

  @Mock
  private InstanceStatus instanceStatus;

  @Mock
  private OnboardingCapabilityHelper onboardingCapabilityHelper;

  @Mock
  private OnboardingCapability onboardingCapability;

  private SelectLicenseOnboardingItem underTest;

  @BeforeEach
  public void setup() {
    when(onboardingCapabilityHelper.getOnboardingCapability()).thenReturn(onboardingCapability);
    underTest = new SelectLicenseOnboardingItem(instanceStatus, onboardingCapabilityHelper);
  }

  @Test
  public void getPriority() {
    assertEquals(OnboardingItemPriority.CONFIGURE_ANONYMOUS_ACCESS + 1, underTest.getPriority());
  }

  @Test
  public void applies() {
    when(instanceStatus.isNew()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);
    assertFalse(underTest.applies());

    when(instanceStatus.isNew()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);
    assertTrue(underTest.applies());

    when(instanceStatus.isNew()).thenReturn(false);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);
    assertFalse(underTest.applies());

    when(instanceStatus.isNew()).thenReturn(false);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);
    assertFalse(underTest.applies());
  }
}
