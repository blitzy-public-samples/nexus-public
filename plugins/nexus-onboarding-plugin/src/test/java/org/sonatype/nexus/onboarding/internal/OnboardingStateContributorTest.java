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

import java.util.List;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OnboardingStateContributor}.
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

  @BeforeEach
  public void setup() {
    when(onboardingConfiguration.isEnabled()).thenReturn(true);
    when(onboardingManager.getOnboardingItems()).thenReturn(List.of(onboardingItem1, onboardingItem2));
    when(onboardingManager.needsOnboarding()).thenReturn(true);
    when(adminPasswordFileManager.exists()).thenReturn(true);
    when(adminPasswordFileManager.getPath()).thenReturn("path/to/file");

    underTest = new OnboardingStateContributor(onboardingConfiguration, onboardingManager, adminPasswordFileManager);
  }

  @Test
  public void testGetState() {
    Map<String, Object> state = underTest.getState();
    assertEquals(2, state.size());
    assertEquals(true, state.get("onboarding.required"));
    assertEquals("path/to/file", state.get("admin.password.file"));
  }

  @Test
  public void testGetState_noItems() {
    when(onboardingManager.needsOnboarding()).thenReturn(false);
    when(adminPasswordFileManager.exists()).thenReturn(false);

    assertNull(underTest.getState());
  }

  @Test
  public void testGetState_cacheOnboardingState() {
    Map<String, Object> state = underTest.getState();
    assertEquals(true, state.get("onboarding.required"));

    when(onboardingManager.needsOnboarding()).thenReturn(false);

    state = underTest.getState();
    assertNull(state.get("onboarding.required"));

    // Set to true to validate that cache kicks in and still doesn't add data to the map
    when(onboardingManager.needsOnboarding()).thenReturn(true);

    state = underTest.getState();
    assertNull(state.get("onboarding.required"));
  }

  @Test
  public void testGetState_noAdminPasswordFile() {
    when(adminPasswordFileManager.exists()).thenReturn(false);

    Map<String, Object> state = underTest.getState();
    assertNull(state.get("admin.password.file"));
  }
  
  @Test
  public void testGetState_onlyAdminPasswordFile() {
    when(onboardingManager.needsOnboarding()).thenReturn(false);
    when(adminPasswordFileManager.exists()).thenReturn(true);
    
    Map<String, Object> state = underTest.getState();
    assertEquals(1, state.size());
    assertEquals("path/to/file", state.get("admin.password.file"));
    assertNull(state.get("onboarding.required"));
  }
}