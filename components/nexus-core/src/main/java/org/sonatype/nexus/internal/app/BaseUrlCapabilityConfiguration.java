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
package org.sonatype.nexus.internal.app;

import java.util.Map;

import org.sonatype.nexus.capability.CapabilityConfigurationSupport;
import org.sonatype.nexus.validation.constraint.UrlString;

import jakarta.validation.constraints.NotBlank;

import static java.lang.StringTemplate.STR;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link BaseUrlCapability} configuration.
 *
 * @since 3.0
 */
public class BaseUrlCapabilityConfiguration
    extends CapabilityConfigurationSupport
{
  public static final String URL = "url";

  @NotBlank
  @UrlString
  private String url;

  /**
   * Creates a new configuration instance from the provided properties map.
   * Uses pattern matching to extract the URL property.
   *
   * @param properties The capability properties map
   */
  public BaseUrlCapabilityConfiguration(final Map<String,String> properties) {
    checkNotNull(properties);
    
    // Using pattern matching to extract URL property
    if (properties instanceof Map<String, String> map && map.containsKey(URL)) {
      this.url = map.get(URL);
    } else {
      this.url = null; // Will be caught by @NotBlank validation
    }
  }

  /**
   * Returns the configured URL.
   *
   * @return The URL string
   */
  public String getUrl() {
    return url;
  }

  /**
   * Sets the URL for this configuration.
   *
   * @param url The URL to set
   */
  public void setUrl(final String url) {
    this.url = url;
  }

  @Override
  public String toString() {
    return STR."\{getClass().getSimpleName()}{url='\{url}'}";
  }
}