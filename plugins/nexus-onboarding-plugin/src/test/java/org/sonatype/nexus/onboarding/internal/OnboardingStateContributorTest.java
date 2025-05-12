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

import java.util.Arrays;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.onboarding.OnboardingConfiguration;
import org.sonatype.nexus.onboarding.OnboardingItem;
import org.sonatype.nexus.onboarding.OnboardingManager;
import org.sonatype.nexus.security.config.AdminPasswordFileManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OnboardingStateContributor} functionality.
 */
@ExtendWith(MockitoExtension.class)
public class OnboardingStateContributorTest
    extends TestSupport
{
  @Mock
  private OnboardingConfiguration onboardingConfiguration;

  @Mock
  private OnboardingManager onboardingManager;

  @Mock
  private AdminPasswordFileManager adminPasswordFileManager;

  @Mock
  private OnboardingItem onboardingItem1;

  @Mock
  private OnboardingItem onboardingItem2;

  private OnboardingStateContributor underTest;

  /**
   * Sets up the test environment with mocked dependencies.
   */
  @BeforeEach
  public void setup() {
    when(onboardingConfiguration.isEnabled()).thenReturn(true);
    when(onboardingManager.getOnboardingItems()).thenReturn(Arrays.asList(onboardingItem1, onboardingItem2));
    when(onboardingManager.needsOnboarding()).thenReturn(true);
    when(adminPasswordFileManager.exists()).thenReturn(true);
    when(adminPasswordFileManager.getPath()).thenReturn("path/to/file");

    underTest = new OnboardingStateContributor(onboardingConfiguration, onboardingManager, adminPasswordFileManager);
  }

  /**
   * Verifies that the state map contains the expected onboarding and admin password file information
   * when both onboarding is required and admin password file exists.
   */
  @Test
  public void shouldReturnCompleteStateWhenOnboardingRequiredAndPasswordFileExists() {
    Map<String, Object> state = underTest.getState();
    assertThat(state.size(), is(2));
    assertThat(state.get("onboarding.required"), is(true));
    assertThat(state.get("admin.password.file"), is("path/to/file"));
  }

  /**
   * Verifies that null is returned when onboarding is not required and admin password file doesn't exist.
   */
  @Test
  public void shouldReturnNullWhenNoOnboardingItemsAndNoPasswordFile() {
    when(onboardingManager.needsOnboarding()).thenReturn(false);
    when(adminPasswordFileManager.exists()).thenReturn(false);

    assertThat(underTest.getState(), nullValue());
  }

  /**
   * Verifies that the onboarding state is cached and subsequent changes to the onboarding manager
   * don't affect the returned state.
   */
  @Test
  public void shouldCacheOnboardingStateAfterFirstCall() {
    Map<String, Object> state = underTest.getState();
    assertThat(state.get("onboarding.required"), is(true));

    when(onboardingManager.needsOnboarding()).thenReturn(false);

    state = underTest.getState();
    assertThat(state.get("onboarding.required"), nullValue());

    // Set to true to validate that cache kicks in and still doesn't add data to the map
    when(onboardingManager.needsOnboarding()).thenReturn(true);

    state = underTest.getState();
    assertThat(state.get("onboarding.required"), nullValue());
  }

  /**
   * Verifies that the admin password file path is not included in the state when the file doesn't exist.
   */
  @Test
  public void shouldNotIncludePasswordFilePathWhenFileDoesNotExist() {
    when(adminPasswordFileManager.exists()).thenReturn(false);

    Map<String, Object> state = underTest.getState();
    assertThat(state.get("admin.password.file"), nullValue());
  }
}
