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
package org.sonatype.nexus.internal.security.model;

import java.util.Set;
import java.util.HashSet;

import org.sonatype.nexus.common.entity.HasStringId;
import org.sonatype.nexus.security.config.CRole;

/**
 * {@link CRole} data implementation that provides data storage and manipulation.
 * Updated for Java 21 with enhanced pattern matching and collection handling.
 *
 * @since 3.21
 */
public class CRoleData
    implements HasStringId, CRole
{
  private String description;

  private String id;

  private String name;

  private Set<String> privileges;

  private boolean readOnly = false;

  private Set<String> roles;

  private int version = 1;

  @Override
  public void addPrivilege(final String string) {
    switch (string) {
      case null -> { /* Do nothing for null values */ }
      case String s -> getPrivileges().add(s);
    }
  }

  @Override
  public void addRole(final String string) {
    switch (string) {
      case null -> { /* Do nothing for null values */ }
      case String s -> getRoles().add(s);
    }
  }

  @Override
  public String getDescription() {
    return this.description;
  }

  @Override
  public String getId() {
    return this.id;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public Set<String> getPrivileges() {
    if (this.privileges == null) {
      this.privileges = new HashSet<>();
    }

    return this.privileges;
  }

  @Override
  public Set<String> getRoles() {
    if (this.roles == null) {
      this.roles = new HashSet<>();
    }

    return this.roles;
  }

  @Override
  public int getVersion() {
    return version;
  }

  @Override
  public boolean isReadOnly() {
    return this.readOnly;
  }

  @Override
  public void removePrivilege(final String string) {
    switch (string) {
      case null -> { /* Do nothing for null values */ }
      case String s -> getPrivileges().remove(s);
    }
  }

  @Override
  public void removeRole(final String string) {
    switch (string) {
      case null -> { /* Do nothing for null values */ }
      case String s -> getRoles().remove(s);
    }
  }

  @Override
  public void setDescription(final String description) {
    this.description = description;
  }

  @Override
  public void setId(final String id) {
    this.id = id;
  }

  @Override
  public void setName(final String name) {
    this.name = name;
  }

  @Override
  public void setPrivileges(final Set<String> privileges) {
    this.privileges = privileges;
  }

  @Override
  public void setReadOnly(final boolean readOnly) {
    this.readOnly = readOnly;
  }

  @Override
  public void setRoles(final Set<String> roles) {
    this.roles = roles;
  }

  @Override
  public void setVersion(final int version) {
    this.version = Math.max(version, 1);
  }

  @Override
  public CRoleData clone() {
    try {
      CRoleData copy = (CRoleData) super.clone();
      
      // Create defensive copies of mutable fields using Java 21 features
      copy.privileges = (this.privileges != null) ? new HashSet<>(this.privileges) : null;
      copy.roles = (this.roles != null) ? new HashSet<>(this.roles) : null;
      
      return copy;
    }
    catch (CloneNotSupportedException e) {
      throw new RuntimeException("Failed to clone CRoleData", e);
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{"
        + "id='" + id + '\''
        + ", name='" + name + '\''
        + ", description='" + description + '\''
        + ", privileges=" + privileges
        + ", roles=" + roles
        + ", readOnly=" + readOnly
        + ", version='" + version + '\''
        + '}';
  }
}