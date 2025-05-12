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
package org.sonatype.nexus.onboarding;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Configuration for the onboarding feature that determines whether onboarding is enabled.
 * 
 * @since 3.17
 */
@Singleton
public class OnboardingConfiguration
{
  private final boolean enabled;

  /**
   * Creates a new instance with the specified enabled state.
   *
   * @param enabled whether onboarding is enabled, defaults to true if not specified
   */
  @Inject
  public OnboardingConfiguration(@Named("${nexus.onboarding.enabled:-true}") final boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Returns whether onboarding is enabled.
   *
   * @return true if onboarding is enabled, false otherwise
   */
  public boolean isEnabled() {
    return enabled;
  }
}