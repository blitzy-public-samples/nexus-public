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
package org.sonatype.nexus.audit.internal;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.log.LogConfigurationCustomizer;
import org.sonatype.nexus.common.log.LoggerLevel;

/**
 * Audit {@link LogConfigurationCustomizer} for configuring audit logging.
 * <p>
 * This implementation is compatible with Java 21 and sets the default logger level
 * for the audit subsystem. In future versions, this could leverage Java 21 features
 * such as String Templates for more sophisticated logging configuration.
 * 
 * @since 3.0
 */
@Named
@Singleton
public class LogConfigurationCustomizerImpl
    extends ComponentSupport
    implements LogConfigurationCustomizer
{
  /**
   * Customizes the logging configuration for the audit subsystem.
   * <p>
   * Sets the logger level for the "org.sonatype.nexus.audit" package to the default level.
   * This ensures proper logging of audit events throughout the application.
   * 
   * @param configuration the logging configuration to customize
   */
  @Override
  public void customize(final Configuration configuration) {
    // Configure the audit logger with default level
    // In Java 21, this could potentially use String Templates for more readable logging configuration
    // Example with String Templates (preview feature): 
    // String loggerPath = "org.sonatype.nexus.audit";
    // log.debug(STR."Configuring audit logger: \{loggerPath} with level: \{LoggerLevel.DEFAULT}");
    configuration.setLoggerLevel("org.sonatype.nexus.audit", LoggerLevel.DEFAULT);
  }
}