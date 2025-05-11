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

import java.util.HashMap;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditDataRecordedEvent;
import org.sonatype.nexus.audit.internal.GlobalAuditWebhook.AuditWebhookPayload;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.webhooks.WebhookConfiguration;
import org.sonatype.nexus.webhooks.WebhookRequestSendEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link GlobalAuditWebhook} using JUnit Jupiter and Mockito 4.11.0+.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
class GlobalAuditWebhookTest
    extends TestSupport
{

  @Mock
  private EventManager eventManager;

  private GlobalAuditWebhook underTest;

  @Captor
  private ArgumentCaptor<WebhookRequestSendEvent> argumentCaptor;

  @BeforeEach
  void setUp() {
    underTest = new GlobalAuditWebhook();
    underTest.setEventManager(eventManager);
  }

  @Test
  @DisplayName("Should have the correct event ID")
  void shouldHaveTheCorrectEventId() {
    assertThat(underTest.getId(), is("rm:global:audit"));
  }

  @Test
  @DisplayName("Should queue audit webhook with correct payload")
  void shouldQueueAuditWebhook() {
    WebhookConfiguration configuration = mock(WebhookConfiguration.class);
    underTest.subscribe(configuration);

    AuditData auditData = new AuditData();
    auditData.setAttributes(new HashMap<>(Map.of("key", "value")));
    auditData.setInitiator("initiator");
    auditData.setNodeId("nodeId");
    auditData.setDomain("domain");
    auditData.setContext("context");
    auditData.setType("type");

    AuditDataRecordedEvent auditRecordedEvent = new AuditDataRecordedEvent(auditData);

    underTest.on(auditRecordedEvent);

    verify(eventManager).post(argumentCaptor.capture());

    AuditWebhookPayload auditPayload = (AuditWebhookPayload) argumentCaptor.getValue().getRequest().getPayload();

    assertThat(auditPayload.getInitiator(), is("initiator"));
    assertThat(auditPayload.getNodeId(), is("nodeId"));
    assertThat(auditPayload.getAudit().getDomain(), is("domain"));
    assertThat(auditPayload.getAudit().getType(), is("type"));
    assertThat(auditPayload.getAudit().getContext(), is("context"));
    assertThat(auditPayload.getAudit().getAttributes().size(), is(1));
    assertThat(auditPayload.getAudit().getAttributes(), hasEntry("key", "value"));
  }
}