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
package org.sonatype.nexus.plugins.defaultrole.internal;

import java.util.Map;
import java.util.Objects;

import org.sonatype.nexus.capability.CapabilityConfigurationSupport;

/**
 * Simple configuration for {@link DefaultRoleCapability} containing a single roleId.
 * Updated for Java 21 compatibility.
 *
 * @since 3.22
 */
public class DefaultRoleCapabilityConfiguration
    extends CapabilityConfigurationSupport
{
  public static final String P_ROLE = "role";

  private String role;

  /**
   * Creates a new configuration instance from the provided properties map.
   * Uses pattern matching for type-safe extraction of the role property.
   *
   * @param properties the capability properties map containing configuration values
   */
  public DefaultRoleCapabilityConfiguration(final Map<String, String> properties) {
    if (properties instanceof Map<String, String> map) {
      this.role = map.get(P_ROLE);
    }
  }

  /**
   * Returns the configured role ID.
   *
   * @return the role ID or null if not configured
   */
  public String getRole() {
    return role;
  }

  /**
   * Sets the role ID for this configuration.
   *
   * @param role the role ID to set
   */
  public void setRole(final String role) {
    this.role = role;
  }
  
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    
    var that = (DefaultRoleCapabilityConfiguration) o;
    return Objects.equals(role, that.role);
  }
  
  @Override
  public int hashCode() {
    return Objects.hashCode(role);
  }
  
  @Override
  public String toString() {
    return "DefaultRoleCapabilityConfiguration{role='" + role + "'}";
  }
}