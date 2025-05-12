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
package org.sonatype.nexus.onboarding.capability;

import java.util.Map;
import jakarta.annotation.Nullable;
import jakarta.inject.Named;

import org.sonatype.nexus.capability.CapabilitySupport;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.capability.Condition;

import static java.lang.StringTemplate.STR;

import static org.sonatype.nexus.onboarding.capability.OnboardingCapabilityDescriptor.messages;

/**
 * Capability that manages the onboarding wizard state.
 * 
 * @since 3.0
 */
@Named(OnboardingCapability.TYPE_ID)
public class OnboardingCapability
    extends CapabilitySupport<OnboardingCapabilityConfiguration>
{
  public static final String TYPE_ID = "onboarding-wizard";

  public static final CapabilityType TYPE = CapabilityType.capabilityType(TYPE_ID);

  @Override
  protected OnboardingCapabilityConfiguration createConfig(final Map<String, String> properties) {
    return new OnboardingCapabilityConfiguration(properties);
  }

  @Nullable
  @Override
  protected String renderDescription() {
    // Using Java 21 string template for improved readability
    return context().isActive() ? STR."{messages.enabled()}" : STR."{messages.disabled()}";
  }

  @Override
  public Condition activationCondition() {
    // Using more readable formatting for the logical condition chain
    return conditions().logical().and(
        conditions().nexus().active(),
        conditions().capabilities().capabilityHasNoDuplicates(),
        conditions().capabilities().passivateCapabilityDuringUpdate()
    );
  }

  /**
   * @return whether registration has been started
   */
  public boolean isRegistrationStarted() {
    return getConfig().isRegistrationStarted();
  }

  /**
   * Sets whether registration has been started.
   *
   * @param registrationStarted the registration started flag
   */
  public void setRegistrationStarted(final boolean registrationStarted) {
    getConfig().setRegistrationStarted(registrationStarted);
  }

  /**
   * @return whether registration has been completed
   */
  public boolean isRegistrationCompleted() {
    return getConfig().isRegistrationCompleted();
  }

  /**
   * Sets whether registration has been completed.
   *
   * @param registrationCompleted the registration completed flag
   */
  public void setRegistrationCompleted(final boolean registrationCompleted) {
    getConfig().setRegistrationCompleted(registrationCompleted);
  }
}
