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

import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.capability.CapabilityDescriptorSupport;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.capability.Tag;
import org.sonatype.nexus.capability.Taggable;
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.formfields.ItemselectFormField;
import org.sonatype.nexus.formfields.PasswordFormField;
import org.sonatype.nexus.formfields.RepositoryCombobox;
import org.sonatype.nexus.formfields.UrlFormField;
import org.sonatype.nexus.repository.types.GroupType;

import static org.sonatype.nexus.repository.webhooks.RepositoryWebhookCapability.TYPE;
import static org.sonatype.nexus.repository.webhooks.RepositoryWebhookCapability.TYPE_ID;
import static org.sonatype.nexus.repository.webhooks.RepositoryWebhookCapability.messages;
import static org.sonatype.nexus.repository.webhooks.RepositoryWebhookCapabilityConfiguration.P_NAMES;
import static org.sonatype.nexus.repository.webhooks.RepositoryWebhookCapabilityConfiguration.P_REPOSITORY;
import static org.sonatype.nexus.repository.webhooks.RepositoryWebhookCapabilityConfiguration.P_SECRET;
import static org.sonatype.nexus.repository.webhooks.RepositoryWebhookCapabilityConfiguration.P_URL;

@AvailabilityVersion(from = "1.0")
@Named(TYPE_ID)
@Singleton
public class RepositoryWebhookCapabilityDescriptor
    extends CapabilityDescriptorSupport<RepositoryWebhookCapabilityConfiguration>
    implements Taggable
{
  private final FormField<String> repository;

  private final FormField<String> names;

  private final FormField<String> url;

  private final FormField<String> secret;

  public RepositoryWebhookCapabilityDescriptor() {
    setExposed(true);
    setHidden(false);

    // Repository selection field with explicit type parameter for Java 21 compatibility
    this.repository = new RepositoryCombobox(
        P_REPOSITORY,
        messages.repositoryLabel(),
        messages.repositoryHelp(),
        FormField.MANDATORY).excludingAnyOfTypes(GroupType.NAME);

    // Item selection field with explicit type parameter and Jakarta REST 3.1 compatible store API
    this.names = new ItemselectFormField(
        P_NAMES,
        messages.namesLabel(),
        messages.namesHelp(),
        FormField.MANDATORY).withStoreApi("coreui_Webhook.listWithTypeRepository")
            .withButtons("add", "remove")
            .withFromTitle("Available")
            .withToTitle("Selected");

    // URL field with explicit type parameter for Java 21 compatibility
    this.url = new UrlFormField(
        P_URL,
        messages.urlLabel(),
        messages.urlHelp(),
        FormField.MANDATORY);

    // Password field with explicit type parameter and autocomplete disabled for security
    this.secret = new PasswordFormField(
        P_SECRET,
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

  /**
   * Returns the form fields for this capability descriptor.  
   * Uses Java 21's type-safe List.of method to ensure compatibility with enhanced type checking.
   *
   * @return List of form fields with proper type information preserved
   */
  @Override
  public List<FormField> formFields() {
    return List.of(repository, names, url, secret);
  }

  /**
   * Creates a configuration object from the provided properties.
   * Implementation is compatible with Java 21's enhanced type checking.
   *
   * @param properties The properties to create the configuration from
   * @return A new configuration instance
   */
  @Override
  protected RepositoryWebhookCapabilityConfiguration createConfig(final Map<String, String> properties) {
    return new RepositoryWebhookCapabilityConfiguration(properties);
  }

  /**
   * Renders the about template for this capability.
   * Uses a template path compatible with Jakarta EE resource resolution.
   *
   * @return The rendered about content
   */
  @Override
  protected String renderAbout() {
    return render(TYPE_ID + "-about.vm");
  }

  /**
   * Returns the tags for this capability.
   * Uses Java 21's type-safe Set.of method for enhanced type safety.
   *
   * @return Set of tags for this capability
   */
  @Override
  public Set<Tag> getTags() {
    return Set.of(Tag.categoryTag(messages.category()));
  }

  /**
   * Returns the unique properties for this capability.
   * Uses Java 21's type-safe Set.of method for enhanced type safety.
   *
   * @return Set of property names that must be unique
   */
  @Override
  protected Set<String> uniqueProperties() {
    return Set.of(P_REPOSITORY, P_URL);
  }
}
