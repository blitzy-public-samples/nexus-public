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
package org.sonatype.nexus.repository.httpbridge.legacy;

import java.util.Collection;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.common.property.SystemPropertiesHelper;
import org.sonatype.nexus.repository.httpbridge.internal.HttpBridgeModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.capability.CapabilityReferenceFilterBuilder.capabilities;

/**
 * Helper class to determine if legacy URL support is enabled.
 * 
 * This class checks both system properties and capability registry to determine
 * if legacy URL support should be active.
 * 
 * @since 3.7
 */
@Named
@Singleton
public class LegacyUrlEnabledHelper
{
  // Use the system property to determine if legacy content is supported by default
  private final boolean supportLegacyContent = SystemPropertiesHelper
      .getBoolean(HttpBridgeModule.class.getName() + ".legacy", false);

  private final CapabilityRegistry capabilities;

  @Inject
  public LegacyUrlEnabledHelper(final CapabilityRegistry capabilities)
  {
    this.capabilities = checkNotNull(capabilities);
  }

  /**
   * Determines if legacy URL support is enabled.
   * 
   * @return true if legacy URL support is enabled via system property or active capability
   */
  public boolean isEnabled() {
    return supportLegacyContent || isLegacyUrlCapabilityActive();
  }

  /**
   * Checks if the legacy URL capability is active in the capability registry.
   * 
   * @return true if the legacy URL capability is active
   */
  private boolean isLegacyUrlCapabilityActive() {
    // Get all capability references matching the LegacyUrlCapabilityDescriptor type
    Collection<? extends CapabilityReference> references = capabilities
        .get(capabilities().withType(LegacyUrlCapabilityDescriptor.TYPE));

    if (references.isEmpty()) {
      return false;
    }

    // Check if the first matching capability is active
    return references.iterator().next().context().isActive();
  }
}