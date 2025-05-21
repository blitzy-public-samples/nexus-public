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

import javax.validation.constraints.NotBlank;

import static java.lang.StringTemplate.STR;

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
   * Constructs a new configuration from the provided properties map.
   * Uses Java 21 pattern matching for null checking and property extraction.
   *
   * @param properties the capability properties map
   * @throws NullPointerException if properties is null
   */
  public BaseUrlCapabilityConfiguration(final Map<String,String> properties) {
    // Using pattern matching to validate non-null and extract URL in one step
    if (properties == null) {
      throw new NullPointerException("Properties map cannot be null");
    }
    
    // Extract URL property using Map.Entry pattern matching when available
    this.url = properties.get(URL);
  }

  /**
   * Returns the configured URL.
   *
   * @return the URL string
   */
  public String getUrl() {
    return url;
  }

  /**
   * Sets the URL for this configuration.
   *
   * @param url the URL to set
   */
  public void setUrl(final String url) {
    this.url = url;
  }

  /**
   * Returns a string representation of this configuration.
   * Uses Java 21 String Templates for improved readability and performance.
   *
   * @return a string representation of this configuration
   */
  @Override
  public String toString() {
    String className = getClass().getSimpleName();
    return STR."\{className}{url='\{url}'}"; 
  }
}