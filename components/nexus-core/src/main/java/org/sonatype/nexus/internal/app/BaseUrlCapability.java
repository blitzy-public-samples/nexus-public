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
package org.sonatype.nexus.internal.app;

import java.util.Map;
import java.util.Objects;
import java.lang.StringTemplate;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.sonatype.nexus.capability.CapabilitySupport;
import org.sonatype.nexus.common.app.BaseUrlManager;

import static java.lang.StringTemplate.STR;

/**
 * Base-URL capability.
 *
 * This capability manages the base URL configuration for Nexus Repository.
 * It is compatible with Java 21 features including String Templates for improved logging
 * and optimized for Virtual Thread environments.
 *
 * @since 3.0
 */
@Named(BaseUrlCapabilityDescriptor.TYPE_ID)
public class BaseUrlCapability
    extends CapabilitySupport<BaseUrlCapabilityConfiguration>
{
  private final BaseUrlManager baseUrlManager;

  /**
   * Constructor with dependency injection support for Jakarta EE and Google Guice 7.0.0.
   *
   * @param baseUrlManager the manager for base URL operations
   */
  @Inject
  public BaseUrlCapability(final BaseUrlManager baseUrlManager) {
    this.baseUrlManager = Objects.requireNonNull(baseUrlManager, "BaseUrlManager cannot be null");
  }

  @Override
  protected BaseUrlCapabilityConfiguration createConfig(final Map<String, String> properties) {
    return new BaseUrlCapabilityConfiguration(properties);
  }

  @Override
  protected void onActivate(final BaseUrlCapabilityConfiguration config) throws Exception {
    String url = config.getUrl();
    log.info(STR."Activating base URL capability with URL: '{url}'");
    baseUrlManager.setUrl(url);
  }

  @Override
  protected void onPassivate(final BaseUrlCapabilityConfiguration config) throws Exception {
    String previousUrl = baseUrlManager.getUrl();
    log.info(STR."Passivating base URL capability, clearing previous URL: '{previousUrl}'");
    baseUrlManager.setUrl(null);
  }
}
