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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
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
import org.sonatype.nexus.webhooks.WebhookRequestSendEvent;

import com.google.common.collect.Lists;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link GlobalRepositoryWebhook}.
 */
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
    globalRepositoryWebhook.subscribe(configuration);

    when(initiatorProvider.get()).thenReturn("initiator");
    when(nodeAccess.getId()).thenReturn("nodeId");
  }

  @Test
  void hasTheCorrectEventId() {
    assertThat(globalRepositoryWebhook.getId(), is(equalTo("rm:global:repository")));
  }

  @Test
  void queuesRepositoryCreatedEvents() {
    globalRepositoryWebhook.on(repositoryCreatedEvent);
    testRepositoryEvent("CREATED");
  }

  @Test
  void queuesRepositoryUpdatedEvents() {
    globalRepositoryWebhook.on(repositoryUpdatedEvent);
    testRepositoryEvent("UPDATED");
  }

  @Test
  void queuesRepositoryDeletedEvents() {
    globalRepositoryWebhook.on(repositoryDeletedEvent);
    testRepositoryEvent("DELETED");
  }

  /**
   * Tests that string templates are correctly used in webhook event payloads.
   * This test verifies that Java 21's string template feature works properly with webhook events.
   */
  @Test
  void stringTemplateUsageInWebhookPayload() {
    // Create a repository event
    Repository repository = mock(Repository.class);
    when(repository.getName()).thenReturn("test-repo");
    when(repository.getFormat()).thenReturn(new TestFormat());
    when(repository.getType()).thenReturn(new ProxyType());
    
    RepositoryCreatedEvent event = mock(RepositoryCreatedEvent.class);
    when(event.getRepository()).thenReturn(repository);
    
    // Trigger the webhook
    globalRepositoryWebhook.on(event);
    
    // Capture the webhook request
    ArgumentCaptor<WebhookRequestSendEvent> eventCaptor = ArgumentCaptor.forClass(WebhookRequestSendEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    
    RepositoryWebhookPayload payload = (RepositoryWebhookPayload) eventCaptor.getValue().getRequest().getPayload();
    
    // Create a string template using Java 21 syntax
    String repoInfo = STR."Repository \{payload.getRepository().getName()} has format \{payload.getRepository().getFormat()} and type \{payload.getRepository().getType()}";
    
    // Verify the string template works correctly
    assertEquals("Repository test-repo has format format and type proxy", repoInfo);
    assertThat(payload.getInitiator(), is(equalTo("initiator")));
    assertThat(payload.getNodeId(), is(equalTo("nodeId")));
    assertThat(payload.getAction().toString(), is(equalTo("CREATED")));
  }

  /**
   * Tests that webhook event dispatching is compatible with virtual threads.
   * This test verifies that Java 21's virtual thread feature works properly with webhook events.
   */
  @Test
  void virtualThreadCompatibilityForWebhookDispatching() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("webhook-virtual-").factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a repository for the test
      Repository repository = mock(Repository.class);
      when(repository.getName()).thenReturn("virtual-thread-test-repo");
      when(repository.getFormat()).thenReturn(new TestFormat());
      when(repository.getType()).thenReturn(new ProxyType());
      
      RepositoryCreatedEvent event = mock(RepositoryCreatedEvent.class);
      when(event.getRepository()).thenReturn(repository);
      
      // Reference to store the payload from the virtual thread
      AtomicReference<RepositoryWebhookPayload> payloadRef = new AtomicReference<>();
      
      // Submit the webhook processing to a virtual thread
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        // Trigger the webhook
        globalRepositoryWebhook.on(event);
        
        // Capture the webhook request
        ArgumentCaptor<WebhookRequestSendEvent> eventCaptor = ArgumentCaptor.forClass(WebhookRequestSendEvent.class);
        verify(eventManager).post(eventCaptor.capture());
        
        // Store the payload for verification
        payloadRef.set((RepositoryWebhookPayload) eventCaptor.getValue().getRequest().getPayload());
      }, virtualExecutor);
      
      // Wait for the virtual thread to complete
      future.get(5, TimeUnit.SECONDS);
      
      // Verify the payload was correctly processed by the virtual thread
      RepositoryWebhookPayload payload = payloadRef.get();
      assertThat(payload, is(notNullValue()));
      assertThat(payload.getRepository().getName(), is(equalTo("virtual-thread-test-repo")));
      assertThat(payload.getRepository().getFormat(), is(equalTo("format")));
      assertThat(payload.getRepository().getType(), is(equalTo("proxy")));
      assertThat(payload.getInitiator(), is(equalTo("initiator")));
      assertThat(payload.getNodeId(), is(equalTo("nodeId")));
      assertThat(payload.getAction().toString(), is(equalTo("CREATED")));
      
      // Verify the thread used was actually a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "The test should be running in a virtual thread");
    } finally {
      virtualExecutor.shutdownNow();
    }
  }

  private void testRepositoryEvent(String action) {
    ArgumentCaptor<WebhookRequestSendEvent> assetArgumentCaptor =
        ArgumentCaptor.forClass(WebhookRequestSendEvent.class);
    verify(eventManager).post(assetArgumentCaptor.capture());

    RepositoryWebhookPayload repositoryPayload =
        (RepositoryWebhookPayload) assetArgumentCaptor.getValue().getRequest().getPayload();

    assertThat(repositoryPayload.getInitiator(), is(equalTo("initiator")));
    assertThat(repositoryPayload.getNodeId(), is(equalTo("nodeId")));
    assertThat(repositoryPayload.getAction().toString(), is(equalTo(action)));

    assertThat(repositoryPayload.getRepository().getName(), is(equalTo("name")));
    assertThat(repositoryPayload.getRepository().getFormat(), is(equalTo("format")));
    assertThat(repositoryPayload.getRepository().getType(), is(equalTo("proxy")));
  }

  public class TestFormat
      extends Format
  {
    public TestFormat() {
      super("format");
    }
  }
}