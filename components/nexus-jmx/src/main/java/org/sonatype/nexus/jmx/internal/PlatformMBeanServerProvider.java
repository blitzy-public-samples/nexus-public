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
 * Enhanced for Java 21 compatibility with improved error handling for JMX access restrictions
 * and diagnostic logging using String Templates.
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
      log.debug(STR."Accessing platform MBeanServer with Java \{System.getProperty("java.version")}");
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      log.debug(STR."Successfully obtained platform MBeanServer: \{server}");
      return server;
    }
    catch (SecurityException e) {
      // Handle Java 21's enhanced security model restrictions
      log.error(STR."Security restriction accessing platform MBeanServer: \{e.getMessage()}", e);
      throw new RuntimeException(STR."Unable to access platform MBeanServer due to security restrictions: \{e.getMessage()}", e);
    }
    catch (Exception e) {
      // Handle other potential JMX access issues
      log.error(STR."Error accessing platform MBeanServer: \{e.getMessage()}", e);
      throw new RuntimeException(STR."Unable to access platform MBeanServer: \{e.getMessage()}", e);
    }
  }
}