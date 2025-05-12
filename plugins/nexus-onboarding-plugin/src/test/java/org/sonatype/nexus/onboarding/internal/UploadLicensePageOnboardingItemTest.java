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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.onboarding.OnboardingItemPriority;
import org.sonatype.nexus.onboarding.capability.OnboardingCapability;
import org.sonatype.nexus.onboarding.capability.OnboardingCapabilityHelper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link UploadLicensePageOnboardingItem}.
 */
@ExtendWith(MockitoExtension.class)
class UploadLicensePageOnboardingItemTest
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
  void setUp() {
    when(onboardingCapabilityHelper.getOnboardingCapability()).thenReturn(onboardingCapability);
    underTest = new UploadLicensePageOnboardingItem(instanceStatus, onboardingCapabilityHelper);
  }

  /**
   * Verifies that applies() returns false when instance is new and registration is completed.
   */
  @Test
  void appliesReturnsFalseForNewInstanceAndRegistrationCompleted() {
    when(instanceStatus.isNew()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);

    assertThat(underTest.applies(), is(false));
  }

  /**
   * Verifies that applies() returns true when instance is new and registration is not completed.
   */
  @Test
  void appliesReturnsTrueForNewInstanceAndRegistrationNotCompleted() {
    when(instanceStatus.isNew()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);

    assertThat(underTest.applies(), is(true));
  }

  /**
   * Verifies that applies() returns false when instance is not new and registration is completed.
   */
  @Test
  void appliesReturnsFalseForNotNewInstanceAndRegistrationCompleted() {
    when(instanceStatus.isNew()).thenReturn(false);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(true);

    assertThat(underTest.applies(), is(false));
  }

  /**
   * Verifies that applies() returns false when instance is not new and registration is not completed.
   */
  @Test
  void appliesReturnsFalseForNotNewInstanceAndRegistrationNotCompleted() {
    when(instanceStatus.isNew()).thenReturn(false);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);

    assertThat(underTest.applies(), is(false));
  }

  /**
   * Verifies the onboarding item type is correctly returned.
   */
  @Test
  void getTypeReturnsCorrectType() {
    assertThat(underTest.getType(), is("UploadLicensePage"));
  }

  /**
   * Verifies the onboarding item priority is correctly returned.
   */
  @Test
  void getPriorityReturnsCorrectPriority() {
    assertThat(underTest.getPriority(), is(OnboardingItemPriority.UPLOAD_LICENSE));
  }
}
