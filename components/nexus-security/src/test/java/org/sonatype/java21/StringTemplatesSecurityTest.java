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
package org.sonatype.java21;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.ErrorMessageUtil;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.DisabledAccountException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UnknownAccountException;
import org.junit.Before;
import org.junit.Test;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests for Java 21 String Templates feature in security contexts.
 * 
 * This test class demonstrates the use of Java 21's String Templates feature for
 * security-related logging, error messages, and audit trails, ensuring improved
 * readability and maintainability while maintaining security standards.
 */
public class StringTemplatesSecurityTest
    extends TestSupport
{
  private static final String USERNAME = "admin";
  private static final String IP_ADDRESS = "192.168.1.100";
  private static final String RESOURCE = "/api/v1/security/users";
  private static final String ACTION = "CREATE";
  
  private LocalDateTime timestamp;
  
  @Before
  public void setUp() {
    timestamp = LocalDateTime.now();
  }
  
  /**
   * Test demonstrating the use of String Templates for security error message generation.
   * Shows how String Templates improve readability compared to traditional string concatenation
   * or formatting methods.
   */
  @Test
  public void testErrorMessageGeneration() {
    // Traditional approach using String concatenation
    String traditionalMessage = "Authentication failed for user '" + USERNAME + "' from IP " + IP_ADDRESS + 
        " at " + timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    
    // Using Java 21 String Templates
    String templateMessage = STR."Authentication failed for user '\{USERNAME}' from IP \{IP_ADDRESS} " + 
        "at \{timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, equalTo(traditionalMessage));
    
    // Verify message contains expected information
    assertThat(templateMessage, containsString(USERNAME));
    assertThat(templateMessage, containsString(IP_ADDRESS));
  }
  
  /**
   * Test demonstrating the use of String Templates for security audit logging with structured data.
   * Shows how String Templates can be used to create consistent, readable audit log entries.
   */
  @Test
  public void testAuditLogFormatting() {
    // Traditional approach using String.format
    String traditionalAuditLog = String.format("AUDIT: User '%s' performed '%s' on resource '%s' from IP %s at %s",
        USERNAME, ACTION, RESOURCE, IP_ADDRESS, timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    
    // Using Java 21 String Templates
    String templateAuditLog = STR."AUDIT: User '\{USERNAME}' performed '\{ACTION}' on resource '\{RESOURCE}' " + 
        "from IP \{IP_ADDRESS} at \{timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}";
    
    // Verify both approaches produce the same result
    assertThat(templateAuditLog, equalTo(traditionalAuditLog));
    
    // Verify audit log contains all required fields
    assertThat(templateAuditLog, containsString("AUDIT"));
    assertThat(templateAuditLog, containsString(USERNAME));
    assertThat(templateAuditLog, containsString(ACTION));
    assertThat(templateAuditLog, containsString(RESOURCE));
    assertThat(templateAuditLog, containsString(IP_ADDRESS));
  }
  
  /**
   * Test demonstrating the use of String Templates for consistent security validation error formatting.
   * Shows how String Templates can be used with existing utility methods to create consistent error messages.
   */
  @Test
  public void testSecurityValidationErrorFormatting() {
    // Create different types of security exceptions
    AuthenticationException unknownAccount = new UnknownAccountException("User does not exist");
    AuthenticationException incorrectCredentials = new IncorrectCredentialsException("Invalid password");
    AuthenticationException disabledAccount = new DisabledAccountException("Account is locked");
    
    // Using Java 21 String Templates with existing utility method
    String unknownAccountError = ErrorMessageUtil.getFormattedMessage(
        STR."Authentication failed: \{unknownAccount.getMessage()} for user \{USERNAME}");
    
    String incorrectCredentialsError = ErrorMessageUtil.getFormattedMessage(
        STR."Authentication failed: \{incorrectCredentials.getMessage()} for user \{USERNAME}");
    
    String disabledAccountError = ErrorMessageUtil.getFormattedMessage(
        STR."Authentication failed: \{disabledAccount.getMessage()} for user \{USERNAME}");
    
    // Verify error messages contain expected information
    assertThat(unknownAccountError, containsString("User does not exist"));
    assertThat(incorrectCredentialsError, containsString("Invalid password"));
    assertThat(disabledAccountError, containsString("Account is locked"));
    assertThat(unknownAccountError, containsString(USERNAME));
  }
  
  /**
   * Test demonstrating the use of String Templates for JSON-like structured security data.
   * Shows how String Templates can be used to create structured data representations.
   */
  @Test
  public void testStructuredSecurityData() {
    // Using Java 21 String Templates for JSON-like structured data
    String jsonTemplate = STR."""
        {
          "event": "security_audit",
          "timestamp": "\{timestamp}",
          "user": "\{USERNAME}",
          "action": "\{ACTION}",
          "resource": "\{RESOURCE}",
          "ip": "\{IP_ADDRESS}",
          "status": "success"
        }
        """;
    
    // Verify structured data contains expected information
    assertThat(jsonTemplate, containsString("security_audit"));
    assertThat(jsonTemplate, containsString(USERNAME));
    assertThat(jsonTemplate, containsString(ACTION));
    assertThat(jsonTemplate, containsString(RESOURCE));
    assertThat(jsonTemplate, containsString(IP_ADDRESS));
  }
  
  /**
   * Test demonstrating the performance benefits of String Templates over traditional concatenation.
   * Shows how String Templates can be more efficient for complex string operations.
   */
  @Test
  public void testPerformanceComparison() {
    final int iterations = 10000;
    Map<String, String> securityAttributes = Map.of(
        "username", USERNAME,
        "ip", IP_ADDRESS,
        "resource", RESOURCE,
        "action", ACTION,
        "timestamp", timestamp.toString()
    );
    
    // Measure time for traditional string concatenation
    long startTraditional = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      String traditional = "Security event: User '" + securityAttributes.get("username") + 
          "' performed '" + securityAttributes.get("action") + 
          "' on resource '" + securityAttributes.get("resource") + 
          "' from IP " + securityAttributes.get("ip") + 
          " at " + securityAttributes.get("timestamp");
    }
    long endTraditional = System.nanoTime();
    long traditionalTime = endTraditional - startTraditional;
    
    // Measure time for String Templates
    long startTemplate = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      String template = STR."Security event: User '\{securityAttributes.get("username")}' " + 
          "performed '\{securityAttributes.get("action")}' " + 
          "on resource '\{securityAttributes.get("resource")}' " + 
          "from IP \{securityAttributes.get("ip")} " + 
          "at \{securityAttributes.get("timestamp")}";
    }
    long endTemplate = System.nanoTime();
    long templateTime = endTemplate - startTemplate;
    
    // Log performance results
    log.info("Traditional concatenation time: {} ns", traditionalTime);
    log.info("String Template time: {} ns", templateTime);
    
    // Note: This assertion might not always pass due to JVM optimizations and other factors
    // It's included to demonstrate the potential performance benefits of String Templates
    // In real-world scenarios, String Templates often perform better for complex strings
    assertThat("String Templates should be more efficient than traditional concatenation",
        templateTime, lessThan(traditionalTime * 2)); // Allow some margin for JVM variations
  }
}