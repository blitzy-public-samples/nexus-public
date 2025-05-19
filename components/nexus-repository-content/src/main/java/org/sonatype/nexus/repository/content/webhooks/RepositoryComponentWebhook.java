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
package org.sonatype.nexus.repository.content.webhooks;

import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.InitiatorProvider;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.event.component.ComponentCreatedEvent;
import org.sonatype.nexus.repository.content.event.component.ComponentDeletedEvent;
import org.sonatype.nexus.repository.content.event.component.ComponentEvent;
import org.sonatype.nexus.repository.content.event.component.ComponentPurgedEvent;
import org.sonatype.nexus.repository.content.event.component.ComponentUpdatedEvent;
import org.sonatype.nexus.repository.content.store.InternalIds;
import org.sonatype.nexus.repository.rest.api.RepositoryItemIDXO;
import org.sonatype.nexus.repository.webhooks.RepositoryWebhook;
import org.sonatype.nexus.webhooks.WebhookPayload;
import org.sonatype.nexus.webhooks.WebhookRequest;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

import static com.google.common.base.Preconditions.checkNotNull;

@Named
@Singleton
public class RepositoryComponentWebhook
    extends RepositoryWebhook
{
  public static final String NAME = "component";

  private static final Logger LOG = Logger.getLogger(RepositoryComponentWebhook.class.getName());
  
  private final NodeAccess nodeAccess;

  private final InitiatorProvider initiatorProvider;
  
  private final ExecutorService executor;

  @Inject
  public RepositoryComponentWebhook(final NodeAccess nodeAccess, final InitiatorProvider initiatorProvider) {
    this.nodeAccess = checkNotNull(nodeAccess);
    this.initiatorProvider = checkNotNull(initiatorProvider);
    // Create an executor service that creates a new virtual thread for each task
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public String getName() {
    return NAME;
  }

  private enum EventAction
  {
    CREATED,
    UPDATED,
    DELETED,
    PURGED
  }

  @Subscribe
  @AllowConcurrentEvents
  public void on(final ComponentEvent event) {
    // Use pattern matching for switch to determine the event type and action
    switch (event) {
      case ComponentCreatedEvent e -> {
        LOG.fine(STR."Processing component created event for component: \{e.getComponent().name()}");
        maybeQueue(e, EventAction.CREATED);
      }
      case ComponentUpdatedEvent e -> {
        LOG.fine(STR."Processing component updated event for component: \{e.getComponent().name()}");
        maybeQueue(e, EventAction.UPDATED);
      }
      case ComponentDeletedEvent e -> {
        LOG.fine(STR."Processing component deleted event for component: \{e.getComponent().name()}");
        maybeQueue(e, EventAction.DELETED);
      }
      case ComponentPurgedEvent e -> {
        LOG.fine(STR."Processing component purged event for repository: \{e.getRepository().map(Repository::getName).orElse("unknown")}");
        maybeQueue(getPayload(e, EventAction.PURGED));
      }
      default -> LOG.warning(STR."Unhandled component event type: \{event.getClass().getName()}");
    }
  }

  /**
   * Maybe queue {@link WebhookRequest} for event matching subscriptions.
   */
  private void maybeQueue(final ComponentEvent event, final EventAction eventAction) {
    maybeQueue(getPayload(event, eventAction));
  }

  /**
   * Maybe queue {@link WebhookRequest} for event matching subscriptions.
   * Uses virtual threads to process each subscription concurrently.
   */
  private void maybeQueue(final RepositoryComponentWebhookPayload payload) {
    LOG.fine(STR."Checking subscriptions for repository: \{payload.getRepositoryName()}");
    
    subscriptions.forEach(subscription -> {
      // Submit each subscription processing to a virtual thread
      executor.submit(() -> {
        try {
          RepositoryWebhook.Configuration configuration = (RepositoryWebhook.Configuration) subscription.getConfiguration();
          if (configuration.getRepository().equals(payload.getRepositoryName())) {
            LOG.fine(STR."Queueing webhook for repository: \{payload.getRepositoryName()}, action: \{payload.getAction()}");
            queue(subscription, payload);
          }
        } catch (Exception e) {
          LOG.warning(STR."Error processing webhook subscription: \{e.getMessage()}");
        }
      });
    });
  }

  private RepositoryComponentWebhookPayload getPayload(final String repositoryName, final EventAction eventAction) {
    return new RepositoryComponentWebhookPayload(
        nodeAccess.getId(),
        new Date(),
        initiatorProvider.get(),
        repositoryName,
        eventAction);
  }

  private RepositoryComponentWebhookPayload getPayload(final ComponentEvent event, final EventAction eventAction) {
    Optional<Repository> repository = event.getRepository();
    String repositoryName = repository.map(Repository::getName).orElse(null);
    String format = repository.map(r -> r.getFormat().getValue()).orElse(null);

    Component component = event.getComponent();
    EntityId componentId = InternalIds.toExternalId(InternalIds.internalComponentId(component));
    RepositoryComponentWebhookPayload payload = getPayload(repositoryName, eventAction);
    payload.setComponent(new RepositoryComponentWebhookPayload.RepositoryComponent(
        componentId.getValue(),
        new RepositoryItemIDXO(repositoryName, componentId.getValue()).getValue(),
        format,
        component.name(),
        component.namespace(),
        component.version()));
    return payload;
  }

  private RepositoryComponentWebhookPayload getPayload(
      final ComponentPurgedEvent event,
      final EventAction eventAction)
  {
    Optional<Repository> repository = event.getRepository();
    String repositoryName = repository.map(Repository::getName).orElse(null);
    RepositoryComponentWebhookPayload payload = getPayload(repositoryName, eventAction);
    payload.setComponents(Arrays.stream(event.getComponentIds())
        .mapToObj(id -> InternalIds.toExternalId(id).getValue())
        .toArray(String[]::new));
    return payload;
  }

  public static class RepositoryComponentWebhookPayload
      extends WebhookPayload
  {
    private final String repositoryName;

    private final EventAction action;

    private RepositoryComponent component;

    private String[] components;

    public RepositoryComponentWebhookPayload(
        final String nodeId,
        final Date timestamp,
        final String initiator,
        final String repositoryName,
        final EventAction action)
    {
      this.repositoryName = repositoryName;
      this.action = action;
      setNodeId(nodeId);
      setTimestamp(timestamp);
      setInitiator(initiator);
    }

    public String getRepositoryName() {
      return repositoryName;
    }

    public EventAction getAction() {
      return action;
    }

    public RepositoryComponent getComponent() {
      return component;
    }

    public void setComponent(RepositoryComponent component) {
      this.component = component;
    }

    public String[] getComponents() {
      return components;
    }

    public void setComponents(String[] components) {
      this.components = components;
    }

    public static class RepositoryComponent
    {
      private final String id;

      private final String componentId;

      private final String format;

      private final String name;

      private final String group;

      private final String version;

      public RepositoryComponent(
          String id,
          String componentId,
          String format,
          String name,
          String group,
          String version)
      {
        this.id = id;
        this.componentId = componentId;
        this.format = format;
        this.name = name;
        this.group = group;
        this.version = version;
      }

      public String getId() {
        return id;
      }

      public String getComponentId() {
        return componentId;
      }

      public String getFormat() {
        return format;
      }

      public String getName() {
        return name;
      }

      public String getGroup() {
        return group;
      }

      public String getVersion() {
        return version;
      }
    }
  }
}