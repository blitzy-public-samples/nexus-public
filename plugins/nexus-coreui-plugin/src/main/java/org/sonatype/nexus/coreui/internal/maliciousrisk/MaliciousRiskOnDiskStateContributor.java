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
package org.sonatype.nexus.coreui.internal.maliciousrisk;

import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.rapture.StateContributor;

import static org.sonatype.nexus.common.app.FeatureFlags.MALWARE_RISK_ON_DISK_ENABLED;
import static org.sonatype.nexus.common.app.FeatureFlags.MALWARE_RISK_ON_DISK_ENABLED_NAMED;
import static org.sonatype.nexus.common.app.FeatureFlags.MALWARE_RISK_ON_DISK_NONADMIN_OVERRIDE_ENABLED;
import static org.sonatype.nexus.common.app.FeatureFlags.MALWARE_RISK_ON_DISK_NONADMIN_OVERRIDE_ENABLED_NAMED;

/**
 * State contributor for malicious risk on disk feature flags.
 * Updated for Java 21 compatibility by replacing Guava's ImmutableMap with Java's Map.of().
 */
@Named
@Singleton
public class MaliciousRiskOnDiskStateContributor
    implements StateContributor
{
  private final Map<String, Object> state;

  @Inject
  public MaliciousRiskOnDiskStateContributor(
      @Named(MALWARE_RISK_ON_DISK_ENABLED_NAMED) final boolean maliciousRiskOnDiskEnabled,
      @Named(MALWARE_RISK_ON_DISK_NONADMIN_OVERRIDE_ENABLED_NAMED)
      final boolean maliciousRiskOnDiskNoneAdminOverrideEnabled)
  {
    this.state = Map.of(
        MALWARE_RISK_ON_DISK_ENABLED, maliciousRiskOnDiskEnabled,
        MALWARE_RISK_ON_DISK_NONADMIN_OVERRIDE_ENABLED, maliciousRiskOnDiskNoneAdminOverrideEnabled
    );
  }

  @Override
  public Map<String, Object> getState() {
    return state;
  }
}