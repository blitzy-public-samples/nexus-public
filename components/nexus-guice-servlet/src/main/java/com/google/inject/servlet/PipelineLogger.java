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
package com.google.inject.servlet;

import com.google.inject.spi.DefaultBindingTargetVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs pipeline definitions, using Java 21 String Templates to align them into columns.
 * Compatible with Google Guice 7.0.0 and Jakarta EE.
 */
final class PipelineLogger
    extends DefaultBindingTargetVisitor<Object, String>
    implements ServletModuleTargetVisitor<Object, String>
{
  private static final String NL = System.getProperty("line.separator");

  private static final Logger log = LoggerFactory.getLogger(DynamicGuiceFilter.class);

  private static final int PADDING = 30;

  private static final PipelineLogger THIS = new PipelineLogger();

  /**
   * Dumps filter definitions to the debug log.
   * 
   * @param filterDefinitions the filter definitions to log
   */
  public static void dump(FilterDefinition[] filterDefinitions) {
    if (log.isDebugEnabled()) {
      final StringBuilder buf = new StringBuilder("Updated filter definitions:");
      for (final FilterDefinition fd : filterDefinitions) {
        buf.append(NL).append(fd.acceptExtensionVisitor(PipelineLogger.THIS, null));
      }
      log.debug(buf.toString());
    }
  }

  /**
   * Dumps servlet definitions to the debug log.
   * 
   * @param servletDefinitions the servlet definitions to log
   */
  public static void dump(ServletDefinition[] servletDefinitions) {
    if (log.isDebugEnabled()) {
      final StringBuilder buf = new StringBuilder("Updated servlet definitions:");
      for (final ServletDefinition sd : servletDefinitions) {
        buf.append(NL).append(sd.acceptExtensionVisitor(PipelineLogger.THIS, null));
      }
      log.debug(buf.toString());
    }
  }

  @Override
  public String visit(LinkedFilterBinding binding) {
    return format(binding.getPattern(), binding.getLinkedKey().getTypeLiteral());
  }

  @Override
  public String visit(InstanceFilterBinding binding) {
    return format(binding.getPattern(), binding.getFilterInstance().getClass());
  }

  @Override
  public String visit(LinkedServletBinding binding) {
    return format(binding.getPattern(), binding.getLinkedKey().getTypeLiteral());
  }

  @Override
  public String visit(InstanceServletBinding binding) {
    return format(binding.getPattern(), binding.getServletInstance().getClass());
  }

  /**
   * Formats the pattern and element using Java 21 String Templates for improved readability and performance.
   * Creates a padded output with the pattern aligned to the left and the element to the right.
   * 
   * @param pattern the URL pattern to format
   * @param element the binding element to format
   * @return a formatted string with proper padding between pattern and element
   */
  private static String format(String pattern, Object element) {
    // If pattern is longer than PADDING, we'll just add one space before the element
    if (pattern.length() >= PADDING) {
      return STR."\{pattern} \{element}";
    }
    
    // Calculate the number of spaces needed for padding
    int spacesToAdd = PADDING - pattern.length();
    String spaces = " ".repeat(spacesToAdd);
    
    // Use String Template for formatting with proper padding
    return STR."\{pattern}\{spaces}\{element}";
  }
}