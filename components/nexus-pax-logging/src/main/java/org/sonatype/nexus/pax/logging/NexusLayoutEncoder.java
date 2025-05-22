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
package org.sonatype.nexus.pax.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.PatternLayoutEncoderBase;

/**
 * Enhanced pattern layout encoder that adds support for:
 * <ul>
 *   <li>Node name via %node pattern</li>
 *   <li>Virtual Thread awareness via %vthread pattern</li>
 *   <li>Java 21 String Template logging formats</li>
 * </ul>
 *
 * @since 3.6.1
 */
public class NexusLayoutEncoder
    extends PatternLayoutEncoderBase<ILoggingEvent>
{
  /**
   * Flag to enable String Template processing for log messages.
   */
  private boolean enableStringTemplates = true;
  
  /**
   * Flag to enable Virtual Thread metrics logging.
   */
  private boolean enableVirtualThreadMetrics = true;
  
  /**
   * Sets whether String Template processing is enabled for log messages.
   *
   * @param enableStringTemplates true to enable String Template processing, false to disable
   */
  public void setEnableStringTemplates(boolean enableStringTemplates) {
    this.enableStringTemplates = enableStringTemplates;
  }
  
  /**
   * Sets whether Virtual Thread metrics logging is enabled.
   *
   * @param enableVirtualThreadMetrics true to enable Virtual Thread metrics, false to disable
   */
  public void setEnableVirtualThreadMetrics(boolean enableVirtualThreadMetrics) {
    this.enableVirtualThreadMetrics = enableVirtualThreadMetrics;
  }
  
  @Override
  public void start() {
    PatternLayout patternLayout = new PatternLayout();
    
    // Register standard converters
    patternLayout.getDefaultConverterMap().put("node", NexusNodeNameConverter.class.getName());
    
    // Register Java 21 Virtual Thread aware converter
    patternLayout.getDefaultConverterMap().put("vthread", VirtualThreadAwareConverter.class.getName());
    
    // Set context and pattern
    patternLayout.setContext(context);
    patternLayout.setPattern(getPattern());
    
    // Configure String Template processor if enabled
    if (enableStringTemplates) {
      patternLayout.addListener(new StringTemplateLogProcessor());
    }
    
    // Start the pattern layout
    patternLayout.start();
    this.layout = patternLayout;
    super.start();
  }
}