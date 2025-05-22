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

import java.lang.StringTemplate;
import java.lang.StringTemplate.Processor;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A processor for Java 21 String Templates (JEP 430) that transforms template expressions
 * into structured log fields while maintaining human-readable formatting.
 * <p>
 * This processor extracts template fragments and expressions, produces a formatted message
 * string for traditional log displays, and builds a structured data map for consumption by
 * JSON log processors.
 * <p>
 * Example usage:
 * <pre>
 * // Define the processor
 * var LOG_STRUCT = StringTemplateLogProcessor.INSTANCE;
 * 
 * // Use in logging statements
 * logger.info(LOG_STRUCT."User \{user.id} performed action \{action} on resource \{resource.id}");
 * 
 * // Produces both a human-readable message and structured data with fields:
 * // - user.id
 * // - action
 * // - resource.id
 * </pre>
 * 
 * @since 3.60
 */
public final class StringTemplateLogProcessor implements Processor<LogTemplateResult, RuntimeException> {

  /**
   * Singleton instance of the processor.
   */
  public static final StringTemplateLogProcessor INSTANCE = new StringTemplateLogProcessor();

  private StringTemplateLogProcessor() {
    // Singleton
  }

  /**
   * Processes a StringTemplate into a structured log result containing both a formatted message
   * and a map of extracted fields.
   *
   * @param template the string template to process
   * @return a LogTemplateResult containing the formatted message and structured data
   */
  @Override
  public LogTemplateResult process(StringTemplate template) {
    StringBuilder message = new StringBuilder();
    Map<String, Object> structuredData = new LinkedHashMap<>();
    
    List<String> fragments = template.fragments();
    List<Object> values = template.values();
    
    // Process the template fragments and values
    Iterator<String> fragmentIterator = fragments.iterator();
    int valueIndex = 0;
    
    // Always start with the first fragment
    message.append(escapeFragment(fragmentIterator.next()));
    
    // Process each value and its corresponding fragment
    for (Object value : values) {
      // Extract field name from the template fragment
      String fieldName = extractFieldName(template, valueIndex);
      
      // Add the value to the structured data
      addToStructuredData(structuredData, fieldName, value);
      
      // Append the value to the message
      message.append(formatValue(value));
      
      // Append the next fragment if available
      if (fragmentIterator.hasNext()) {
        message.append(escapeFragment(fragmentIterator.next()));
      }
      
      valueIndex++;
    }
    
    return new LogTemplateResult(message.toString(), structuredData);
  }

  /**
   * Extracts the field name from a template expression.
   * <p>
   * This attempts to determine the field name by analyzing the template expression.
   * If the expression is a simple variable reference, that name is used.
   * For more complex expressions, a best-effort approach is used to extract a meaningful name.
   *
   * @param template the string template
   * @param valueIndex the index of the value in the template
   * @return the extracted field name
   */
  private String extractFieldName(StringTemplate template, int valueIndex) {
    // Get the fragments surrounding the value
    List<String> fragments = template.fragments();
    if (valueIndex >= fragments.size() - 1) {
      return "field" + valueIndex; // Fallback if we can't determine the name
    }
    
    // Look for the expression in the previous fragment
    String prevFragment = fragments.get(valueIndex);
    int exprStart = prevFragment.lastIndexOf("\\{");
    if (exprStart >= 0) {
      // Extract the expression name from the fragment
      String expr = prevFragment.substring(exprStart + 2).trim();
      
      // Handle common expression patterns
      if (expr.contains(".")) {
        // For expressions like "user.name", use the full path
        return expr;
      } else if (expr.contains("[")) {
        // For array/map access like "users[0]", use the variable name
        return expr.substring(0, expr.indexOf('['));
      } else {
        // For simple variables, use the variable name
        return expr;
      }
    }
    
    // Fallback to a generic field name
    return "field" + valueIndex;
  }

  /**
   * Adds a value to the structured data map, handling nested structures.
   *
   * @param data the structured data map
   * @param fieldName the field name
   * @param value the value to add
   */
  @SuppressWarnings("unchecked")
  private void addToStructuredData(Map<String, Object> data, String fieldName, Object value) {
    if (fieldName.contains(".")) {
      // Handle nested fields (e.g., "user.name")
      String[] parts = fieldName.split("\\.", 2);
      String rootField = parts[0];
      String nestedField = parts[1];
      
      // Create or get the nested map
      Map<String, Object> nestedMap = (Map<String, Object>) data.computeIfAbsent(
          rootField, k -> new LinkedHashMap<String, Object>());
      
      // Recursively add to the nested map
      addToStructuredData(nestedMap, nestedField, value);
    } else {
      // Simple field
      data.put(fieldName, value);
    }
  }

  /**
   * Formats a value for inclusion in the log message.
   *
   * @param value the value to format
   * @return the formatted string representation
   */
  private String formatValue(Object value) {
    if (value == null) {
      return "null";
    } else if (value instanceof String) {
      return (String) value;
    } else if (value instanceof LogTemplateResult) {
      // Handle nested template results
      return ((LogTemplateResult) value).getMessage();
    } else {
      return String.valueOf(value);
    }
  }

  /**
   * Escapes special characters in template fragments for safe logging.
   *
   * @param fragment the template fragment
   * @return the escaped fragment
   */
  private String escapeFragment(String fragment) {
    // Replace any remaining template expression markers
    return fragment.replace("\\{", "{");
  }

  /**
   * Result class that holds both the formatted message and structured data.
   */
  public static final class LogTemplateResult {
    private final String message;
    private final Map<String, Object> data;

    /**
     * Creates a new log template result.
     *
     * @param message the formatted message
     * @param data the structured data
     */
    public LogTemplateResult(String message, Map<String, Object> data) {
      this.message = message;
      this.data = new HashMap<>(data);
    }

    /**
     * Gets the formatted message.
     *
     * @return the message
     */
    public String getMessage() {
      return message;
    }

    /**
     * Gets the structured data.
     *
     * @return the data
     */
    public Map<String, Object> getData() {
      return data;
    }

    @Override
    public String toString() {
      return message;
    }
  }
}