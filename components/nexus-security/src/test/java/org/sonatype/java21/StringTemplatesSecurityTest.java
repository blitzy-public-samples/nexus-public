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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.ErrorMessageUtil;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.Map;
import java.util.HashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 String Templates in security contexts.
 * 
 * This test suite validates the use of Java 21's String Templates feature for security logging,
 * error messages, and audit trails, ensuring improved readability and maintainability of
 * security-related messages while maintaining performance and security standards.
 */
public class StringTemplatesSecurityTest
    extends TestSupport
{
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password123";
    private static final String IP_ADDRESS = "192.168.1.100";
    private static final String RESOURCE = "/api/v1/security/users";
    
    @Mock
    private Subject subject;
    
    @Before
    public void setUp() {
        subject = mock(Subject.class);
    }
    
    /**
     * Tests the use of String Templates for security error message generation.
     * Demonstrates how String Templates improve readability and maintainability
     * compared to traditional String.format() or concatenation approaches.
     */
    @Test
    public void testSecurityErrorMessageGeneration() {
        // Traditional approach using String.format
        String traditionalMessage = String.format(
            "Authentication failed for user '%s' from IP '%s' when accessing resource '%s'",
            USERNAME, IP_ADDRESS, RESOURCE);
            
        // New approach using String Templates
        String templateMessage = STR.
            "Authentication failed for user '\{USERNAME}' from IP '\{IP_ADDRESS}' when accessing resource '\{RESOURCE}'";
        
        // Verify both approaches produce the same result
        assertThat(templateMessage, is(traditionalMessage));
        
        // Verify message contains expected information
        assertThat(templateMessage, containsString(USERNAME));
        assertThat(templateMessage, containsString(IP_ADDRESS));
        assertThat(templateMessage, containsString(RESOURCE));
    }
    
    /**
     * Tests String Templates for security audit logging with structured data.
     * Demonstrates how String Templates can be used to format audit log entries
     * with complex structured data in a more readable way.
     */
    @Test
    public void testAuditLogFormatting() {
        // Create a map of audit data
        Map<String, Object> auditData = new HashMap<>();
        auditData.put("user", USERNAME);
        auditData.put("action", "LOGIN");
        auditData.put("timestamp", System.currentTimeMillis());
        auditData.put("ip", IP_ADDRESS);
        auditData.put("success", true);
        
        // Traditional approach using StringBuilder
        StringBuilder traditionalLog = new StringBuilder();
        traditionalLog.append("AUDIT: {");
        traditionalLog.append("\"user\":\"" + auditData.get("user") + "\", ");
        traditionalLog.append("\"action\":\"" + auditData.get("action") + "\", ");
        traditionalLog.append("\"timestamp\":" + auditData.get("timestamp") + ", ");
        traditionalLog.append("\"ip\":\"" + auditData.get("ip") + "\", ");
        traditionalLog.append("\"success\":" + auditData.get("success"));
        traditionalLog.append("}");
        
        // New approach using String Templates
        String templateLog = STR.
            "AUDIT: {\"user\":\"\{auditData.get("user")}\", " +
            "\"action\":\"\{auditData.get("action")}\", " +
            "\"timestamp\":\{auditData.get("timestamp")}, " +
            "\"ip\":\"\{auditData.get("ip")}\", " +
            "\"success\":\{auditData.get("success")}}";
        
        // Verify both approaches produce the same result
        assertThat(templateLog, is(traditionalLog.toString()));
        
        // Verify log contains expected information
        assertThat(templateLog, startsWith("AUDIT: {"));
        assertThat(templateLog, containsString("\"user\":\"" + USERNAME + "\""));
        assertThat(templateLog, containsString("\"ip\":\"" + IP_ADDRESS + "\""));
    }
    
    /**
     * Tests String Templates for consistent security validation error formatting.
     * Demonstrates how String Templates can improve the formatting of validation
     * error messages in security contexts.
     */
    @Test
    public void testValidationErrorFormatting() {
        // Traditional approach using ErrorMessageUtil with String.format
        String traditionalError = ErrorMessageUtil.getFormattedMessage(
            "Invalid credentials for user '" + USERNAME + "'");
        
        // New approach using String Templates
        String errorMessage = STR."Invalid credentials for user '\{USERNAME}'";
        String templateError = STR."ValidationErrorXO{id='*', message='\{errorMessage}'}";
        
        // Verify both approaches produce the same result
        assertThat(templateError, is(traditionalError));
        
        // Verify error contains expected information
        assertThat(templateError, containsString("ValidationErrorXO"));
        assertThat(templateError, containsString(USERNAME));
    }
    
    /**
     * Tests the performance benefits of String Templates over traditional concatenation.
     * Demonstrates how String Templates can provide better performance for complex
     * string formatting in security-sensitive code paths.
     */
    @Test
    public void testPerformanceComparison() {
        final int iterations = 10000;
        
        // Setup test data
        UsernamePasswordToken token = new UsernamePasswordToken(USERNAME, PASSWORD.toCharArray());
        token.setHost(IP_ADDRESS);
        AuthenticationException authException = new AuthenticationException("Invalid credentials");
        
        // Measure traditional concatenation approach
        long traditionalStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            String message = "Authentication failed for user '" + token.getUsername() + "' from IP '" + 
                token.getHost() + "' with error: " + authException.getMessage();
            assertThat(message.length() > 0, is(true)); // Prevent optimization
        }
        long traditionalEnd = System.nanoTime();
        long traditionalDuration = traditionalEnd - traditionalStart;
        
        // Measure String Templates approach
        long templateStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            String message = STR."Authentication failed for user '\{token.getUsername()}' from IP '\{token.getHost()}' with error: \{authException.getMessage()}";
            assertThat(message.length() > 0, is(true)); // Prevent optimization
        }
        long templateEnd = System.nanoTime();
        long templateDuration = templateEnd - templateStart;
        
        // Log performance results
        System.out.println("Performance comparison for " + iterations + " iterations:");
        System.out.println("Traditional concatenation: " + traditionalDuration / 1_000_000.0 + " ms");
        System.out.println("String Templates: " + templateDuration / 1_000_000.0 + " ms");
        System.out.println("Ratio (traditional/templates): " + 
            String.format("%.2f", (double) traditionalDuration / templateDuration));
        
        // Note: We don't assert on performance as it can vary between environments
        // The test is primarily to demonstrate and log the performance characteristics
    }
    
    /**
     * Tests String Templates for security-sensitive data masking.
     * Demonstrates how String Templates can be used to consistently mask
     * sensitive data in security logs and error messages.
     */
    @Test
    public void testSecurityDataMasking() {
        // Create sensitive data
        String apiKey = "sk_live_abcdefghijklmnopqrstuvwxyz123456";
        String maskedApiKey = maskSensitiveData(apiKey);
        
        // Traditional approach using String.format
        String traditionalMessage = String.format(
            "API key '%s' was used for authentication from IP '%s'",
            maskedApiKey, IP_ADDRESS);
            
        // New approach using String Templates
        String templateMessage = STR.
            "API key '\{maskSensitiveData(apiKey)}' was used for authentication from IP '\{IP_ADDRESS}'";
        
        // Verify both approaches produce the same result
        assertThat(templateMessage, is(traditionalMessage));
        
        // Verify sensitive data is properly masked
        assertThat(templateMessage, containsString("sk_live_****"));
        assertThat(templateMessage, containsString(IP_ADDRESS));
    }
    
    /**
     * Helper method to mask sensitive data like API keys or tokens.
     * Only shows the first 8 characters followed by asterisks.
     */
    private String maskSensitiveData(String data) {
        if (data == null || data.length() <= 8) {
            return data;
        }
        return data.substring(0, 8) + "****";
    }
}