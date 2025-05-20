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
package org.sonatype.nexus.repository.webhooks;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.audit.InitiatorProvider;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.RepositoryEvent;
import org.sonatype.nexus.repository.manager.RepositoryCreatedEvent;
import org.sonatype.nexus.repository.manager.RepositoryDeletedEvent;
import org.sonatype.nexus.repository.manager.RepositoryUpdatedEvent;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.webhooks.GlobalRepositoryWebhook.RepositoryWebhookPayload;
import org.sonatype.nexus.testcommon.Java21TestGroup;
import org.sonatype.nexus.webhooks.WebhookRequestSendEvent;
import org.sonatype.nexus.webhooks.WebhookSubscription;

import com.google.common.collect.Lists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.lang.StringTemplate.STR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class GlobalRepositoryWebhookTest
    extends TestSupport
{
  @Mock
  private EventManager eventManager;

  @Mock
  private InitiatorProvider initiatorProvider;

  @Mock
  private NodeAccess nodeAccess;

  @Mock
  private RepositoryCreatedEvent repositoryCreatedEvent;

  @Mock
  private RepositoryUpdatedEvent repositoryUpdatedEvent;

  @Mock
  private RepositoryDeletedEvent repositoryDeletedEvent;

  private GlobalRepositoryWebhook globalRepositoryWebhook;

  @BeforeEach
  public void setUp() {
    globalRepositoryWebhook = new GlobalRepositoryWebhook(nodeAccess, initiatorProvider);
    globalRepositoryWebhook.setEventManager(eventManager);

    for (RepositoryEvent repositoryEvent : Lists
        .newArrayList(repositoryCreatedEvent, repositoryDeletedEvent, repositoryUpdatedEvent)) {
      Repository repository = mock(Repository.class);
      when(repository.getName()).thenReturn("name");
      when(repository.getFormat()).thenReturn(new TestFormat());
      when(repository.getType()).thenReturn(new ProxyType());

      when(repositoryEvent.getRepository()).thenReturn(repository);
    }

    RepositoryWebhook.Configuration configuration = mock(RepositoryWebhook.Configuration.class);
    when(configuration.getRepository()).thenReturn("repoName");
    when(configuration.getUrl()).thenReturn("http://example.com/webhook");
    globalRepositoryWebhook.subscribe(configuration);

    when(initiatorProvider.get()).thenReturn("initiator");
    when(nodeAccess.getId()).thenReturn("nodeId");
  }

  @Test
  public void hasTheCorrectEventId() {
    assertEquals("rm:global:repository", globalRepositoryWebhook.getId());
  }

  @Test
  public void queuesRepositoryCreatedEvents() {
    globalRepositoryWebhook.on(repositoryCreatedEvent);
    testRepositoryEvent("CREATED");
  }

  @Test
  public void queuesRepositoryUpdatedEvents() {
    globalRepositoryWebhook.on(repositoryUpdatedEvent);
    testRepositoryEvent("UPDATED");
  }

  @Test
  public void queuesRepositoryDeletedEvents() {
    globalRepositoryWebhook.on(repositoryDeletedEvent);
    testRepositoryEvent("DELETED");
  }
  
  @Test
  public void verifyStringTemplateUsageInWebhookPayload() throws Exception {
    // Create a repository event with a specific name to test string template usage
    Repository repository = mock(Repository.class);
    when(repository.getName()).thenReturn("test-repo");
    when(repository.getFormat()).thenReturn(new TestFormat());
    when(repository.getType()).thenReturn(new ProxyType());
    
    RepositoryCreatedEvent event = mock(RepositoryCreatedEvent.class);
    when(event.getRepository()).thenReturn(repository);
    
    // Capture the thread name to verify string template usage
    final String[] capturedThreadName = new String[1];
    Thread.ofVirtual().name("test-thread").start(() -> {
      globalRepositoryWebhook.on(event);
      capturedThreadName[0] = Thread.currentThread().getName();
    }).join();
    
    // Verify that the thread name was created using string templates
    assertTrue(capturedThreadName[0].contains("repository-created-test-repo") || 
               capturedThreadName[0].contains("webhook-delivery-test-repo"),
               "Thread name should be created using string templates");
    
    // Verify the webhook payload was created and posted
    ArgumentCaptor<WebhookRequestSendEvent> eventCaptor = 
        ArgumentCaptor.forClass(WebhookRequestSendEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    
    // Verify the payload contains the expected data
    RepositoryWebhookPayload payload = 
        (RepositoryWebhookPayload) eventCaptor.getValue().getRequest().getPayload();
    assertEquals("test-repo", payload.getRepository().getName());
  }
  
  @Test
  public void verifyVirtualThreadCompatibilityForWebhookEventDispatching() throws Exception {
    // Create a repository event
    Repository repository = mock(Repository.class);
    when(repository.getName()).thenReturn("virtual-thread-test");
    when(repository.getFormat()).thenReturn(new TestFormat());
    when(repository.getType()).thenReturn(new ProxyType());
    
    RepositoryCreatedEvent event = mock(RepositoryCreatedEvent.class);
    when(event.getRepository()).thenReturn(repository);
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute the event handler in a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      try {
        globalRepositoryWebhook.on(event);
        latch.countDown();
      }
      catch (Exception e) {
        log.error("Error in virtual thread", e);
      }
    });
    
    // Wait for the virtual thread to complete
    boolean completed = latch.await(5, TimeUnit.SECONDS);
    assertTrue(completed, "Virtual thread should complete webhook processing");
    assertTrue(virtualThread.isVirtual(), "Thread should be a virtual thread");
    
    // Verify the webhook payload was created and posted
    ArgumentCaptor<WebhookRequestSendEvent> eventCaptor = 
        ArgumentCaptor.forClass(WebhookRequestSendEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    
    // Verify the payload contains the expected data
    RepositoryWebhookPayload payload = 
        (RepositoryWebhookPayload) eventCaptor.getValue().getRequest().getPayload();
    assertEquals("virtual-thread-test", payload.getRepository().getName());
  }

  private void testRepositoryEvent(String action) {
    ArgumentCaptor<WebhookRequestSendEvent> assetArgumentCaptor =
        ArgumentCaptor.forClass(WebhookRequestSendEvent.class);
    verify(eventManager).post(assetArgumentCaptor.capture());

    RepositoryWebhookPayload repositoryPayload =
        (RepositoryWebhookPayload) assetArgumentCaptor.getValue().getRequest().getPayload();

    assertEquals("initiator", repositoryPayload.getInitiator());
    assertEquals("nodeId", repositoryPayload.getNodeId());
    assertEquals(action, repositoryPayload.getAction().toString());

    assertEquals("name", repositoryPayload.getRepository().getName());
    assertEquals("format", repositoryPayload.getRepository().getFormat());
    assertEquals("proxy", repositoryPayload.getRepository().getType());
  }

  public class TestFormat
      extends Format
  {
    public TestFormat() {
      super("format");
    }
  }
}
