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
 * Capability for custom S3 regions.
 * 
 * @since 3.0
 * @see CustomS3RegionCapabilityDescriptor
 * @see CustomS3RegionCapabilityConfiguration
 */
@Named(CustomS3RegionCapabilityDescriptor.TYPE_ID)
public class CustomS3RegionCapability
    extends CapabilitySupport<CustomS3RegionCapabilityConfiguration>
{
  @Override
  protected CustomS3RegionCapabilityConfiguration createConfig(final Map<String, String> properties) {
    // Using pattern matching to ensure properties is not null before creating configuration
    if (properties instanceof Map<String, String> map) {
      return new CustomS3RegionCapabilityConfiguration(map);
    }
    throw new IllegalArgumentException("Properties map cannot be null");
  }
}