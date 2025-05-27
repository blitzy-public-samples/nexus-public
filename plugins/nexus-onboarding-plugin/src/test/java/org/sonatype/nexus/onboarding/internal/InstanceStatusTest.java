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

  @Test
  public void instanceIsNewWhenAnonymousNotConfigured() {
    when(anonymousManager.isConfigured()).thenReturn(false);

    assertThat(underTest.isNew(), is(true));
    assertThat(underTest.isUpgraded(), is(false));
  }

  @Test
  public void instanceIsUpgradedWhenAnonymousConfiguredButRegistrationNotStarted() {
    when(anonymousManager.isConfigured()).thenReturn(true);
    when(onboardingCapability.isRegistrationStarted()).thenReturn(false);

    assertThat(underTest.isNew(), is(false));
    assertThat(underTest.isUpgraded(), is(true));
  }

  @Test
  public void instanceIsNewWhenAnonymousConfiguredAndRegistrationStartedButNotCompleted() {
    when(anonymousManager.isConfigured()).thenReturn(true);
    when(onboardingCapability.isRegistrationStarted()).thenReturn(true);
    when(onboardingCapability.isRegistrationCompleted()).thenReturn(false);

    assertThat(underTest.isNew(), is(true));
    assertThat(underTest.isUpgraded(), is(false));
  }
  
  /**
   * This test demonstrates Java 21 pattern matching for switch statements
   * to test different instance status scenarios using a record to represent state.
   */
  @Test
  public void patternMatchingForInstanceStatus() {
    // Define a record to represent instance state
    record InstanceState(boolean anonymousConfigured, boolean registrationStarted, boolean registrationCompleted) {}
    
    // Test all possible combinations of instance state
    for (boolean anonymousConfigured : new boolean[] {true, false}) {
      for (boolean registrationStarted : new boolean[] {true, false}) {
        for (boolean registrationCompleted : new boolean[] {true, false}) {
          // Skip invalid state: if registration is not started, it cannot be completed
          if (!registrationStarted && registrationCompleted) {
            continue;
          }
          
          // Set up the mocks for this state combination
          when(anonymousManager.isConfigured()).thenReturn(anonymousConfigured);
          when(onboardingCapability.isRegistrationStarted()).thenReturn(registrationStarted);
          when(onboardingCapability.isRegistrationCompleted()).thenReturn(registrationCompleted);
          
          // Create an instance state record for this combination
          InstanceState state = new InstanceState(anonymousConfigured, registrationStarted, registrationCompleted);
          
          // Use pattern matching with switch to determine expected behavior
          boolean expectedIsNew = switch (state) {
            // Anonymous not configured -> instance is new
            case InstanceState(false, _, _) -> true;
            
            // Anonymous configured, registration started but not completed -> instance is new
            case InstanceState(true, true, false) -> true;
            
            // All other cases -> instance is not new
            default -> false;
          };
          
          boolean expectedIsUpgraded = switch (state) {
            // Anonymous configured, registration not started -> instance is upgraded
            case InstanceState(true, false, _) -> true;
            
            // All other cases -> instance is not upgraded
            default -> false;
          };
          
          // Assert that the actual behavior matches the expected behavior
          assertThat("isNew() for state: " + state, underTest.isNew(), is(expectedIsNew));
          assertThat("isUpgraded() for state: " + state, underTest.isUpgraded(), is(expectedIsUpgraded));
        }
      }
    }
  }
}