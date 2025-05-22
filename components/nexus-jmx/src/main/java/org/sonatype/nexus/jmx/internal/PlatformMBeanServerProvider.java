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
package org.sonatype.nexus.jmx.internal;

import java.lang.management.ManagementFactory;

import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.management.JMException;
import javax.management.MBeanServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the platform {@link MBeanServer}.
 * <p>
 * Enhanced for Java 21 compatibility with improved error handling for potential JMX access
 * restrictions in Java 21's enhanced security model.
 *
 * @since 3.0
 */
@Named("platform")
@Singleton
public class PlatformMBeanServerProvider
  implements Provider<MBeanServer>
{
  private static final Logger log = LoggerFactory.getLogger(PlatformMBeanServerProvider.class);

  @Override
  public MBeanServer get() {
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      log.debug(STR."Successfully obtained platform MBeanServer: \{server}");
      return server;
    }
    catch (SecurityException e) {
      // Java 21 has enhanced security model that might restrict JMX access
      log.warn(STR."Security restriction accessing platform MBeanServer: \{e.getMessage()}");
      log.debug("Security exception details", e);
      
      // Attempt to create a new MBeanServer as fallback
      try {
        MBeanServer fallbackServer = ManagementFactory.newPlatformMBeanServerBuilder().buildMBeanServer();
        log.info(STR."Created fallback MBeanServer: \{fallbackServer}");
        return fallbackServer;
      }
      catch (JMException | SecurityException fallbackEx) {
        log.error(STR."Failed to create fallback MBeanServer: \{fallbackEx.getMessage()}", fallbackEx);
        // Re-throw original exception if fallback fails
        throw e;
      }
    }
    catch (Exception e) {
      // Handle any other unexpected exceptions
      log.error(STR."Unexpected error accessing platform MBeanServer: \{e.getMessage()}", e);
      throw e;
    }
  }
}