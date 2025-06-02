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
package java21;

import static java.lang.StringTemplate.STR;
import static java.lang.StringTemplate.RAW;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sonatype.nexus.security.ErrorMessageUtil;

/**
 * Tests demonstrating the use of Java 21 String Templates in security-related messaging contexts.
 * 
 * String Templates provide a more readable, maintainable, and potentially more performant way to
 * generate messages in security contexts such as error messages, validation responses, audit logs,
 * and debug output.
 */
public class StringTemplateSecurityMessageTest
{
  /**
   * Demonstrates basic usage of String Templates for security error messages.
   * 
   * Benefits:
   * - More readable code compared to String concatenation or String.format
   * - Less error-prone as variable names are directly embedded in the template
   * - Improved maintainability as message structure is clearer
   */
  @Test
  public void testBasicSecurityErrorMessage() {
    String userId = "admin";
    String action = "delete-user";
    String resource = "user:jdoe";
    
    // Traditional approach using String.format
    String traditionalMessage = String.format("User '%s' is not authorized to perform action '%s' on resource '%s'", 
        userId, action, resource);
    
    // New approach using String Templates
    String templateMessage = STR."User '\{userId}' is not authorized to perform action '\{action}' on resource '\{resource}'";
    
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  /**
   * Demonstrates using String Templates with the existing ErrorMessageUtil class.
   * 
   * This shows how String Templates can be integrated with existing code that uses
   * String.format for message generation.
   */
  @Test
  public void testErrorMessageUtilWithTemplates() {
    String validationError = "Invalid username format";
    
    // Current implementation in ErrorMessageUtil uses String.format
    String traditionalMessage = ErrorMessageUtil.getFormattedMessage(validationError);
    
    // Using String Templates directly
    String templateMessage = STR."ValidationErrorXO{id='*', message='\{validationError}'}";
    
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  /**
   * Demonstrates using String Templates for security validation messages with different parameter types.
   * 
   * Benefits:
   * - Type safety as expressions are evaluated at compile time
   * - Automatic conversion of different types to strings
   * - Cleaner code for complex messages with multiple parameters
   */
  @Test
  public void testValidationMessagesWithDifferentTypes() {
    String username = "jdoe";
    int failedAttempts = 5;
    boolean accountLocked = true;
    UUID sessionId = UUID.randomUUID();
    
    // Traditional approach with concatenation
    String traditionalMessage = "Security alert: User '" + username + "' has " + failedAttempts + 
        " failed login attempts. Account locked: " + accountLocked + ". Session ID: " + sessionId;
    
    // Using String Templates
    String templateMessage = STR."Security alert: User '\{username}' has \{failedAttempts} " + 
        "failed login attempts. Account locked: \{accountLocked}. Session ID: \{sessionId}";
    
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  /**
   * Demonstrates using String Templates for audit logging messages.
   * 
   * Benefits:
   * - Improved readability of audit log messages
   * - Easier maintenance of log message formats
   * - Better alignment with security compliance requirements for clear audit trails
   */
  @Test
  public void testAuditLoggingWithTemplates() {
    String userId = "admin";
    String action = "update-role";
    String roleId = "nx-admin";
    String ipAddress = "192.168.1.100";
    Instant timestamp = Instant.now();
    
    // Traditional approach with StringBuilder
    StringBuilder sb = new StringBuilder();
    sb.append("AUDIT: User '").append(userId)
      .append("' performed action '").append(action)
      .append("' on role '").append(roleId)
      .append("' from IP '").append(ipAddress)
      .append("' at '").append(timestamp).append("'");
    String traditionalMessage = sb.toString();
    
    // Using String Templates
    String templateMessage = STR."AUDIT: User '\{userId}' performed action '\{action}' on role '\{roleId}' " + 
        "from IP '\{ipAddress}' at '\{timestamp}'";
    
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  /**
   * Tests proper handling of escapes in security message templates.
   * 
   * Benefits:
   * - Demonstrates how String Templates handle special characters
   * - Shows how to escape characters in security contexts
   * - Ensures proper message formatting with complex content
   */
  @Test
  public void testEscapeHandlingInSecurityTemplates() {
    String username = "jdoe";
    String jsonPayload = "{\"role\":\"admin\",\"permissions\":[\"*\"]}";
    
    // Traditional approach with escaping
    String traditionalMessage = String.format("User '%s' attempted to modify security settings with payload: %s", 
        username, jsonPayload);
    
    // Using String Templates
    String templateMessage = STR."User '\{username}' attempted to modify security settings with payload: \{jsonPayload}";
    
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  /**
   * Compares performance of String Templates vs traditional formatting approaches.
   * 
   * Benefits:
   * - Demonstrates potential performance improvements with String Templates
   * - Shows how String Templates can be more efficient for complex messages
   * - Provides metrics for decision-making on adoption
   */
  @Test
  public void testPerformanceComparison() {
    String userId = "admin";
    String action = "modify-permissions";
    String resource = "repository:maven-central";
    String ipAddress = "192.168.1.100";
    UUID requestId = UUID.randomUUID();
    
    int iterations = 100_000;
    
    // Measure traditional String.format performance
    Instant formatStart = Instant.now();
    for (int i = 0; i < iterations; i++) {
      String message = String.format("Security event: User '%s' performed '%s' on '%s' from '%s' (request: %s)", 
          userId, action, resource, ipAddress, requestId);
    }
    Instant formatEnd = Instant.now();
    long formatDuration = Duration.between(formatStart, formatEnd).toMillis();
    
    // Measure String Template performance
    Instant templateStart = Instant.now();
    for (int i = 0; i < iterations; i++) {
      String message = STR."Security event: User '\{userId}' performed '\{action}' on '\{resource}' " + 
          "from '\{ipAddress}' (request: \{requestId})";
    }
    Instant templateEnd = Instant.now();
    long templateDuration = Duration.between(templateStart, templateEnd).toMillis();
    
    System.out.println("Performance comparison for " + iterations + " iterations:");
    System.out.println("String.format duration: " + formatDuration + "ms");
    System.out.println("String Template duration: " + templateDuration + "ms");
    System.out.println("Improvement: " + (formatDuration - templateDuration) + "ms " + 
        "(" + (100 - (templateDuration * 100 / formatDuration)) + "%)");
    
    // String Templates should generally be faster than String.format
    // This assertion might need adjustment based on specific environment
    assertThat("String Templates should be faster than String.format", 
        templateDuration, lessThan(formatDuration));
  }
  
  /**
   * Demonstrates using the RAW template processor for deferred processing.
   * 
   * Benefits:
   * - Shows how to use RAW for cases where template processing needs to be deferred
   * - Demonstrates advanced usage patterns for security logging
   * - Provides a pattern for custom security message processing
   */
  @Test
  public void testRawTemplateProcessorForSecurityMessages() {
    String userId = "admin";
    String action = "create-user";
    String targetUser = "jsmith";
    
    // Create a raw template that can be processed later
    var rawTemplate = RAW."SECURITY_EVENT: User '\{userId}' performed '\{action}' to create user '\{targetUser}'";
    
    // Process the template with STR processor
    String processedMessage = STR.process(rawTemplate);
    
    // Traditional equivalent
    String expectedMessage = String.format("SECURITY_EVENT: User '%s' performed '%s' to create user '%s'", 
        userId, action, targetUser);
    
    assertThat(processedMessage, is(equalTo(expectedMessage)));
    
    // Demonstrate access to template fragments and values
    assertThat(rawTemplate.fragments().size(), is(4)); // 3 expressions + 1 = 4 fragments
    assertThat(rawTemplate.values().size(), is(3));    // 3 expressions = 3 values
    assertThat(rawTemplate.values().get(0), is(userId));
    assertThat(rawTemplate.values().get(1), is(action));
    assertThat(rawTemplate.values().get(2), is(targetUser));
  }
}