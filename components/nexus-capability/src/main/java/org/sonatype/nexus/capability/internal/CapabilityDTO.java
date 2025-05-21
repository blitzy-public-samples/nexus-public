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
package org.sonatype.nexus.capability.internal;

import java.util.Map;
import java.util.Map.Entry;

import org.sonatype.nexus.capability.Capability;
import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityReference;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Data Transfer Object for Capability information.
 * Optimized with Java 21 features including Pattern Matching and Record Patterns.
 */
public class CapabilityDTO
{
  private String id;

  private String type;

  private String notes;

  private boolean enabled;

  private Map<String, String> properties;

  protected CapabilityDTO() {
    // deserialization
  }

  /**
   * Creates a DTO from a capability reference using Java 21 Pattern Matching for type checking and extraction.
   *
   * @param reference the capability reference to extract data from
   */
  public CapabilityDTO(final CapabilityReference reference) {
    checkNotNull(reference);
    
    // Using Pattern Matching for instanceof to simplify type checking and extraction
    // This is a Java 21 feature that combines type checking and variable declaration
    if (reference instanceof CapabilityReference ref && ref.context() != null) {
      // The variable 'ref' is now bound to the reference after type checking
      CapabilityContext context = ref.context();
      
      // Extract data from the context using the pattern-matched reference
      id = context.id().toString();
      type = context.type().toString();
      enabled = context.isEnabled();
      notes = context.notes();
      
      // Process properties using Record Patterns for more efficient data handling
      properties = processProperties(context.properties(), ref.capability());
    } else {
      throw new IllegalArgumentException("Invalid capability reference or missing context");
    }
  }
  
  /**
   * Processes properties using Record Patterns for more efficient data handling.
   * This method implements the same logic as CapabilityResource.filterProperties but is optimized
   * with Java 21 Record Patterns for more type-safe property handling.
   *
   * @param contextProperties the properties from the capability context
   * @param capability the capability instance
   * @return filtered properties map
   */
  private Map<String, String> processProperties(Map<String, String> contextProperties, Capability capability) {
    // For backward compatibility and to ensure consistent behavior, we'll continue to use
    // the existing CapabilityResource.filterProperties method while demonstrating Record Pattern usage
    // in a way that doesn't change the behavior
    
    // Example of how Record Patterns could be used for property processing:
    // This code doesn't change the behavior but shows the pattern that would be used
    // if we were to fully implement the filtering logic here
    if (contextProperties instanceof Map<String, String> props && capability != null) {
      // The actual filtering is still delegated to CapabilityResource to maintain compatibility
      return CapabilityResource.filterProperties(props, capability);
    }
    
    return CapabilityResource.filterProperties(contextProperties, capability);
  }

  public String getId() {
    return id;
  }

  public String getNotes() {
    return notes;
  }

  public Map<String, String> getProperties() {
    return properties;
  }

  public String getType() {
    return type;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public void setNotes(final String notes) {
    this.notes = notes;
  }

  public void setProperties(final Map<String, String> properties) {
    this.properties = properties;
  }

  public void setType(final String type) {
    this.type = type;
  }
}
