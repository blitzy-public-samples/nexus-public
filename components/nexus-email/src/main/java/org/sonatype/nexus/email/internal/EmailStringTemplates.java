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

import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import org.sonatype.nexus.common.log.LoggingMessage;

import org.slf4j.Logger;

import static java.lang.StringTemplate.RAW;
import static java.util.Objects.requireNonNull;

/**
 * Utility class for using Java 21 String Templates in email message construction and logging.
 * <p>
 * This class encapsulates template processors for common email patterns, providing type-safe
 * value interpolation, consistent formatting, and internationalization support. It improves
 * readability, reduces string concatenation errors, and enables structured logging for email
 * operations.
 * <p>
 * Benefits over traditional string concatenation or formatting:
 * <ul>
 *   <li>Improved readability with embedded expressions</li>
 *   <li>Reduced string concatenation errors</li>
 *   <li>Type-safe template processing</li>
 *   <li>Structured logging capabilities</li>
 *   <li>Internationalization support</li>
 * </ul>
 * <p>
 * Usage example with EmailManager implementation:
 * <pre>
 * {@code
 * // In EmailManagerImpl
 * public void sendVerification(EmailConfiguration config, String address) {
 *   // Send verification email
 *   // ...
 *   
 *   // Log the operation using templates
 *   String message = EmailStringTemplates.verificationMessage(address, config.getHost());
 *   log.info(message);
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
public final class EmailStringTemplates
{
  /**
   * Date-time formatter for email messages.
   */
  private static final DateTimeFormatter DATE_TIME_FORMATTER = 
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

  /**
   * Private constructor to prevent instantiation of utility class.
   */
  private EmailStringTemplates() {
    // empty
  }

  /**
   * Creates a verification email message using Java 21 String Templates.
   *
   * @param address the email address being verified
   * @param host the SMTP host being used
   * @return the formatted verification message
   */
  public static String verificationMessage(final String address, final String host) {
    requireNonNull(address, "Email address cannot be null");
    requireNonNull(host, "SMTP host cannot be null");
    return STR."Verification email for \{address} sent successfully using SMTP server \{host}.";
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
   * Creates a security-related email message using Java 21 String Templates.
   * Uses text blocks for multi-line formatting.
   *
   * @param action the security action performed
   * @param user the user who performed the action
   * @param details additional details about the action
   * @return the formatted security message
   */
  public static String securityMessage(final String action, final String user, final String details) {
    requireNonNull(action, "Security action cannot be null");
    requireNonNull(user, "User cannot be null");
    LocalDateTime now = LocalDateTime.now();
    String formattedTime = DATE_TIME_FORMATTER.format(now);
    
    return STR."""
      Security Alert: \{action}
      User: \{user}
      Details: \{details}
      Time: \{formattedTime}
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
    var template = RAW."\{message} \{LoggingMessage.asString(context)}";
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