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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.validation.constraints.NotBlank;

import org.sonatype.nexus.blobstore.SelectOption;
import org.sonatype.nexus.capability.CapabilityConfigurationSupport;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Configuration for custom S3 regions capability.
 * 
 * @since 3.0
 * @see <a href="https://docs.aws.amazon.com/general/latest/gr/rande.html">AWS Regions and Endpoints</a>
 * @see <a href="https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/region-selection.html">AWS SDK for Java 2.x - Region Selection</a>
 */

public class CustomS3RegionCapabilityConfiguration
    extends CapabilityConfigurationSupport
{
  public static final String REGIONS = "regions";

  @NotBlank
  private String customRegions;

  @Inject
  public CustomS3RegionCapabilityConfiguration(final Map<String, String> properties) {
    checkNotNull(properties);
    this.customRegions = properties.get(REGIONS);
  }

  public String getRegions() { return customRegions; }

  /**
   * Converts the comma-separated regions string into a list of {@link SelectOption} objects.
   * 
   * @return List of region select options
   */
  public List<SelectOption> getRegionsList() {
    return Arrays.stream(customRegions.split(","))
        .map(String::trim)
        .map(region -> new SelectOption(region, region))
        .collect(Collectors.toList());
  }

  @Override
  public String toString() {
    return STR."\{getClass().getSimpleName()}\{regions='\{customRegions}'\}";
  }
}
