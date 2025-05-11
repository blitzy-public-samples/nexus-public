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

package org.sonatype.nexus.repository.maven;

import java.util.Collection;
import java.util.Objects;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityReferenceFilterBuilder;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.utils.httpclient.UserAgentGenerator;

/**
 * Support class for Maven proxy request headers.
 * <p>
 * Updated for Java 21 compatibility with Jakarta EE annotations.
 */
@Named
public class MavenProxyRequestHeaderSupport
{
  private static final String ANALYTICS_CAPABILITY = "analytics-configuration";

  private final CapabilityRegistry capabilityRegistry;
  private final UserAgentGenerator userAgentGenerator;

  /**
   * Constructor with dependency injection.
   *
   * @param capabilityRegistry the capability registry
   * @param userAgentGenerator the user agent generator
   */
  @Inject
  public MavenProxyRequestHeaderSupport(
      final CapabilityRegistry capabilityRegistry,
      final UserAgentGenerator userAgentGenerator)
  {
    this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry);
    this.userAgentGenerator = Objects.requireNonNull(userAgentGenerator);
  }

  /**
   * Gets the user agent string for analytics.
   *
   * @return the user agent string
   */
  public String getUserAgentForAnalytics() {
    CapabilityReference capabilityReference = getCapabilityReference();
    return userAgentGenerator.buildUserAgentForAnalytics(capabilityReference);
  }

  /**
   * Gets the capability reference for analytics.
   *
   * @return the capability reference, or null if not found
   */
  @Nullable
  public CapabilityReference getCapabilityReference()
  {
    CapabilityType capabilityType = CapabilityType.capabilityType(ANALYTICS_CAPABILITY);
    Collection<? extends CapabilityReference> refs = capabilityRegistry.get(
        CapabilityReferenceFilterBuilder.capabilities().withType(capabilityType).includeNotExposed());
    CapabilityReference capabilityReference = null;
    if (!refs.isEmpty()) {
      capabilityReference = refs.iterator().next();
    }
    return capabilityReference;
  }

}