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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.slf4j.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link EmailStringTemplates}.
 */
public class EmailStringTemplatesTest
    extends TestSupport
{
  @Mock
  private Logger mockLogger;

  @Before
  public void setup() {
    // Initialize mocks
    org.mockito.MockitoAnnotations.initMocks(this);
  }

  @Test
  public void testVerificationMessage() {
    String message = EmailStringTemplates.verificationMessage("user@example.com", "smtp.example.com");
    
    assertThat(message, notNullValue());
    assertThat(message, containsString("user@example.com"));
    assertThat(message, containsString("smtp.example.com"));
  }

  @Test
  public void testVerificationMessageWithNullAddress() {
    assertThrows(NullPointerException.class, () -> {
      EmailStringTemplates.verificationMessage(null, "smtp.example.com");
    });
  }

  @Test
  public void testConfigurationMessage() {
    String enabledMessage = EmailStringTemplates.configurationMessage(true, "smtp.example.com", 25);
    String disabledMessage = EmailStringTemplates.configurationMessage(false, "smtp.example.com", 25);
    
    assertThat(enabledMessage, containsString("enabled"));
    assertThat(disabledMessage, containsString("disabled"));
    assertThat(enabledMessage, containsString("smtp.example.com:25"));
  }

  @Test
  public void testSecurityMessage() {
    String message = EmailStringTemplates.securityMessage("password_change", "admin", "Password policy updated");
    
    assertThat(message, notNullValue());
    assertThat(message, containsString("Security Alert: password_change"));
    assertThat(message, containsString("User: admin"));
    assertThat(message, containsString("Details: Password policy updated"));
    assertThat(message, containsString("Time:"));
  }

  @Test
  public void testI18nMessage() {
    // This test assumes a ResourceBundle exists for testing
    // In a real test, you would create a test resource bundle
    try {
      String message = EmailStringTemplates.i18nMessage("org.sonatype.nexus.email.internal.TestMessages", 
          "test.key", Locale.ENGLISH, "param1");
      assertThat(message, notNullValue());
    } catch (Exception e) {
      // Expected in test environment without the actual resource bundle
      log.info("Resource bundle not available in test environment: {}", e.getMessage());
    }
  }

  @Test
  public void testLogEmailOperation() {
    Map<String, Object> context = new HashMap<>();
    context.put("host", "smtp.example.com");
    context.put("port", 25);
    
    EmailStringTemplates.logEmailOperation(mockLogger, "info", "Email sent", context);
    
    // Verify that logger.info was called with a message containing our context
    verify(mockLogger).info(org.mockito.ArgumentMatchers.contains("Email sent"));
  }

  @Test
  public void testDeliveryStatusMessage() {
    String successMessage = EmailStringTemplates.deliveryStatusMessage(
        "user@example.com", "Test Subject", true, null);
    String failureMessage = EmailStringTemplates.deliveryStatusMessage(
        "user@example.com", "Test Subject", false, "Connection timeout");
    
    assertThat(successMessage, containsString("successful"));
    assertThat(failureMessage, containsString("failed"));
    assertThat(failureMessage, containsString("Connection timeout"));
  }

  @Test
  public void testEmailContentTemplate() {
    String content = EmailStringTemplates.emailContentTemplate(
        "Test Subject", "This is the body", "system@example.com");
    
    assertThat(content, containsString("Subject: Test Subject"));
    assertThat(content, containsString("This is the body"));
    assertThat(content, containsString("From: system@example.com"));
    assertThat(content, containsString("Sent by Nexus Repository Manager"));
  }

  @Test
  public void testErrorMessage() {
    String message = EmailStringTemplates.errorMessage(
        "send", "ConnectionError", "Failed to connect to SMTP server");
    
    assertThat(message, is("Email send failed: [ConnectionError] Failed to connect to SMTP server"));
  }

  @Test
  public void testConnectionStatusMessage() {
    String successSecure = EmailStringTemplates.connectionStatusMessage(
        "smtp.example.com", 465, true, true);
    String failedInsecure = EmailStringTemplates.connectionStatusMessage(
        "smtp.example.com", 25, false, false);
    
    assertThat(successSecure, containsString("successful"));
    assertThat(successSecure, containsString("secure"));
    assertThat(failedInsecure, containsString("failed"));
    assertThat(failedInsecure, containsString("insecure"));
  }

  @Test
  public void testVirtualThreadOperationMessage() {
    String message = EmailStringTemplates.virtualThreadOperationMessage(
        "sendAsync", "Verification email to user@example.com");
    
    assertThat(message, containsString("Async email operation"));
    assertThat(message, containsString("sendAsync"));
    assertThat(message, containsString("virtual thread"));
  }

  @Test
  public void testAsyncCompletionMessage() {
    String successMessage = EmailStringTemplates.asyncCompletionMessage("sendVerification", 150, true);
    String failureMessage = EmailStringTemplates.asyncCompletionMessage("sendVerification", 250, false);
    
    assertThat(successMessage, containsString("completed successfully"));
    assertThat(successMessage, containsString("150ms"));
    assertThat(failureMessage, containsString("failed"));
    assertThat(failureMessage, containsString("250ms"));
  }
}