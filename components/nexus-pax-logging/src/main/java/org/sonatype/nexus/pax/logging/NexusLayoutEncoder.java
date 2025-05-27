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
 * Adds the ability to use %node in an encoder pattern and supports Java 21 features:
 * - String Template logging formats
 * - Virtual Thread awareness
 * - Structured logging
 *
 * @since 3.6.1
 */
public class NexusLayoutEncoder
    extends PatternLayoutEncoderBase<ILoggingEvent>
{
  /**
   * Whether to enable virtual thread metrics logging
   */
  private boolean enableVirtualThreadMetrics = true;
  
  /**
   * Whether to enable string template processing
   */
  private boolean enableStringTemplates = true;

  /**
   * Sets whether virtual thread metrics logging is enabled.
   *
   * @param enableVirtualThreadMetrics true to enable, false to disable
   */
  public void setEnableVirtualThreadMetrics(boolean enableVirtualThreadMetrics) {
    this.enableVirtualThreadMetrics = enableVirtualThreadMetrics;
  }

  /**
   * Sets whether string template processing is enabled.
   *
   * @param enableStringTemplates true to enable, false to disable
   */
  public void setEnableStringTemplates(boolean enableStringTemplates) {
    this.enableStringTemplates = enableStringTemplates;
  }

  @Override
  public void start() {
    PatternLayout patternLayout = new PatternLayout();
    
    // Register the node name converter
    patternLayout.getDefaultConverterMap().put("node", NexusNodeNameConverter.class.getName());
    
    // Register the virtual thread aware converter for Java 21
    if (enableVirtualThreadMetrics) {
      patternLayout.getDefaultConverterMap().put("vthread", VirtualThreadAwareConverter.class.getName());
      patternLayout.getDefaultConverterMap().put("vtid", VirtualThreadIdConverter.class.getName());
      patternLayout.getDefaultConverterMap().put("vtname", VirtualThreadNameConverter.class.getName());
      patternLayout.getDefaultConverterMap().put("vtmetrics", VirtualThreadMetricsConverter.class.getName());
    }
    
    // Register string template processor for structured logging
    if (enableStringTemplates) {
      patternLayout.getDefaultConverterMap().put("template", StringTemplateConverter.class.getName());
      patternLayout.getDefaultConverterMap().put("st", StringTemplateConverter.class.getName());
    }
    
    patternLayout.setContext(context);
    patternLayout.setPattern(getPattern());
    patternLayout.start();
    this.layout = patternLayout;
    super.start();
  }
}