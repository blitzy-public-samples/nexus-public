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
package org.sonatype.nexus.repository.httpbridge.internal;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.repository.httpbridge.legacy.LegacyUrlEnabledHelper;

import com.google.inject.AbstractModule;
import org.eclipse.sisu.inject.MutableBeanLocator;

import static org.sonatype.nexus.common.app.FeatureFlags.JWT_ENABLED;

/**
 * Manages the injection of {@link JwtLegacyHttpBridgeModule} based on the capability being enabled or the system property.
 * Updated for Java 21 compatibility and Apache Shiro 2.0.0 with Java-JWT 4.4.0.
 *
 * @since 3.38
 */
@Named
@Singleton
@FeatureFlag(name = JWT_ENABLED)
public class JwtLegacyHttpBridgeService
  extends LegacyHttpBridgeService
{
  /**
   * Constructor with dependency injection compatible with Java 21 and Apache Shiro 2.0.0.
   *
   * @param locator The bean locator for dependency injection
   * @param legacyUrlEnabledHelper Helper to determine if legacy URLs are enabled
   */
  @Inject
  public JwtLegacyHttpBridgeService(
      final MutableBeanLocator locator,
      final LegacyUrlEnabledHelper legacyUrlEnabledHelper)
  {
    super(locator, legacyUrlEnabledHelper);
  }

  /**
   * Returns the JWT-specific legacy HTTP bridge module.
   * Compatible with Java 21 and updated JWT implementation.
   *
   * @return The JWT legacy HTTP bridge module
   */
  @Override
  protected AbstractModule getLegacyHttpBridgeModule() {
    return new JwtLegacyHttpBridgeModule();
  }
}
