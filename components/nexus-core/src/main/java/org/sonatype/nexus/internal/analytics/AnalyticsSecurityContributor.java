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
package org.sonatype.nexus.internal.analytics;

import static java.lang.StringTemplate.STR;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityContributor;
import org.sonatype.nexus.security.config.SecurityContributorSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Analytics security configuration.
 *
 * @since 3.0
 */
@Named
@Singleton
public class AnalyticsSecurityContributor
    extends SecurityContributorSupport
    implements SecurityContributor
{
  private static final Logger log = LoggerFactory.getLogger(AnalyticsSecurityContributor.class);
  
  public static final String ANALYTICS_DOMAIN = "analytics";

  public static final String ANALYTICS_ALL_PRIV_ID = "nx-analytics-all";

  public static final String ANALYTICS_ALL_PRIV_DESCRIPTION = "All permissions for Analytics";

  @Override
  public SecurityConfiguration getContribution() {
    log.debug(STR."Creating security configuration for \{ANALYTICS_DOMAIN} domain");
    
    MemorySecurityConfiguration config = new MemorySecurityConfiguration();

    config.addPrivilege(
        createApplicationPrivilege(ANALYTICS_ALL_PRIV_ID, ANALYTICS_ALL_PRIV_DESCRIPTION, ANALYTICS_DOMAIN,
            ACTION_ALL));
    
    log.debug(STR."Added privilege \{ANALYTICS_ALL_PRIV_ID} with description: \{ANALYTICS_ALL_PRIV_DESCRIPTION}");

    return config;
  }
}