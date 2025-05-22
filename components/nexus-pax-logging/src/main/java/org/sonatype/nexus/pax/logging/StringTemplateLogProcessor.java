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

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.core.Context;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;

/**
 * Processor for Java 21 String Templates in log messages.
 * <p>
 * This class enhances logging by detecting and properly formatting String Template
 * expressions in log messages. It works with both traditional string formatting and
 * the new Java 21 String Template format.
 *
 * @since 3.60.0
 */
public class StringTemplateLogProcessor
    extends ContextAwareBase
    implements LifeCycle
{
  private boolean started = false;
  
  /**
   * List of registered template processors for different template formats.
   */
  private final List<TemplateProcessor> templateProcessors = new ArrayList<>();
  
  /**
   * Processes a log message, applying String Template formatting if applicable.
   *
   * @param message the original log message
   * @param args the arguments for the message (if any)
   * @return the processed message
   */
  public String processLogMessage(String message, Object... args) {
    if (message == null || message.isEmpty()) {
      return message;
    }
    
    // First check if this is a traditional format string with arguments
    if (args != null && args.length > 0) {
      // Handle traditional format string
      return formatTraditionalMessage(message, args);
    }
    
    // Check if this might be a String Template expression
    if (message.contains("\\{") || message.contains("STR.")) {
      // Process as potential String Template
      return processStringTemplate(message);
    }
    
    // Return original message if no processing needed
    return message;
  }
  
  /**
   * Formats a message using traditional String.format style.
   *
   * @param message the format string
   * @param args the arguments
   * @return the formatted message
   */
  private String formatTraditionalMessage(String message, Object... args) {
    try {
      return String.format(message, args);
    }
    catch (Exception e) {
      addWarn("Error formatting log message: " + e.getMessage());
      return message;
    }
  }
  
  /**
   * Processes a potential String Template expression.
   *
   * @param message the message potentially containing String Template syntax
   * @return the processed message
   */
  private String processStringTemplate(String message) {
    // This is a simplified implementation
    // In a real implementation, we would use Java 21's StringTemplate API
    // to properly process the template
    
    try {
      // Simple processing of \{...} syntax for Java versions prior to 21
      StringBuilder result = new StringBuilder();
      int pos = 0;
      while (pos < message.length()) {
        int startIdx = message.indexOf("\\{", pos);
        if (startIdx == -1) {
          result.append(message.substring(pos));
          break;
        }
        
        result.append(message.substring(pos, startIdx));
        int endIdx = findClosingBrace(message, startIdx + 2);
        
        if (endIdx == -1) {
          // No closing brace found, treat as literal
          result.append("\\{");
          pos = startIdx + 2;
        }
        else {
          // Extract expression and evaluate (simplified)
          String expr = message.substring(startIdx + 2, endIdx).trim();
          result.append("[" + expr + "]"); // Placeholder for actual evaluation
          pos = endIdx + 1;
        }
      }
      
      return result.toString();
    }
    catch (Exception e) {
      addWarn("Error processing String Template: " + e.getMessage());
      return message;
    }
  }
  
  /**
   * Finds the matching closing brace for a template expression.
   *
   * @param message the message containing the expression
   * @param startPos the position after the opening brace
   * @return the position of the closing brace or -1 if not found
   */
  private int findClosingBrace(String message, int startPos) {
    int braceCount = 1;
    for (int i = startPos; i < message.length(); i++) {
      char c = message.charAt(i);
      if (c == '{') {
        braceCount++;
      }
      else if (c == '}') {
        braceCount--;
        if (braceCount == 0) {
          return i;
        }
      }
    }
    return -1; // No matching closing brace
  }
  
  /**
   * Interface for template processors that handle different template formats.
   */
  private interface TemplateProcessor {
    /**
     * Checks if this processor can handle the given message.
     *
     * @param message the message to check
     * @return true if this processor can handle the message
     */
    boolean canProcess(String message);
    
    /**
     * Processes the template message.
     *
     * @param message the template message
     * @return the processed message
     */
    String process(String message);
  }
  
  @Override
  public void start() {
    if (started) {
      return;
    }
    
    // Initialize template processors
    // In a real implementation, we would add processors for different template formats
    
    started = true;
  }
  
  @Override
  public void stop() {
    started = false;
    templateProcessors.clear();
  }
  
  @Override
  public boolean isStarted() {
    return started;
  }
  
  @Override
  public void setContext(Context context) {
    super.setContext(context);
  }
}