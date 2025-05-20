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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;
import org.sonatype.nexus.capability.CapabilitySupport;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.Tag;
import org.sonatype.nexus.capability.Taggable;
import org.sonatype.nexus.repository.capability.RepositoryConditions;
import org.sonatype.nexus.webhooks.WebhookService;
import org.sonatype.nexus.webhooks.WebhookSubscription;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.capability.CapabilityType.capabilityType;

/**
 * Repository webhook capability that manages webhook subscriptions for repositories.
 * Updated for Java 21 to support Virtual Thread-based webhook operations and String Templates.
 * 
 * <p>This implementation uses Java 21 Virtual Threads for non-blocking webhook subscription
 * management, allowing thousands of concurrent webhook operations with minimal resource usage.
 * String Templates are used for improved logging and message formatting.</p>
 */
@Named(RepositoryWebhookCapability.TYPE_ID)
public class RepositoryWebhookCapability
    extends CapabilitySupport<RepositoryWebhookCapabilityConfiguration>
    implements Taggable
{
  public static final String TYPE_ID = "webhook.repository";

  public static final CapabilityType TYPE = capabilityType(TYPE_ID);

  interface Messages
      extends MessageBundle
  {
    @DefaultMessage("Webhook: Repository")
    String name();

    @DefaultMessage("Webhook")
    String category();

    @DefaultMessage("Repository")
    String repositoryLabel();

    @DefaultMessage("Repository to discriminate events from")
    String repositoryHelp();

    @DefaultMessage("Event Types")
    String namesLabel();

    @DefaultMessage("Event types which trigger this Webhook")
    String namesHelp();

    @DefaultMessage("URL")
    String urlLabel();

    @DefaultMessage("Send a HTTP POST request to this URL")
    String urlHelp();

    @DefaultMessage("Secret Key")
    String secretLabel();

    @DefaultMessage("Key to use for HMAC payload digest")
    String secretHelp();

    @DefaultMessage("%s")
    String description(String names);
  }

  static final Messages messages = I18N.create(Messages.class);

  private final WebhookService webhookService;

  private final RepositoryConditions repositoryConditions;

  // Thread-safe list of active webhook subscriptions
  private final List<WebhookSubscription> subscriptions = new ArrayList<>();

  @Inject
  public RepositoryWebhookCapability(
      final WebhookService webhookService,
      final RepositoryConditions repositoryConditions)
  {
    this.webhookService = checkNotNull(webhookService);
    this.repositoryConditions = checkNotNull(repositoryConditions);
  }

  @Override
  protected RepositoryWebhookCapabilityConfiguration createConfig(final Map<String, String> properties) {
    return new RepositoryWebhookCapabilityConfiguration(properties);
  }

  @Override
  protected String renderDescription() {
    return STR."\{String.join(", ", getConfig().names)}";
  }

  @Override
  public Condition activationCondition() {
    return conditions().logical()
        .and(
            conditions().capabilities().passivateCapabilityDuringUpdate(),
            repositoryConditions.repositoryExists(() -> getConfig().repository));
  }

  @Override
  protected void onActivate(final RepositoryWebhookCapabilityConfiguration config) {
    // Use Virtual Thread executor for non-blocking webhook subscription management
    // Virtual Threads allow for high-concurrency operations without blocking platform threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    executor.execute(() -> {
      try {
        log.debug(STR."Activating repository webhook capability for repository: \{config.repository}");
        webhookService.getWebhooks()
            .stream()
            .filter(webhook -> webhook.getType() == RepositoryWebhook.TYPE && config.names.contains(webhook.getName()))
            .forEach(webhook -> {
              try {
                WebhookSubscription subscription = webhook.subscribe(config);
                synchronized(subscriptions) {
                  subscriptions.add(subscription);
                }
                log.debug(STR."Subscribed to webhook: \{webhook.getName()} for repository: \{config.repository}");
              } catch (Exception e) {
                log.error(STR."Failed to subscribe to webhook: \{webhook.getName()} for repository: \{config.repository}", e);
              }
            });
      } catch (Exception e) {
        log.error(STR."Error activating repository webhook capability for repository: \{config.repository}", e);
      }
    });
  }

  @Override
  protected void onPassivate(final RepositoryWebhookCapabilityConfiguration config) {
    // Use Virtual Thread executor for non-blocking webhook unsubscription
    // This ensures that webhook cancellation doesn't block the main capability lifecycle operations
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    executor.execute(() -> {
      try {
        log.debug(STR."Passivating repository webhook capability for repository: \{config.repository}");
        synchronized(subscriptions) {
          subscriptions.forEach(subscription -> {
            try {
              subscription.cancel();
              log.debug(STR."Cancelled webhook subscription for repository: \{config.repository}");
            } catch (Exception e) {
              log.error(STR."Failed to cancel webhook subscription for repository: \{config.repository}", e);
            }
          });
          subscriptions.clear();
        }
      } catch (Exception e) {
        log.error(STR."Error passivating repository webhook capability for repository: \{config.repository}", e);
      }
    });
  }

  @Override
  public Set<Tag> getTags() {
    return Set.of(
        Tag.categoryTag(messages.category()),
        Tag.repositoryTag(getConfig().repository));
  }
}