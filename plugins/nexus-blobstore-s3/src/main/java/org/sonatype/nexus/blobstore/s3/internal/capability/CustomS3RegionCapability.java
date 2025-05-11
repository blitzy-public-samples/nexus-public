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
 * This file has been updated for Java 21 compatibility.
 */
package org.sonatype.nexus.blobstore.s3.internal.capability;

import java.util.Map;
import javax.inject.Named;

import org.sonatype.nexus.capability.CapabilitySupport;

/**
 * Capability implementation for custom S3 regions.
 * <p>
 * This capability allows administrators to define custom AWS S3 regions that can be used
 * with S3 blobstores. The implementation is compatible with Java 21 and OSGi/Karaf 4.3.9.
 *
 * @since 3.38
 * @see CustomS3RegionCapabilityConfiguration
 * @see CustomS3RegionCapabilityDescriptor
 */
@Named(CustomS3RegionCapabilityDescriptor.TYPE_ID)
public class CustomS3RegionCapability
    extends CapabilitySupport<CustomS3RegionCapabilityConfiguration>
{
  /**
   * Creates a new configuration instance from the provided properties.
   * <p>
   * This method is called by the capability framework when the capability is created or updated.
   *
   * @param properties the capability properties from the UI or API
   * @return a new configuration instance
   * @throws IllegalArgumentException if properties are invalid
   */
  @Override
  protected CustomS3RegionCapabilityConfiguration createConfig(final Map<String, String> properties) {
    // Using Java 21 pattern matching to validate properties
    if (properties != null && !properties.isEmpty()) {
      return new CustomS3RegionCapabilityConfiguration(properties);
    }
    throw new IllegalArgumentException(STR."Invalid properties: \{properties}");
  }
}