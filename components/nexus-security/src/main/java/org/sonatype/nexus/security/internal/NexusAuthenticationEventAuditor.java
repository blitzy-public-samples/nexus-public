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
package org.sonatype.nexus.security.internal;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.security.ClientInfo;
import org.sonatype.nexus.security.authc.AuthenticationFailureReason;
import org.sonatype.nexus.security.authc.NexusAuthenticationEvent;

import com.google.common.collect.Sets;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * Writes to the audit log for fired {@link NexusAuthenticationEvent}
 * using Java 21 features for improved performance and readability.
 *
 * @since 3.22
 */
@Named
@Singleton
public class NexusAuthenticationEventAuditor
    extends AuditorSupport
    implements EventAware
{
  private static final String DOMAIN = "security.user";

  /* for now only log a subset of failure reasons */
  private static final Set<AuthenticationFailureReason> AUDITABLE_FAILURE_REASONS = new HashSet<>();

  static {
    AUDITABLE_FAILURE_REASONS.add(AuthenticationFailureReason.INCORRECT_CREDENTIALS);
  }

  /**
   * Handles authentication events and logs them to the audit system.
   * Optimized for Java 21's execution model with improved concurrency handling.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final NexusAuthenticationEvent event) {
    // Use Virtual Thread for non-blocking processing of audit events
    Thread.startVirtualThread(() -> processAuthenticationEvent(event));
  }

  /**
   * Process the authentication event and record it to the audit log if needed.
   * Uses Java 21 String Templates and Pattern Matching for improved readability.
   */
  private void processAuthenticationEvent(NexusAuthenticationEvent event) {
    Set<AuthenticationFailureReason> failureReasonsToLog = getFailureReasonsToLog(event);

    if (isRecording() && !failureReasonsToLog.isEmpty()) {
      AuditData auditData = createAuditData(event, failureReasonsToLog);
      record(auditData);
    }
  }

  /**
   * Creates structured audit data using Java 21 features for improved data construction.
   */
  private AuditData createAuditData(NexusAuthenticationEvent event, Set<AuthenticationFailureReason> failureReasons) {
    AuditData auditData = new AuditData();

    // Use String Templates for more readable string construction
    auditData.setType(STR."authentication");
    auditData.setDomain(DOMAIN);
    auditData.setTimestamp(event.getEventDate());

    Map<String, Object> attributes = auditData.getAttributes();
    attributes.put("failureReasons", failureReasons);
    attributes.put("wasSuccessful", event.isSuccessful());

    // Use pattern matching for more concise client info handling
    if (event.getClientInfo() instanceof ClientInfo clientInfo) {
      // Using String Templates for attribute keys for consistency
      attributes.put(STR."userId", clientInfo.getUserid());
      attributes.put(STR."remoteIp", clientInfo.getRemoteIP());
      attributes.put(STR."userAgent", clientInfo.getUserAgent());
      attributes.put(STR."path", clientInfo.getPath());
    }

    return auditData;
  }

  /**
   * Gets the subset of failure reasons that should be logged.
   */
  private Set<AuthenticationFailureReason> getFailureReasonsToLog(NexusAuthenticationEvent event) {
    return Sets.intersection(event.getAuthenticationFailureReasons(), AUDITABLE_FAILURE_REASONS);
  }
}