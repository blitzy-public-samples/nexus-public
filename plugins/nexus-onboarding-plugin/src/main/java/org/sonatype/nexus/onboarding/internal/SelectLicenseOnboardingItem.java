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

import java.util.Objects;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.onboarding.OnboardingItem;
import org.sonatype.nexus.onboarding.OnboardingItemPriority;
import org.sonatype.nexus.onboarding.capability.OnboardingCapabilityHelper;

/**
 * Onboarding item for license selection.
 * 
 * <p>Updated for Java 21 compatibility with Jakarta EE 9+ injection annotations
 * and modern Java practices.</p>
 *
 * @since 3.25
 */
@Named
@Singleton
@FeatureFlag(name = "nexus.onboarding.license.enabled")
public class SelectLicenseOnboardingItem
  implements OnboardingItem
{
  private final InstanceStatus instanceStatus;
  private final OnboardingCapabilityHelper onboardingCapabilityHelper;

  @Inject
  public SelectLicenseOnboardingItem(
      final InstanceStatus instanceStatus,
      final OnboardingCapabilityHelper onboardingCapabilityHelper
  ) {
    this.instanceStatus = Objects.requireNonNull(instanceStatus);
    this.onboardingCapabilityHelper = Objects.requireNonNull(onboardingCapabilityHelper);
  }

  @Override
  public String getType() {
    return "SelectLicense";
  }

  @Override
  public int getPriority() {
    return OnboardingItemPriority.SELECT_LICENSE;
  }

  @Override
  public boolean applies() {
    return instanceStatus.isNew() && !onboardingCapabilityHelper.getOnboardingCapability()
        .isRegistrationCompleted();
  }
}