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
   * @param properties the configuration properties
   * @throws NullPointerException if properties is null
   */
  public SchedulerCapabilityConfiguration(final Map<String, String> properties) {
    // Use pattern matching for parameter validation
    switch (properties) {
      case null -> throw new NullPointerException("Properties map cannot be null");
      case Map<String, String> map -> {
        // Process properties using record pattern if needed in the future
        // This demonstrates Java 21's pattern matching for Map entries
        // No specific configuration needed currently, but framework is in place
        // for future property handling
        for (var entry : map.entrySet()) {
          // Using record pattern for map entry processing
          if (entry instanceof Entry<String, String>(var key, var value)) {
            // Record pattern allows direct access to key and value without getter methods
            // This approach is more concise and type-safe than traditional methods
            // No specific configuration needed at this time, but ready for future extensions
          }
        }
      }
      default -> throw new IllegalArgumentException("Unexpected properties type");
    }
  }
}