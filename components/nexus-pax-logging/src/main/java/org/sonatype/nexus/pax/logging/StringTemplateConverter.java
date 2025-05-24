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

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.lang.reflect.Method;

/**
 * Converter that processes Java 21 String Templates in log messages.
 * This enables structured logging with type-safe string interpolation.
 * On Java versions prior to 21, it passes through the original message.
 *
 * @since 3.60.0
 */
public class StringTemplateConverter
    extends ClassicConverter
{
  private static final boolean STRING_TEMPLATE_AVAILABLE;
  private static Method stringTemplateProcessMethod;
  private static Object stringTemplateProcessor;
  
  static {
    boolean available = false;
    try {
      // Check if StringTemplate class exists (Java 21+)
      Class<?> stringTemplateClass = Class.forName("java.lang.StringTemplate");
      Class<?> processorClass = Class.forName("java.lang.StringTemplate$Processor");
      
      // Get the STR processor
      Class<?> strClass = Class.forName("java.lang.StringTemplate$STR");
      stringTemplateProcessor = strClass.getField("STR").get(null);
      
      // Get the process method
      stringTemplateProcessMethod = processorClass.getMethod("process", stringTemplateClass);
      
      available = true;
    }
    catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
      // String templates not available in this Java version
    }
    STRING_TEMPLATE_AVAILABLE = available;
  }
  
  /**
   * Processes a potential string template object.
   *
   * @param obj the object to process, which might be a string template
   * @return the processed string, or the original object's toString if not a template
   */
  private static String processStringTemplate(Object obj) {
    if (!STRING_TEMPLATE_AVAILABLE || obj == null) {
      return obj != null ? obj.toString() : "null";
    }
    
    try {
      // Check if the object is a StringTemplate
      Class<?> stringTemplateClass = Class.forName("java.lang.StringTemplate");
      if (stringTemplateClass.isInstance(obj)) {
        // Process the template using the STR processor
        Object result = stringTemplateProcessMethod.invoke(stringTemplateProcessor, obj);
        return result != null ? result.toString() : "null";
      }
    }
    catch (Exception e) {
      // Fall back to toString if any error occurs
    }
    
    return obj.toString();
  }
  
  @Override
  public String convert(ILoggingEvent event) {
    Object[] argumentArray = event.getArgumentArray();
    if (argumentArray == null || argumentArray.length == 0) {
      return event.getFormattedMessage();
    }
    
    // Check if any of the arguments might be a string template
    for (Object arg : argumentArray) {
      if (arg != null && arg.getClass().getName().startsWith("java.lang.StringTemplate")) {
        return processStringTemplate(arg);
      }
    }
    
    // If no string templates found, return the formatted message
    return event.getFormattedMessage();
  }
}