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

import java.util.Map;
import java.util.Map.Entry;

import org.sonatype.nexus.capability.CapabilityConfigurationSupport;

/**
 * {@link SchedulerCapability} configuration.
 *
 * @since 3.0
 */
public class SchedulerCapabilityConfiguration
    extends CapabilityConfigurationSupport
{
  /**
   * Creates a new configuration instance.
   * 
   * Uses Java 21 pattern matching for parameter validation and demonstrates record pattern usage
   * for Map entry processing, though this particular class doesn't use any specific properties.
   *
   * @param properties the configuration properties (must not be null)
   * @throws NullPointerException if properties is null
   */
  public SchedulerCapabilityConfiguration(final Map<String, String> properties) {
    // Using pattern matching for instanceof to validate the parameter
    // This replaces the previous checkNotNull call with a more expressive validation
    if (!(properties instanceof Map<String, String> map)) {
      throw new NullPointerException("Properties map cannot be null");
    }
    
    // Demonstrate Java 21 Record Pattern for Map.Entry processing
    // Even though this class doesn't use any specific properties, this shows the pattern
    // that would be used for property extraction in more complex configurations
    for (Entry<String, String> entry : map.entrySet()) {
      // Using record pattern to destructure the Map.Entry in a type-safe way
      if (entry instanceof Entry<String, String>(var key, var value)) {
        // In a real implementation, specific properties would be processed here
        // For example: if ("someKey".equals(key)) { processValue(value); }
        log.debug("Scheduler capability configuration property: {}={}", key, value);
      }
    }
    
    // No specific configuration needed for this capability
  }
}