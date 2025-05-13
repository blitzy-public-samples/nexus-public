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
package org.sonatype.nexus.audit.internal;

import java.util.Map;
import java.util.concurrent.Executors;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditDataRecordedEvent;
import org.sonatype.nexus.audit.internal.GlobalAuditWebhook.AuditWebhookPayload.Audit;
import org.sonatype.nexus.webhooks.GlobalWebhook;
import org.sonatype.nexus.webhooks.WebhookPayload;

import com.google.common.eventbus.Subscribe;

/**
 * Global audit {@link GlobalWebhook} implementation.
 * <p>
 * This webhook dispatches audit events to configured subscribers using Java 21 Virtual Threads
 * for improved concurrency and resource utilization.
 *
 * @since 3.1
 */
@Named
@Singleton
public class GlobalAuditWebhook
    extends GlobalWebhook
{
  public static final String NAME = "audit";

  @Override
  public String getName() {
    return NAME;
  }

  /**
   * Event handler for audit data recorded events.
   * <p>
   * Uses Java 21 Virtual Threads for non-blocking, concurrent webhook dispatch,
   * allowing for thousands of concurrent webhook deliveries with minimal resource overhead.
   * This is particularly beneficial for high-volume audit environments where many
   * webhook subscribers may exist.
   *
   * @param event the audit data recorded event
   */
  @Subscribe
  public void on(final AuditDataRecordedEvent event) {
    // Create the payload from the event data
    AuditData auditData = event.getData();
    AuditWebhookPayload payload = new AuditWebhookPayload();
    payload.setInitiator(auditData.getInitiator());
    payload.setNodeId(auditData.getNodeId());

    // Use pattern matching to safely extract and process audit data
    Audit audit = switch (auditData) {
      case AuditData data when data != null -> {
        yield new Audit(data.getDomain(), data.getType(),
            data.getContext(), data.getAttributes());
      }
      case null -> throw new IllegalArgumentException("Audit data cannot be null");
    };
    
    payload.setAudit(audit);

    // Dispatch to all subscribers using Virtual Threads for non-blocking I/O operations
    getSubscriptions().forEach(subscription -> {
      // Use Virtual Threads for each webhook dispatch to improve concurrency
      Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
        queue(subscription, payload);
      });
    });
  }

  /**
   * Webhook payload for audit events.
   */
  public static class AuditWebhookPayload
      extends WebhookPayload
  {
    private Audit audit;

    public Audit getAudit() {
      return audit;
    }

    public void setAudit(Audit audit) {
      this.audit = audit;
    }

    /**
     * Audit data structure for webhook payloads.
     */
    public static class Audit
    {
      private String domain;

      private String type;

      private String context;

      private Map<String, Object> attributes;

      public Audit(final String domain, final String type, final String context, final Map<String, Object> attributes) {
        this.domain = domain;
        this.type = type;
        this.context = context;
        this.attributes = attributes;
      }

      public String getDomain() {
        return domain;
      }

      public void setDomain(String domain) {
        this.domain = domain;
      }

      public String getType() {
        return type;
      }

      public void setType(String type) {
        this.type = type;
      }

      public String getContext() {
        return context;
      }

      public void setContext(String context) {
        this.context = context;
      }

      public Map<String, Object> getAttributes() {
        return attributes;
      }

      public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
      }
    }
  }
}