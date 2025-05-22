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
package com.sonatype.nexus.ssl.plugin.internal;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityContributor;
import org.sonatype.nexus.security.config.SecurityContributorSupport;

/**
 * SSL security configuration.
 * <p>
 * Updated for compatibility with Apache Shiro 2.0.0 and Java 21.
 *
 * @since 3.0
 */
@Named
@Singleton
public class SslSecurityContributor
    extends SecurityContributorSupport
    implements SecurityContributor
{
  private static final Logger LOG = Logger.getLogger(SslSecurityContributor.class.getName());
  
  public static final String SSL_DOMAIN = "ssl-truststore";

  public static final String SSL_PRIV_ID_PREFIX = "nx-ssl-truststore";

  /**
   * Returns the security configuration contribution for SSL truststore privileges.
   * <p>
   * This implementation is compatible with Apache Shiro 2.0.0 and Java 21's enhanced security model.
   * It creates CRUD and ALL application privileges for the SSL truststore domain.
   *
   * @return The security configuration containing SSL truststore privileges
   */
  @Override
  public SecurityConfiguration getContribution() {
    try {
      MemorySecurityConfiguration config = new MemorySecurityConfiguration();

      // Create and add all privileges for SSL truststore domain
      var privileges = createCrudAndAllApplicationPrivileges(SSL_PRIV_ID_PREFIX, SSL_DOMAIN);
      Objects.requireNonNull(privileges, "Failed to create SSL truststore privileges");
      privileges.forEach(privilege -> {
        try {
          config.addPrivilege(privilege);
        } catch (Exception e) {
          LOG.log(Level.WARNING, "Failed to add privilege: " + privilege.getId(), e);
        }
      });

      return config;
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to create SSL security contribution", e);
      // Return empty configuration rather than null to prevent NPEs
      return new MemorySecurityConfiguration();
    }
  }
}