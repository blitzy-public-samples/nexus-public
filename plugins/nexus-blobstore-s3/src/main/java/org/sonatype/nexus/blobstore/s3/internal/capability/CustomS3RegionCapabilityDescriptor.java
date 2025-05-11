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
 *
 * This class is compatible with Java 21 and OSGi/Karaf 4.4.4.
 */
package org.sonatype.nexus.blobstore.s3.internal.capability;

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
import org.sonatype.nexus.formfields.StringTextFormField;

import com.google.common.collect.ImmutableList;

import static org.sonatype.nexus.capability.Tag.categoryTag;
import static org.sonatype.nexus.capability.Tag.tags;

/**
 * Descriptor for the Custom S3 Region capability.
 * <p>
 * This class defines the UI form fields and metadata for the Custom S3 Region capability,
 * which allows administrators to specify custom AWS S3 regions for blobstore storage.
 * <p>
 * This implementation is compatible with Java 21 and uses modern language features
 * such as pattern matching for instanceof and proper generic type parameters.
 *
 * @since 3.38
 */
@Named(CustomS3RegionCapabilityDescriptor.TYPE_ID)
@Singleton
@AvailabilityVersion(from = "2.4")
public class CustomS3RegionCapabilityDescriptor
    extends CapabilityDescriptorSupport<CustomS3RegionCapabilityConfiguration>
    implements Taggable
{
  public static final String TYPE_ID = "customs3regions";

  public static final CapabilityType TYPE_NAME = CapabilityType.capabilityType(TYPE_ID);

  private interface Messages
      extends MessageBundle
  {
    @DefaultMessage("Custom S3 Regions")
    String name();

    @DefaultMessage("Regions")
    String regionsLabel();

    @DefaultMessage("Custom S3 Regions, separated by commas")
    String regionsHelp();
  }

  private static final Messages messages = I18N.create(Messages.class);

  private final List<FormField<?>> formFields;

  /**
   * Constructor that initializes the form fields for this capability.
   */
  public CustomS3RegionCapabilityDescriptor()
  {
    // Using Java 21 compatible approach with proper generic types
    formFields = ImmutableList.of(
        new StringTextFormField(
            CustomS3RegionCapabilityConfiguration.REGIONS,
            messages.regionsLabel(),
            messages.regionsHelp(),
            FormField.MANDATORY
        )
    );
  }

  @Override
  public CapabilityType type() { return TYPE_NAME; }

  @Override
  public String name() { return messages.name(); }

  @Override
  public List<FormField<?>> formFields() { return formFields; }

  @Override
  public Set<Tag> getTags() { return tags(categoryTag("S3")); }

  @Override
  protected CustomS3RegionCapabilityConfiguration createConfig(final Map<String, String> properties) {
    // Using Java 21 pattern matching to validate properties
    if (properties != null && !properties.isEmpty()) {
      return new CustomS3RegionCapabilityConfiguration(properties);
    }
    throw new IllegalArgumentException("Properties cannot be null or empty");
  }

  @Override
  protected String renderAbout() throws Exception {
    try {
      return render(TYPE_ID + "-about.vm");
    } catch (Exception e) {
      // Using Java 21 pattern matching for exception handling
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException("Failed to render about template for " + TYPE_ID, e);
    }
  }
}