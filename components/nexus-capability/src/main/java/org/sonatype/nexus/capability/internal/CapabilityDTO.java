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

import org.sonatype.nexus.capability.Capability;
import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityReference;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Data Transfer Object for Capability information.
 * Uses Java 21's Record Patterns for efficient data handling.
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
   * Creates a new DTO from a capability reference.
   * Uses Pattern Matching for type checking and extraction.
   *
   * @param reference the capability reference (must not be null)
   */
  public CapabilityDTO(final CapabilityReference reference) {
    checkNotNull(reference);
    
    // Use Pattern Matching to extract context and capability in one step
    if (reference instanceof CapabilityReference(var context, var capability)) {
      checkNotNull(context);
      
      id = context.id().toString();
      type = context.type().toString();
      enabled = context.isEnabled();
      notes = context.notes();
      properties = processProperties(context.properties(), capability);
    }
    else {
      // Fallback for backward compatibility
      CapabilityContext context = checkNotNull(reference.context());
      
      id = context.id().toString();
      type = context.type().toString();
      enabled = context.isEnabled();
      notes = context.notes();
      properties = CapabilityResource.filterProperties(context.properties(), reference.capability());
    }
  }
  
  /**
   * Process properties using Record Patterns when handling capability configuration data.
   * This method provides a more efficient way to filter properties using Java 21 features.
   *
   * @param contextProperties the properties from the context
   * @param capability the capability instance
   * @return filtered properties map
   */
  private Map<String, String> processProperties(final Map<String, String> contextProperties, final Capability capability) {
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