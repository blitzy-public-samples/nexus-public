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

import javax.validation.ConstraintViolationException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.onboarding.OnboardingManager;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.config.AdminPasswordFileManager;
import org.sonatype.nexus.validation.ValidationModule;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;

// JUnit Jupiter imports
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// Mockito imports
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// Hamcrest imports
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

// Jupiter assertions
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

// Mockito static imports
import static org.mockito.Mockito.verify;

// Constants
import static org.sonatype.nexus.onboarding.internal.OnboardingResource.PASSWORD_REQUIRED;

/**
 * Tests for {@link OnboardingResource}.
 * 
 * Verifies the behavior of the onboarding resource, particularly around admin password validation
 * and security system interactions.
 */
@ExtendWith(MockitoExtension.class)
public class OnboardingResourceTest
    extends TestSupport
{
  @Mock
  private OnboardingManager onboardingManager;

  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private ApplicationDirectories applicationDirectories;

  @Mock
  private AdminPasswordFileManager adminPasswordFileManager;

  private OnboardingResource underTest;

  @BeforeEach
  public void setUp() {
    underTest = Guice.createInjector(new ValidationModule(), new AbstractModule()
    {
      @Override
      protected void configure() {
        bind(OnboardingManager.class).toInstance(onboardingManager);
        bind(SecuritySystem.class).toInstance(securitySystem);
        bind(ApplicationDirectories.class).toInstance(applicationDirectories);
        bind(AdminPasswordFileManager.class).toInstance(adminPasswordFileManager);
      }
    }).getInstance(OnboardingResource.class);
  }

  /**
   * Verifies that changing the admin password with a valid password works correctly.
   */
  @Test
  public void shouldChangeAdminPasswordWithValidPassword() throws Exception {
    // When: changing the admin password with a valid password
    underTest.changeAdminPassword("newpass");

    // Then: the security system should be called to change the password
    verify(securitySystem).changePassword("admin", "newpass", false);
  }

  /**
   * Verifies that changing the admin password with an empty password fails validation.
   */
  @Test
  public void shouldFailValidationWithEmptyPassword() {
    // When: changing the admin password with an empty password
    // Then: a constraint violation exception should be thrown with the correct message
    ConstraintViolationException exception = assertThrows(ConstraintViolationException.class, () -> {
      underTest.changeAdminPassword("");
    });
    
    assertThat(exception.getConstraintViolations().iterator().next().getMessage(), is(PASSWORD_REQUIRED));
  }

  /**
   * Verifies that changing the admin password with a null password fails validation.
   */
  @Test
  public void shouldFailValidationWithNullPassword() {
    // When: changing the admin password with a null password
    // Then: a constraint violation exception should be thrown with the correct message
    ConstraintViolationException exception = assertThrows(ConstraintViolationException.class, () -> {
      underTest.changeAdminPassword(null);
    });
    
    assertThat(exception.getConstraintViolations().iterator().next().getMessage(), is(PASSWORD_REQUIRED));
  }
}