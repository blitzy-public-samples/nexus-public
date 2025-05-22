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
package ch.qos.logback.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sonatype.nexus.common.template.EscapeHelper;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * A Logback pattern converter that properly formats Java 21 string templates in log messages.
 * This converter transforms string template expressions into structured log fields,
 * enabling enhanced machine readability while maintaining human-readable formats.
 * <p>
 * String templates in Java 21 (JEP 430) use the syntax: STR."text \{expression} more text"
 * This converter detects these patterns and formats them appropriately for logging.
 * <p>
 * Configuration options:
 * <ul>
 *   <li>format - The output format. Supported values: "json", "compact", "expanded" (default: "compact")</li>
 *   <li>prefix - A prefix to add to each template key in the structured output (default: none)</li>
 * </ul>
 * <p>
 * Example configuration in logback.xml:
 * <pre>
 * &lt;conversionRule conversionWord="tmpl" converterClass="ch.qos.logback.internal.StringTemplateConverter" /&gt;
 * &lt;appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"&gt;
 *   &lt;encoder&gt;
 *     &lt;pattern&gt;%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %tmpl{json}%n&lt;/pattern&gt;
 *   &lt;/encoder&gt;
 * &lt;/appender&gt;
 * </pre>
 *
 * @since 3.60
 */
public class StringTemplateConverter extends ClassicConverter
{
  // Pattern to match string template expressions: \{...} or {expression}
  // The first group matches escaped expressions (\{...}), the second group matches regular expressions ({...})
  private static final Pattern TEMPLATE_PATTERN = 
      Pattern.compile("\\\\\\{([^}]*)\\}|\\{([^}]*)\\}", Pattern.DOTALL);

  // Pattern to detect if a string looks like a Java 21 string template (STR."...")
  private static final Pattern STRING_TEMPLATE_PATTERN = 
      Pattern.compile("(^|\\s)STR\\.(\"|\"\"\").*?(\"|\"\"\")\\s*", Pattern.DOTALL);

  // Format options
  private static final String FORMAT_JSON = "json";
  private static final String FORMAT_COMPACT = "compact";
  private static final String FORMAT_EXPANDED = "expanded";

  // Default format if none specified
  private static final String DEFAULT_FORMAT = FORMAT_COMPACT;

  // Configuration options
  private String format;
  private String prefix;

  // Helper for escaping values
  private final EscapeHelper escapeHelper = new EscapeHelper();

  @Override
  public void start() {
    // Parse options
    List<String> options = getOptionList();
    
    if (options != null && !options.isEmpty()) {
      format = options.get(0);
      
      if (options.size() > 1) {
        prefix = options.get(1);
      }
    }
    
    // Set defaults if not specified
    if (format == null || format.isEmpty()) {
      format = DEFAULT_FORMAT;
    }
    
    if (prefix == null) {
      prefix = "";
    }
    
    super.start();
  }

  @Override
  public String convert(final ILoggingEvent event) {
    String message = event.getFormattedMessage();
    if (message == null || message.isEmpty()) {
      return message;
    }

    // Check if the message contains any template expressions or looks like a string template
    if (!containsTemplateExpression(message) && !looksLikeStringTemplate(message)) {
      return message;
    }

    return processTemplateExpressions(message, event);
  }

  /**
   * Checks if the message contains any template expressions.
   *
   * @param message the log message to check
   * @return true if the message contains template expressions, false otherwise
   */
  private boolean containsTemplateExpression(final String message) {
    return TEMPLATE_PATTERN.matcher(message).find();
  }

  /**
   * Checks if the message looks like a Java 21 string template (contains STR. followed by quotes).
   *
   * @param message the log message to check
   * @return true if the message looks like a string template, false otherwise
   */
  private boolean looksLikeStringTemplate(final String message) {
    return STRING_TEMPLATE_PATTERN.matcher(message).find();
  }

  /**
   * Processes template expressions in the message and formats them according to the specified format option.
   *
   * @param message the log message containing template expressions
   * @param event the logging event (for accessing MDC and other context data)
   * @return the formatted message with processed template expressions
   */
  private String processTemplateExpressions(final String message, final ILoggingEvent event) {
    Matcher matcher = TEMPLATE_PATTERN.matcher(message);
    StringBuffer result = new StringBuffer();
    Map<String, String> templateValues = new LinkedHashMap<>();
    List<String> expressions = new ArrayList<>();

    // Extract all template expressions and their values
    while (matcher.find()) {
      String expression = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
      expression = expression.trim();
      
      // Skip empty expressions
      if (expression.isEmpty()) {
        matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
        continue;
      }
      
      expressions.add(expression);

      // Generate a key for the structured output
      String key = generateKey(expression, expressions.size());
      
      // Store the expression and its value for structured logging
      // In a real scenario, the expression is already evaluated by the time it reaches the log message
      templateValues.put(key, expression);

      // Replace the template expression with a formatted value based on the selected format
      String replacement = formatTemplateExpression(expression, key, templateValues);
      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);

    // Append structured data based on the selected format
    if (FORMAT_JSON.equalsIgnoreCase(format) && !templateValues.isEmpty()) {
      result.append(" ").append(formatAsJson(templateValues));
    } else if (FORMAT_EXPANDED.equalsIgnoreCase(format) && !templateValues.isEmpty()) {
      result.append(formatAsExpanded(templateValues));
    }

    return result.toString();
  }

  /**
   * Generates a key for the structured output based on the expression.
   *
   * @param expression the template expression
   * @param index the index of the expression in the message
   * @return a key for the structured output
   */
  private String generateKey(final String expression, final int index) {
    // Try to extract a meaningful key from the expression
    // For simple variable references, use the variable name
    // For complex expressions, use a generic name with an index
    String key;
    
    if (expression.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
      // Simple variable reference
      key = expression;
    } else if (expression.contains(".") && expression.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")) {
      // Object property reference (e.g., user.name)
      key = expression.replace('.', '_');
    } else {
      // Complex expression
      key = "expr" + index;
    }
    
    // Add prefix if specified
    return prefix + key;
  }

  /**
   * Formats a template expression according to the current format settings.
   *
   * @param expression the template expression to format
   * @param key the key for the structured output
   * @param templateValues map of template keys to their values
   * @return the formatted expression
   */
  private String formatTemplateExpression(final String expression, final String key, final Map<String, String> templateValues) {
    String value = templateValues.get(key);
    
    if (FORMAT_COMPACT.equalsIgnoreCase(format)) {
      // In compact format, just return the value
      return value;
    } else if (FORMAT_EXPANDED.equalsIgnoreCase(format)) {
      // In expanded format, return the value with the key in a comment-like format
      return value + "[" + key + "]";
    } else {
      // Default format (including JSON) just returns the value
      return value;
    }
  }

  /**
   * Formats the template values as a JSON object for structured logging.
   *
   * @param templateValues map of template keys to their values
   * @return a JSON representation of the template values
   */
  private String formatAsJson(final Map<String, String> templateValues) {
    if (templateValues.isEmpty()) {
      return "{}";
    }

    StringBuilder json = new StringBuilder("{")
        .append(templateValues.entrySet().stream()
            .map(entry -> "\"" + escapeJson(entry.getKey()) + "\": \"" + escapeJson(entry.getValue()) + "\"")
            .reduce((a, b) -> a + ", " + b)
            .orElse(""))
        .append("}");

    return json.toString();
  }

  /**
   * Formats the template values in an expanded format for structured logging.
   *
   * @param templateValues map of template keys to their values
   * @return an expanded representation of the template values
   */
  private String formatAsExpanded(final Map<String, String> templateValues) {
    if (templateValues.isEmpty()) {
      return "";
    }

    StringBuilder expanded = new StringBuilder("\n");
    templateValues.forEach((key, value) -> {
      expanded.append("  ").append(key).append("=\"")
          .append(escapeHelper.html(value)).append("\"\n");
    });

    return expanded.toString();
  }

  /**
   * Escapes special characters in a string for JSON encoding.
   *
   * @param str the string to escape
   * @return the escaped string
   */
  private String escapeJson(final String str) {
    if (str == null) {
      return "";
    }

    return str.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("/", "\\/");
  }
}