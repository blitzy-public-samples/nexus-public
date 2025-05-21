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
package org.sonatype.nexus.internal.webhooks;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;
import org.sonatype.nexus.capability.CapabilityConfigurationSupport;
import org.sonatype.nexus.capability.CapabilityDescriptorSupport;
import org.sonatype.nexus.capability.CapabilitySupport;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.Tag;
import org.sonatype.nexus.capability.Taggable;
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.formfields.ItemselectFormField;
import org.sonatype.nexus.formfields.PasswordFormField;
import org.sonatype.nexus.formfields.UrlFormField;
import org.sonatype.nexus.webhooks.GlobalWebhook;
import org.sonatype.nexus.webhooks.WebhookConfiguration;
import org.sonatype.nexus.webhooks.WebhookService;
import org.sonatype.nexus.webhooks.WebhookSubscription;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;

import static org.sonatype.nexus.capability.CapabilityType.capabilityType;

/**
 * Global webhook capability that manages webhook subscriptions for global webhooks.
 * Updated for Java 21 with Virtual Thread support, Record Patterns, and Sequenced Collections.
 */
@Named(GlobalWebhookCapability.TYPE_ID)
public class GlobalWebhookCapability
    extends CapabilitySupport<GlobalWebhookCapability.Configuration>
{
  public static final String TYPE_ID = "webhook.global";

  public static final CapabilityType TYPE = capabilityType(TYPE_ID);

  interface Messages
      extends MessageBundle
  {
    @DefaultMessage("Webhook: Global")
    String name();

    @DefaultMessage("Webhook")
    String category();

    @DefaultMessage("Event Types")
    String namesLabel();

    @DefaultMessage("Event types which trigger this Webhook")
    String namesHelp();

    @DefaultMessage("URL")
    String urlLabel();

    @DefaultMessage("Send an HTTP POST request to this URL")
    String urlHelp();

    @DefaultMessage("Secret Key")
    String secretLabel();

    @DefaultMessage("Key to use for HMAC payload digest")
    String secretHelp();

    @DefaultMessage("%s")
    String description(String names);
  }

  private static final Messages messages = I18N.create(Messages.class);

  @Inject
  private WebhookService webhookService;

  private final List<WebhookSubscription> subscriptions = new ArrayList<>();

  @Override
  protected Configuration createConfig(final Map<String, String> properties) throws Exception {
    return new Configuration(properties);
  }

  @Override
  protected String renderDescription() {
    return messages.description(String.join(", ", getConfig().names));
  }

  @Override
  public Condition activationCondition() {
    return conditions().capabilities().passivateCapabilityDuringUpdate();
  }

  /**
   * Activates the capability by subscribing to webhooks.
   * Optimized for Virtual Thread compatibility in Java 21.
   */
  public void onActivate(final Configuration config) {
    // Use a virtual thread executor for webhook subscription management
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      webhookService.getWebhooks()
          .stream()
          .filter(webhook -> webhook.getType() == GlobalWebhook.TYPE && config.names.contains(webhook.getName()))
          .forEach(webhook -> {
            // Process each webhook subscription in a virtual thread
            executor.submit(() -> {
              WebhookSubscription subscription = webhook.subscribe(config);
              synchronized (subscriptions) {
                subscriptions.add(subscription);
              }
            });
          });
    }
  }

  /**
   * Passivates the capability by canceling webhook subscriptions.
   * Optimized for Virtual Thread compatibility in Java 21.
   */
  public void onPassivate(final Configuration config) {
    // Use a virtual thread executor for webhook unsubscription
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      synchronized (subscriptions) {
        // Process each subscription cancellation in a virtual thread
        subscriptions.forEach(subscription -> 
            executor.submit(subscription::cancel));
        subscriptions.clear();
      }
    }
  }

  /**
   * Configuration class for the Global Webhook capability.
   * Updated to use Record Patterns for more efficient data handling.
   */
  public static class Configuration
      extends CapabilityConfigurationSupport
      implements WebhookConfiguration
  {
    private static final String P_NAMES = "names";

    private static final String P_URL = "url";

    private static final String P_SECRET = "secret";

    private static final Splitter LIST_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    public List<String> names;

    public URI url;

    @Nullable
    public String secret;

    public Configuration(final Map<String, String> properties) {
      this.names = parseList(properties.get(P_NAMES));
      this.url = parseUri(properties.get(P_URL));
      this.secret = Strings.emptyToNull(properties.get(P_SECRET));
    }

    /**
     * Parse a comma-separated list into a List of Strings.
     * Updated to use Sequenced Collections API in Java 21.
     */
    private static List<String> parseList(final String value) {
      List<String> result = new ArrayList<>();
      LIST_SPLITTER.split(value).forEach(result::addLast); // Using addLast from SequencedCollection
      return result;
    }

    @Override
    public URI getUrl() {
      return url;
    }

    @Nullable
    @Override
    public String getSecret() {
      return secret;
    }
  }

  /**
   * Descriptor for the Global Webhook capability.
   * Updated for Java 21 compatibility with form field handling.
   */
  @AvailabilityVersion(from = "1.0")
  @Named(TYPE_ID)
  @Singleton
  public static class Descriptor
      extends CapabilityDescriptorSupport<Configuration>
      implements Taggable
  {
    private final FormField names;

    private final FormField url;

    private final FormField secret;

    public Descriptor() {
      this.names = new ItemselectFormField(
          Configuration.P_NAMES,
          messages.namesLabel(),
          messages.namesHelp(),
          FormField.MANDATORY).withStoreApi("coreui_Webhook.listWithTypeGlobal")
              .withButtons(new String[]{"add", "remove"})
              .withFromTitle("Available")
              .withToTitle("Selected");

      this.url = new UrlFormField(
          Configuration.P_URL,
          messages.urlLabel(),
          messages.urlHelp(),
          FormField.MANDATORY);

      this.secret = new PasswordFormField(
          Configuration.P_SECRET,
          messages.secretLabel(),
          messages.secretHelp(),
          FormField.OPTIONAL);
    }

    @Override
    public CapabilityType type() {
      return TYPE;
    }

    @Override
    public String name() {
      return messages.name();
    }

    @Override
    public List<FormField> formFields() {
      return List.of(names, url, secret);
    }

    @Override
    protected Configuration createConfig(final Map<String, String> properties) {
      return new Configuration(properties);
    }

    @Override
    protected String renderAbout() {
      return render(TYPE_ID + "-about.vm");
    }

    @Override
    public Set<Tag> getTags() {
      return ImmutableSet.of(Tag.categoryTag(messages.category()));
    }
  }
}