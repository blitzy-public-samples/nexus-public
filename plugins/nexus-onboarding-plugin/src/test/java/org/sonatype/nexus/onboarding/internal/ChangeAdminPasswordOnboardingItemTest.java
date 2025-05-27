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
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ChangeAdminPasswordOnboardingItemTest
    extends TestSupport
{
  @Mock
  private SecuritySystem securitySystem;

  private ChangeAdminPasswordOnboardingItem underTest;

  @BeforeEach
  public void setup() {
    underTest = new ChangeAdminPasswordOnboardingItem(securitySystem);
  }

  @Test
  public void applies() throws Exception {
    User user = new User();
    user.setStatus(UserStatus.changepassword);

    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    assertThat(underTest.applies(), is(true));
  }

  @Test
  public void appliesStatusActive() throws Exception {
    User user = new User();
    user.setStatus(UserStatus.active);

    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    assertThat(underTest.applies(), is(false));
  }

  @Test
  public void appliesStatusDisabled() throws Exception {
    User user = new User();
    user.setStatus(UserStatus.disabled);

    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    assertThat(underTest.applies(), is(false));
  }

  @Test
  public void appliesStatusLocked() throws Exception {
    User user = new User();
    user.setStatus(UserStatus.locked);

    when(securitySystem.getUser("admin", "default")).thenReturn(user);

    assertThat(underTest.applies(), is(false));
  }

  @Test
  public void appliesUserNotFound() throws Exception {
    when(securitySystem.getUser("admin", "default")).thenThrow(new UserNotFoundException("admin"));

    assertThat(underTest.applies(), is(false));
  }

  @Test
  public void appliesUserManagerNotFound() throws Exception {
    when(securitySystem.getUser("admin", "default")).thenThrow(new NoSuchUserManagerException("default"));

    assertThat(underTest.applies(), is(false));
  }
}
