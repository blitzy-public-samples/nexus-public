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

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.capability.CapabilitySupport;
import org.sonatype.nexus.common.app.BaseUrlManager;

import static java.lang.StringTemplate.STR;

/**
 * Base-URL capability.
 * 
 * Manages the configuration of the base URL for the Nexus Repository instance,
 * particularly when running behind a reverse proxy.
 *
 * @since 3.0
 * @see BaseUrlManager
 * @see BaseUrlCapabilityConfiguration
 */
@Named(BaseUrlCapabilityDescriptor.TYPE_ID)
public class BaseUrlCapability
    extends CapabilitySupport<BaseUrlCapabilityConfiguration>
{
  private final BaseUrlManager baseUrlManager;

  /**
   * Creates a new BaseUrlCapability with the specified BaseUrlManager.
   * 
   * @param baseUrlManager the manager responsible for handling base URL operations
   * @throws NullPointerException if baseUrlManager is null
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
    baseUrlManager.setUrl(url);
    log.info(STR"Base URL activated: \{url}");
  }

  @Override
  protected void onPassivate(final BaseUrlCapabilityConfiguration config) throws Exception {
    String previousUrl = baseUrlManager.getUrl();
    baseUrlManager.setUrl(null);
    log.info(STR"Base URL deactivated (was: \{previousUrl})");
  }
}