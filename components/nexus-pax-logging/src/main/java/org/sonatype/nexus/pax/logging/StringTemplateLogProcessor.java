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

import java.util.HashMap;
import java.util.Map;

/**
 * Processor for Java 21 String Templates in log messages.
 * This class provides utilities for structured logging with string templates.
 * It extracts template expressions into structured data that can be used for
 * machine-readable logging formats like JSON.
 *
 * @since 3.60.0
 */
public class StringTemplateLogProcessor
{
  private static final boolean STRING_TEMPLATE_AVAILABLE;
  
  static {
    boolean available = false;
    try {
      // Check if StringTemplate class exists (Java 21+)
      Class.forName("java.lang.StringTemplate");
      available = true;
    }
    catch (ClassNotFoundException e) {
      // String templates not available in this Java version
    }
    STRING_TEMPLATE_AVAILABLE = available;
  }
  
  /**
   * Checks if String Templates are available in the current Java runtime.
   *
   * @return true if String Templates are available, false otherwise
   */
  public static boolean isStringTemplateAvailable() {
    return STRING_TEMPLATE_AVAILABLE;
  }
  
  /**
   * Extracts structured data from a log message that might contain string template expressions.
   * This is useful for creating structured log entries with key-value pairs.
   *
   * @param message the log message that might contain string template expressions
   * @param args the arguments passed to the log message
   * @return a map of extracted key-value pairs, or an empty map if no structured data is found
   */
  public static Map<String, Object> extractStructuredData(String message, Object... args) {
    Map<String, Object> structuredData = new HashMap<>();
    
    if (!STRING_TEMPLATE_AVAILABLE || args == null || args.length == 0) {
      return structuredData;
    }
    
    try {
      // Check if any of the arguments might be a string template
      for (Object arg : args) {
        if (arg != null && arg.getClass().getName().startsWith("java.lang.StringTemplate")) {
          // Use reflection to extract template fragments and values
          Class<?> stringTemplateClass = Class.forName("java.lang.StringTemplate");
          if (stringTemplateClass.isInstance(arg)) {
            // Get the fragments and values methods
            java.lang.reflect.Method fragmentsMethod = stringTemplateClass.getMethod("fragments");
            java.lang.reflect.Method valuesMethod = stringTemplateClass.getMethod("values");
            
            // Extract fragments and values
            Object fragments = fragmentsMethod.invoke(arg);
            Object values = valuesMethod.invoke(arg);
            
            if (fragments instanceof String[] && values instanceof Object[]) {
              String[] fragmentsArray = (String[]) fragments;
              Object[] valuesArray = (Object[]) values;
              
              // Extract variable names from fragments and pair with values
              for (int i = 0; i < valuesArray.length; i++) {
                if (i < fragmentsArray.length - 1) {
                  String fragment = fragmentsArray[i];
                  Object value = valuesArray[i];
                  
                  // Try to extract variable name from the fragment
                  String varName = extractVariableName(fragment);
                  if (varName != null && !varName.isEmpty()) {
                    structuredData.put(varName, value);
                  }
                  else {
                    // Use a generic name if we can't extract a variable name
                    structuredData.put("param" + i, value);
                  }
                }
              }
            }
          }
        }
      }
    }
    catch (Exception e) {
      // Ignore any errors in structured data extraction
    }
    
    return structuredData;
  }
  
  /**
   * Attempts to extract a variable name from a string template fragment.
   * This is a heuristic approach and may not work for all cases.
   *
   * @param fragment the string template fragment
   * @return the extracted variable name, or null if none could be found
   */
  private static String extractVariableName(String fragment) {
    if (fragment == null || fragment.isEmpty()) {
      return null;
    }
    
    // Look for common patterns in string template usage
    // For example: "User {username} logged in" - extract "username"
    int lastSpace = fragment.lastIndexOf(' ');
    if (lastSpace >= 0 && lastSpace < fragment.length() - 1) {
      return fragment.substring(lastSpace + 1);
    }
    
    // If no space found, try to use the whole fragment as the name
    return fragment;
  }
}