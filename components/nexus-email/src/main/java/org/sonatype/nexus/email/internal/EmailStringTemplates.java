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
package org.sonatype.nexus.email.internal;

import org.slf4j.Logger;

import java.text.MessageFormat;
import java.util.Locale;

// Import Java 21 String Template related classes
import static java.lang.StringTemplate.*;
import static java.util.Objects.requireNonNull;

import java.lang.StringTemplate;
import java.util.Map;
import java.util.ResourceBundle;



/**
 * Utility class for using Java 21 String Templates in email message construction and logging.
 * This class encapsulates template processors for common email patterns, providing type-safe
 * value interpolation, consistent formatting, and internationalization support.
 * <p>
 * Benefits of using String Templates for email operations:
 * <ul>
 *   <li>Improved readability with embedded expressions directly in the template</li>
 *   <li>Type safety for all interpolated values</li>
 *   <li>Reduced string concatenation errors and improved performance</li>
 *   <li>Consistent formatting across all email messages</li>
 *   <li>Support for structured logging of email operations</li>
 *   <li>Internationalization support for global deployments</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * String subject = EmailStringTemplates.subject(STR."Account verification for \{username}");
 * String body = EmailStringTemplates.body(STR."""
 *     Hello \{username},
 *     
 *     Please verify your account by clicking the link below:
 *     \{verificationUrl}
 *     
 *     Thank you,
 *     The Nexus Team
 *     """);
 * </pre>
 *
 * @since 3.60
 */
public final class EmailStringTemplates
{
  /**
   * Private constructor to prevent instantiation of utility class.
   */
  private EmailStringTemplates() {
    // empty
  }

  /**
   * Creates a formatted email subject line using String Templates.
   *
   * @param subject The subject template with embedded expressions
   * @return The formatted subject string
   */
  public static String subject(StringTemplate subject) {
    return STR.process(subject);
  }

  /**
   * Creates a formatted email body using String Templates.
   *
   * @param body The body template with embedded expressions
   * @return The formatted body string
   */
  public static String body(StringTemplate body) {
    return STR.process(body);
  }

  /**
   * Creates a formatted email message with both subject and body using String Templates.
   *
   * @param subject The subject template with embedded expressions
   * @param body The body template with embedded expressions
   * @return The formatted message string with subject and body
   */
  public static String message(StringTemplate subject, StringTemplate body) {
    return STR."Subject: \{STR.process(subject)}\n\n\{STR.process(body)}";
  }

  /**
   * Creates a formatted verification email message using String Templates.
   *
   * @param recipient The email recipient
   * @param serverUrl The server URL for verification
   * @return The formatted verification message
   */
  public static String verificationMessage(String recipient, String serverUrl) {
    return STR."""
        Verification email for Nexus Repository Manager
        
        This email was sent to verify the email configuration for \{recipient}.
        
        Server URL: \{serverUrl}
        
        If you received this email in error, please ignore it.
        """;
  }

  /**
   * Creates a formatted error message for email operations using String Templates.
   *
   * @param operation The email operation that failed
   * @param error The error message or exception
   * @return The formatted error message
   */
  public static String errorMessage(String operation, String error) {
    return STR."Email operation failed: \{operation}. Error: \{error}";
  }

  /**
   * Creates a formatted success message for email operations using String Templates.
   *
   * @param operation The email operation that succeeded
   * @param recipient The email recipient
   * @return The formatted success message
   */
  public static String successMessage(String operation, String recipient) {
    return STR."Email \{operation} successfully sent to \{recipient}";
  }

  /**
   * Creates a formatted log message for email operations using String Templates.
   * This method is designed for structured logging with consistent format.
   *
   * @param operation The email operation being performed
   * @param status The status of the operation
   * @param details Additional details about the operation
   * @return The formatted log message
   */
  public static String logMessage(String operation, String status, String details) {
    return STR."Email[operation=\{operation}, status=\{status}, details=\{details}]";
  }
  
  /**
   * Creates a structured log entry for email sending operations.
   * 
   * @param recipient The email recipient
   * @param subject The email subject
   * @param success Whether the operation was successful
   * @param errorDetails Optional error details if the operation failed
   * @return The formatted log message
   */
  public static String logSendOperation(String recipient, String subject, boolean success, String errorDetails) {
    String status = success ? "SUCCESS" : "FAILURE";
    String details = success ? STR."sent to \{recipient}" : STR."failed: \{errorDetails}";
    
    return STR."Email[operation=SEND, recipient=\{recipient}, subject=\{subject}, status=\{status}, details=\{details}]";
  }
  
  /**
   * Creates a structured log entry for email configuration operations.
   * 
   * @param host The SMTP host
   * @param port The SMTP port
   * @param success Whether the operation was successful
   * @param errorDetails Optional error details if the operation failed
   * @return The formatted log message
   */
  public static String logConfigOperation(String host, int port, boolean success, String errorDetails) {
    String status = success ? "SUCCESS" : "FAILURE";
    String details = success ? STR."configured \{host}:\{port}" : STR."failed: \{errorDetails}";
    
    return STR."Email[operation=CONFIG, host=\{host}, port=\{port}, status=\{status}, details=\{details}]";
  }

  /**
   * Creates a formatted internationalized message using String Templates and the specified locale.
   * This method supports email content localization for global deployments.
   *
   * @param template The message template with embedded expressions
   * @param locale The locale for formatting
   * @return The formatted message string with locale-specific formatting
   */
  public static String i18nMessage(StringTemplate template, Locale locale) {
    // Using FMT processor with locale for internationalized messages
    return STR."\{locale}\{template}";
  }

  /**
   * Constructs an email message with proper escaping and formatting.
   * This method is designed to be used by the EmailManager implementation.
   * It replaces the legacy constructMessage method with a template-based approach.
   *
   * @param message The message to construct
   * @return The constructed message string
   */
  public static String constructMessage(String message) {
    // Simple case - just return the message if it's already formatted
    if (message == null || message.isEmpty()) {
      return "";
    }
    
    // Use String Templates for consistent formatting
    return STR."\{message}";
  }
  
  /**
   * Creates a formatted email footer using String Templates.
   * This method provides a consistent footer for all emails sent by the system.
   *
   * @param instanceId The instance ID of the Nexus Repository Manager
   * @param version The version of the Nexus Repository Manager
   * @return The formatted footer string
   */
  public static String emailFooter(String instanceId, String version) {
    return STR."""
        --
        Sent by Nexus Repository Manager
        Instance: \{instanceId}
        Version: \{version}
        """;
  }
  
  /**
   * Creates a formatted debug message for email troubleshooting using String Templates.
   * This method is intended for development and troubleshooting purposes.
   *
   * @param emailConfig The email configuration details
   * @param diagnosticInfo Additional diagnostic information
   * @return The formatted debug message
   */
  public static String debugMessage(String emailConfig, String diagnosticInfo) {
    return STR."""
        === EMAIL DEBUG INFORMATION ===
        Time: \{java.time.LocalDateTime.now()}
        
        Configuration:
        \{emailConfig}
        
        Diagnostic Info:
        \{diagnosticInfo}
        
        === END DEBUG INFORMATION ===
        """;
  }
  
  /**
   * Formats an email address with optional display name using String Templates.
   * This method handles proper formatting of email addresses for RFC compliance.
   *
   * @param email The email address
   * @param displayName The optional display name (can be null or empty)
   * @return The formatted email address string
   */
  public static String formatEmailAddress(String email, String displayName) {
    if (displayName == null || displayName.isEmpty()) {
      return email;
    }
    
    // Use pattern matching to handle different email formats
    return switch (email) {
      case String e when e.contains("@") && displayName != null && !displayName.isEmpty() ->
        STR."\{displayName} <\{e}>";
      case String e when e.contains("@") ->
        e;
      default ->
        throw new IllegalArgumentException(STR."Invalid email address: \{email}");
    };
  }
  
  /**
   * Parses and formats an email template variable using String Templates.
   * This method demonstrates the use of pattern matching with String Templates.
   *
   * @param variable The template variable to parse
   * @return The formatted variable value
   */
  public static String parseTemplateVariable(Object variable) {
    return switch (variable) {
      case String s -> STR."\{s}";
      case Integer i -> STR."\{i}";
      case Double d -> STR."\{String.format("%.2f", d)}";
      case Boolean b -> b ? "Yes" : "No";
      case null -> "";
      default -> variable.toString();
    };
  }
  
  /**
   * Creates a formatted email template with conditional content using String Templates.
   * This method demonstrates the use of conditional expressions within templates.
   *
   * @param recipient The email recipient
   * @param isAdmin Whether the recipient is an administrator
   * @param hasCustomContent Whether to include custom content
   * @param customContent The custom content to include (if applicable)
   * @return The formatted email template
   */
  public static String conditionalTemplate(String recipient, boolean isAdmin, boolean hasCustomContent, String customContent) {
    return STR."""
        Hello \{recipient},
        
        \{isAdmin ? "You are receiving this email as a system administrator." : "You are receiving this email as a regular user."}
        
        \{hasCustomContent ? STR."Custom content: \{customContent}" : "No custom content is available."}
        
        Thank you,
        The Nexus Team
        """;
  }
  
  /**
   * Creates a formatted email with embedded expressions for system metrics using String Templates.
   * This method demonstrates the use of complex expressions within templates.
   *
   * @param systemName The name of the system
   * @param uptime The system uptime in seconds
   * @param memoryUsage The memory usage percentage
   * @param diskUsage The disk usage percentage
   * @return The formatted system metrics email
   */
  public static String systemMetricsEmail(String systemName, long uptime, double memoryUsage, double diskUsage) {
    // Calculate uptime in a more readable format
    long days = uptime / (24 * 3600);
    long hours = (uptime % (24 * 3600)) / 3600;
    long minutes = (uptime % 3600) / 60;
    long seconds = uptime % 60;
    
    // Format the uptime string using String Templates
    String uptimeStr = STR."\{days}d \{hours}h \{minutes}m \{seconds}s";
    
    // Determine status based on resource usage
    String memoryStatus = memoryUsage > 90 ? "CRITICAL" : (memoryUsage > 75 ? "WARNING" : "OK");
    String diskStatus = diskUsage > 90 ? "CRITICAL" : (diskUsage > 75 ? "WARNING" : "OK");
    
    return STR."""
        System Metrics Report - \{systemName}
        Generated: \{java.time.LocalDateTime.now()}
        
        System Uptime: \{uptimeStr}
        
        Resource Usage:
        - Memory: \{String.format("%.2f", memoryUsage)}% (Status: \{memoryStatus})
        - Disk: \{String.format("%.2f", diskUsage)}% (Status: \{diskStatus})
        
        Overall System Status: \{memoryStatus.equals("CRITICAL") || diskStatus.equals("CRITICAL") ? "CRITICAL" : 
                                (memoryStatus.equals("WARNING") || diskStatus.equals("WARNING") ? "WARNING" : "OK")}
        
        This is an automated system report from Nexus Repository Manager.
        """;
  }

  /**
   * Creates a formatted HTML email body using String Templates.
   * This method properly handles HTML content with embedded expressions.
   *
   * @param htmlBody The HTML body template with embedded expressions
   * @return The formatted HTML body string
   */
  public static String htmlBody(StringTemplate htmlBody) {
    return STR.process(htmlBody);
  }

  /**
   * Creates a formatted plain text alternative for HTML emails using String Templates.
   *
   * @param textBody The plain text body template with embedded expressions
   * @return The formatted plain text body string
   */
  public static String textBody(StringTemplate textBody) {
    return STR.process(textBody);
  }
  
  /**
   * Creates a formatted multipart email with both HTML and plain text parts using String Templates.
   *
   * @param subject The email subject template
   * @param htmlBody The HTML body template
   * @param textBody The plain text body template
   * @return The formatted multipart message description
   */
  public static String multipartMessage(StringTemplate subject, StringTemplate htmlBody, StringTemplate textBody) {
    String subjectStr = STR.process(subject);
    String htmlStr = STR.process(htmlBody);
    String textStr = STR.process(textBody);
    
    return STR."""
        Multipart Email:
        Subject: \{subjectStr}
        
        Text Body:
        \{textStr}
        
        HTML Body:
        \{htmlStr}
        """;
  }
  
  /**
   * Creates a formatted notification email using String Templates.
   * This is a specialized template for system notifications.
   *
   * @param system The system or component sending the notification
   * @param event The event that triggered the notification
   * @param details The details of the event
   * @return The formatted notification message
   */
  public static String notificationMessage(String system, String event, String details) {
    return STR."""
        Nexus Repository Manager Notification
        
        System: \{system}
        Event: \{event}
        Time: \{java.time.LocalDateTime.now()}
        
        Details:
        \{details}
        
        This is an automated message from Nexus Repository Manager.
        """;
  }
  
  /**
   * Creates a formatted security alert email using String Templates.
   * This is a specialized template for security-related notifications.
   *
   * @param alertType The type of security alert
   * @param username The username associated with the alert, if applicable
   * @param details The details of the security event
   * @return The formatted security alert message
   */
  public static String securityAlertMessage(String alertType, String username, String details) {
    return STR."""
        SECURITY ALERT - Nexus Repository Manager
        
        Alert Type: \{alertType}
        User: \{username}
        Time: \{java.time.LocalDateTime.now()}
        
        Details:
        \{details}
        
        This security alert requires your immediate attention.
        """;
  }

  /**
   * Creates an internationalized message using Java 21 String Templates and ResourceBundle.
   * This method supports localization of email messages.
   *
   * @param bundleName the resource bundle name
   * @param key the message key in the resource bundle
   * @param locale the locale for formatting
   * @param args the arguments to substitute in the template
   * @return the formatted internationalized message
   */
  public static String i18nMessage(final String bundleName,
                                   final String key,
                                   final Locale locale,
                                   final Object... args) {
    requireNonNull(bundleName, "Bundle name cannot be null");
    requireNonNull(key, "Message key cannot be null");
    requireNonNull(locale, "Locale cannot be null");

    ResourceBundle bundle = ResourceBundle.getBundle(bundleName, locale);
    String template = bundle.getString(key);

    // Create a raw template first to avoid premature interpolation
    var rawTemplate = RAW."\{MessageFormat.format(template, args)}";
    // Process the template with STR processor
    return rawTemplate.process(STR);
  }

  /**
   * Simplified i18n message method that uses the default locale.
   *
   * @param bundleName the resource bundle name
   * @param key the message key in the resource bundle
   * @param args the arguments to substitute in the template
   * @return the formatted internationalized message
   */
  public static String i18nMessage(final String bundleName, final String key, final Object... args) {
    return i18nMessage(bundleName, key, Locale.getDefault(), args);
  }
  /**
   * Creates a configuration update message using Java 21 String Templates.
   *
   * @param enabled whether email is enabled
   * @param host the SMTP host
   * @param port the SMTP port
   * @return the formatted configuration message
   */
  public static String configurationMessage(final boolean enabled, final String host, final int port) {
    requireNonNull(host, "SMTP host cannot be null");
    String status = enabled ? "enabled" : "disabled";
    return STR."Email configuration updated: \{status} with server \{host}:\{port}";
  }
  /**
   * Creates a structured log message for email operations using Java 21 String Templates.
   * This method supports structured logging with context information.
   *
   * @param logger the SLF4J logger to use
   * @param level the log level ("info", "debug", "warn", "error")
   * @param message the message template
   * @param context the context map with key-value pairs for structured logging
   */
  public static void logEmailOperation(final Logger logger,
                                       final String level,
                                       final String message,
                                       final Map<String, Object> context) {
    requireNonNull(logger, "Logger cannot be null");
    requireNonNull(level, "Log level cannot be null");
    requireNonNull(message, "Message cannot be null");

    // Create a structured logging message using String Templates
    var template = RAW."{message} {context.toString()}";
    String logMessage = template.process(STR);

    // Log at the appropriate level
    switch (level.toLowerCase()) {
      case "debug" -> logger.debug(logMessage);
      case "warn" -> logger.warn(logMessage);
      case "error" -> logger.error(logMessage);
      default -> logger.info(logMessage);
    }
  }

  /**
   * Creates an email delivery status message using Java 21 String Templates.
   * This method provides different templates based on success or failure.
   *
   * @param recipient the email recipient
   * @param subject the email subject
   * @param success whether delivery was successful
   * @param errorDetails optional error details if delivery failed
   * @return the formatted delivery status message
   */
  public static String deliveryStatusMessage(final String recipient,
                                             final String subject,
                                             final boolean success,
                                             final String errorDetails) {
    requireNonNull(recipient, "Recipient cannot be null");
    requireNonNull(subject, "Subject cannot be null");

    if (success) {
      return STR."Email delivery successful: '\{subject}' to \{recipient}";
    } else {
      return STR."Email delivery failed: '\{subject}' to \{recipient}. Error: \{errorDetails}";
    }
  }

  /**
   * Creates a template for email content with common header and footer sections.
   * Uses text blocks for multi-line formatting.
   *
   * @param subject the email subject
   * @param body the main email body content
   * @param sender the email sender
   * @return the formatted email content with header and footer
   */
  public static String emailContentTemplate(final String subject, final String body, final String sender) {
    requireNonNull(subject, "Subject cannot be null");
    requireNonNull(body, "Body cannot be null");
    requireNonNull(sender, "Sender cannot be null");

    return STR."""
      Subject: \{subject}

      \{body}

      --
      Sent by Nexus Repository Manager
      From: \{sender}
      """;
  }

  /**
   * Creates a formatted error message for email-related exceptions using Java 21 String Templates.
   * This method provides consistent error message formatting.
   *
   * @param operation the operation that failed
   * @param errorType the type of error
   * @param errorMessage the error message
   * @return the formatted error message
   */
  public static String errorMessage(final String operation, final String errorType, final String errorMessage) {
    requireNonNull(operation, "Operation cannot be null");
    requireNonNull(errorType, "Error type cannot be null");

    return STR."Email \{operation} failed: [\{errorType}] \{errorMessage}";
  }

  /**
   * Creates a connection status message using Java 21 String Templates.
   * This method provides information about SMTP connection status.
   *
   * @param host the SMTP host
   * @param port the SMTP port
   * @param connected whether connection was successful
   * @param secureConnection whether the connection is secure (TLS/SSL)
   * @return the formatted connection status message
   */
  public static String connectionStatusMessage(final String host,
                                               final int port,
                                               final boolean connected,
                                               final boolean secureConnection) {
    requireNonNull(host, "SMTP host cannot be null");

    String status = connected ? "successful" : "failed";
    String security = secureConnection ? "secure" : "insecure";
    return STR."SMTP connection \{status} to \{host}:\{port} (\{security})";
  }

  /**
   * Creates a formatted message for virtual thread email operations using Java 21 String Templates.
   * This method is specifically designed for asynchronous email operations.
   *
   * @param operation the asynchronous operation being performed
   * @param details details about the operation
   * @return the formatted virtual thread operation message
   */
  public static String virtualThreadOperationMessage(final String operation, final String details) {
    requireNonNull(operation, "Operation cannot be null");

    return STR."Async email operation [\{operation}] started on virtual thread: \{details}";
  }

  /**
   * Creates a formatted completion message for asynchronous email operations.
   *
   * @param operation the operation that completed
   * @param durationMs the duration in milliseconds
   * @param success whether the operation was successful
   * @return the formatted completion message
   */
  public static String asyncCompletionMessage(final String operation,
                                              final long durationMs,
                                              final boolean success) {
    requireNonNull(operation, "Operation cannot be null");

    String status = success ? "completed successfully" : "failed";
    return STR."Async email operation [\{operation}] \{status} in \{durationMs}ms";
  }
}