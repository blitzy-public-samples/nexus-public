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
package org.sonatype.nexus.coreui;

import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.common.log.LogConfigurationCustomizer;
import org.sonatype.nexus.common.log.LoggerLevel;

/**
 * Core UI {@link LogConfigurationCustomizer}.
 * 
 * Updated to use Java 21 String Templates for improved log message formatting.
 * String Templates provide a more readable and efficient way to format log messages
 * compared to traditional string concatenation or String.format().
 * 
 * The STR template processor automatically converts embedded expressions to strings
 * and combines them with the template text, resulting in cleaner and more maintainable code.
 *
 * @since 3.0
 */
@Singleton
@Named
public class LogConfigurationCustomizerImpl
    implements LogConfigurationCustomizer
{
  private static final Logger log = LoggerFactory.getLogger(LogConfigurationCustomizerImpl.class);
  
  @Override
  public void customize(final Configuration configuration) {
    String packageName = "org.sonatype.nexus.coreui";
    LoggerLevel level = LoggerLevel.DEFAULT;
    
    // Using Java 21 String Templates for more efficient and readable log message formatting
    // This replaces traditional string concatenation with the new STR"" template syntax
    if (log.isDebugEnabled()) {
      // Example of using String Templates for logging - more readable and efficient than concatenation
      log.debug(STR."Configuring logger level for package \{packageName} to \{level}");
      
      // Example of using String Templates with expressions
      log.debug(STR."Configuration timestamp: \{System.currentTimeMillis()} ms");
      
      // Example of using String Templates with conditional expressions
      log.debug(STR."Logger level is default: \{level == LoggerLevel.DEFAULT}");
      
      // Example comparing old style vs. new String Template style
      // Old style with concatenation:
      // log.debug("Found " + configuration.getLoggerNames().size() + " loggers in configuration");
      // New style with String Templates:
      log.debug(STR."Found \{configuration.getLoggerNames().size()} loggers in configuration");
      
      // Example with multiple expressions and formatting
      String className = this.getClass().getSimpleName();
      log.debug(STR."\{className} is applying configuration at thread \{Thread.currentThread().getName()}");
    }
    
    // Apply the configuration
    configuration.setLoggerLevel(packageName, level);
  }
}