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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.sonatype.nexus.capability.CapabilityConfigurationSupport;

/**
 * Configuration for the Onboarding capability.
 *
 * @since 3.0
 */
public class OnboardingCapabilityConfiguration
    extends CapabilityConfigurationSupport
{
  public static final String PRO_STARTER_INFO_PAGE_COMPLETED = "proStarterInfoPageCompleted";

  public static final boolean DEFAULT_PRO_STARTER_INFO_PAGE_COMPLETED = false;

  public static final boolean DEFAULT_REGISTRATION_STARTED = false;

  public static final String REGISTRATION_STARTED = "registrationStarted";

  public static final boolean DEFAULT_REGISTRATION_COMPLETED = false;

  public static final String REGISTRATION_COMPLETED = "registrationCompleted";

  private boolean proStarterInfoPageCompleted;

  private boolean registrationStarted;

  private boolean registrationCompleted;

  /**
   * Creates a new configuration instance from the provided properties map.
   *
   * @param properties the configuration properties (must not be null)
   */
  public OnboardingCapabilityConfiguration(final Map<String, String> properties) {
    Objects.requireNonNull(properties, "Properties map cannot be null");
    this.proStarterInfoPageCompleted =
        parseBoolean(properties.get(PRO_STARTER_INFO_PAGE_COMPLETED), DEFAULT_PRO_STARTER_INFO_PAGE_COMPLETED);
    this.registrationStarted = parseBoolean(properties.get(REGISTRATION_STARTED), DEFAULT_REGISTRATION_STARTED);
    this.registrationCompleted = parseBoolean(properties.get(REGISTRATION_COMPLETED), DEFAULT_REGISTRATION_COMPLETED);
  }

  /**
   * @return whether registration has been started
   */
  public boolean isRegistrationStarted() {
    return registrationStarted;
  }

  /**
   * Sets whether registration has been started.
   *
   * @param registrationStarted the registration started flag
   * @return this configuration instance for method chaining
   */
  public OnboardingCapabilityConfiguration setRegistrationStarted(final boolean registrationStarted) {
    this.registrationStarted = registrationStarted;
    return this;
  }

  /**
   * @return whether registration has been completed
   */
  public boolean isRegistrationCompleted() {
    return registrationCompleted;
  }

  /**
   * Sets whether registration has been completed.
   *
   * @param registrationCompleted the registration completed flag
   * @return this configuration instance for method chaining
   */
  public OnboardingCapabilityConfiguration setRegistrationCompleted(final boolean registrationCompleted) {
    this.registrationCompleted = registrationCompleted;
    return this;
  }

  /**
   * Converts this configuration to a properties map.
   *
   * @return a new map containing the configuration properties
   */
  public Map<String, String> asMap() {
    final Map<String, String> properties = new HashMap<>();
    properties.put(PRO_STARTER_INFO_PAGE_COMPLETED, String.valueOf(proStarterInfoPageCompleted));
    properties.put(REGISTRATION_STARTED, String.valueOf(registrationStarted));
    properties.put(REGISTRATION_COMPLETED, String.valueOf(registrationCompleted));
    return properties;
  }

  @Override
  public String toString() {
    return STR."{getClass().getSimpleName()}{
        proStarterInfoPageCompleted={proStarterInfoPageCompleted};
        registrationStarted={registrationStarted};
        registrationCompleted={registrationCompleted};
      }";
  }
}