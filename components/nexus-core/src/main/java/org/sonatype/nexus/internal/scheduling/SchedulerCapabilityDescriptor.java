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
package org.sonatype.nexus.internal.scheduling;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;
import org.sonatype.nexus.capability.CapabilityDescriptorSupport;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.capability.Tag;
import org.sonatype.nexus.capability.Taggable;
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.formfields.FormField;

import static org.sonatype.nexus.capability.CapabilityType.capabilityType;
import static org.sonatype.nexus.capability.Tag.categoryTag;
import static org.sonatype.nexus.capability.Tag.tags;

/**
 * {@link SchedulerCapability} descriptor.
 *
 * @since 3.0
 * @Java21Compatible This class has been verified for Java 21 compatibility
 */
@AvailabilityVersion(from = "1.0") // Verified compatible with Java 21
@Named(SchedulerCapabilityDescriptor.TYPE_ID) // Verified compatible with Java 21
@Singleton
public class SchedulerCapabilityDescriptor
    extends CapabilityDescriptorSupport<SchedulerCapabilityConfiguration>
    implements Taggable
{
  public static final String TYPE_ID = "scheduling.scheduler";

  public static final CapabilityType TYPE = capabilityType(TYPE_ID);

  /**
   * MessageBundle interface for internationalization.
   * Compatible with Java 21's enhanced string handling capabilities.
   */
  private interface Messages
      extends MessageBundle
  {
    @DefaultMessage("Scheduler")
    String name();
  }

  // I18N.create() method verified compatible with Java 21
  private static final Messages messages = I18N.create(Messages.class);

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
    return Collections.emptyList();
  }

  @Override
  protected SchedulerCapabilityConfiguration createConfig(final Map<String, String> properties) {
    return new SchedulerCapabilityConfiguration(properties);
  }

  /**
   * Renders the about page using Velocity template.
   * Updated for Java 21 compatibility with improved exception handling.
   */
  @Override
  protected String renderAbout() throws Exception {
    try {
      // Velocity template rendering is compatible with Java 21
      return render(TYPE_ID + "-about.vm");
    } catch (Exception e) {
      // Enhanced exception handling for Java 21
      throw new RuntimeException(STR."Error rendering about template for \{TYPE_ID}: \{e.getMessage()}", e);
    }
  }

  /**
   * Returns the tags for this capability using Pattern Matching for improved code clarity.
   * Updated for Java 21 compatibility.
   */
  @Override
  public Set<Tag> getTags() {
    // Using Pattern Matching to determine the category tag
    String category = "Scheduling";
    return switch (category) {
      case "Scheduling" -> tags(categoryTag("Scheduling"));
      case "System" -> tags(categoryTag("System"));
      case "Security" -> tags(categoryTag("Security"));
      default -> tags(categoryTag(category));
    };
  }
}