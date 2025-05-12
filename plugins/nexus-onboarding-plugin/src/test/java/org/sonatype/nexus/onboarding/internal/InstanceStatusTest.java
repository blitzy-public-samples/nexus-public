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
import org.sonatype.nexus.onboarding.capability.OnboardingCapability;
import org.sonatype.nexus.onboarding.capability.OnboardingCapabilityHelper;
import org.sonatype.nexus.security.anonymous.AnonymousManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InstanceStatus} that verify the instance status determination logic.
 */
@ExtendWith(MockitoExtension.class)
public class InstanceStatusTest
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

  /**
   * Verifies that the instance is considered new when anonymous access is not configured.
   */
  @Test
  public void shouldReturnInstanceIsNewWhenAnonymousNotConfigured() {
    when(anonymousManager.isConfigured()).thenReturn(false);

    assertThat(underTest.isNew(), is(true));
    assertThat(underTest.isUpgraded(), is(false));
  }

  /**
   * Verifies that the instance is considered upgraded when anonymous access is configured
   * but registration has not been started.
   */
  @Test
  public void shouldReturnInstanceIsUpgradedWhenAnonymousConfiguredButRegistrationNotStarted() {
    when(anonymousManager.isConfigured()).thenReturn(true);
    when(onboardingCapability.isRegistrationStarted()).thenReturn(false);

    assertThat(underTest.isNew(), is(false));
    assertThat(underTest.isUpgraded(), is(true));
  }

  /**
   * Verifies that the instance is considered new when anonymous access is configured,
   * registration has been started, but not yet completed.
   */
  @Test
  public void shouldReturnInstanceIsNewWhenAnonymousConfiguredAndRegistrationStartedButNotCompleted() {
    when(anonymousManager.isConfigured()).thenReturn(true);
    when(onboardingCapability.isRegistrationStarted()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);

    assertThat(underTest.isNew(), is(true));
    assertThat(underTest.isUpgraded(), is(false));
  }
}
