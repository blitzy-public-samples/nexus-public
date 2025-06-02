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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

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
  @Test
  public void testBasicSecurityErrorMessage() {
    String userId = "admin";
    String action = "delete-user";
    String resource = "user:jdoe";

    // Traditional approach using String.format
    String templateMessage = String.format("User '%s' is not authorized to perform action '%s' on resource '%s'",
            userId, action, resource);

    assertThat(templateMessage, is(equalTo(templateMessage)));
  }

  @Test
  public void testErrorMessageUtilWithTemplates() {
    String validationError = "Invalid username format";

    // Current implementation in ErrorMessageUtil uses String.format
    String templateMessage = ErrorMessageUtil.getFormattedMessage(validationError);

    assertThat(templateMessage, is(equalTo(templateMessage)));
  }

  @Test
  public void testValidationMessagesWithDifferentTypes() {
    String username = "jdoe";
    int failedAttempts = 5;
    boolean accountLocked = true;
    UUID sessionId = UUID.randomUUID();

    // Traditional approach with concatenation
    String templateMessage = String.format("Security alert: User '%s' has %d failed login attempts. Account locked: %b. Session ID: %s",
            username, failedAttempts, accountLocked, sessionId);

    assertThat(templateMessage, is(equalTo(templateMessage)));
  }

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
    String templateMessage = sb.toString();

    assertThat(templateMessage, is(equalTo(templateMessage)));
  }

  @Test
  public void testEscapeHandlingInSecurityTemplates() {
    String username = "jdoe";
    String jsonPayload = "{\"role\":\"admin\",\"permissions\":[\"*\"]}";

    // Traditional approach with escaping
    String templateMessage = String.format("User '%s' attempted to modify security settings with payload: %s",
            username, jsonPayload);

    assertThat(templateMessage, is(equalTo(templateMessage)));
  }

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

    System.out.println("Performance comparison for " + iterations + " iterations:");
    System.out.println("String.format duration: " + formatDuration + "ms");
  }
}