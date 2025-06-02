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
package org.sonatype.nexus.security.config.memory;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.SequencedSet;
import java.util.Set;

import org.sonatype.nexus.security.config.CRole;

/**
 * An implementation of {@link CRole} suitable for an in-memory backing store.
 * Updated for Java 21 with Sequenced Collections and modern language features.
 *
 * @since 3.0
 */
public class MemoryCRole
    implements CRole
{
  private String description;

  private String id;

  private String name;

  private SequencedSet<String> privileges;

  private boolean readOnly = false;

  private SequencedSet<String> roles;

  private int version;

  @Override
  public void addPrivilege(final String string) {
    getPrivileges().add(string);
  }

  @Override
  public void addRole(final String string) {
    getRoles().add(string);
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
      this.privileges = new LinkedHashSet<>();
    }

    return this.privileges;
  }

  @Override
  public Set<String> getRoles() {
    if (this.roles == null) {
      this.roles = new LinkedHashSet<>();
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
    getPrivileges().remove(string);
  }

  @Override
  public void removeRole(final String string) {
    getRoles().remove(string);
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
    if (privileges instanceof SequencedSet<String> sequencedPrivileges) {
      this.privileges = sequencedPrivileges;
    } else if (privileges != null) {
      this.privileges = new LinkedHashSet<>(privileges);
    } else {
      this.privileges = null;
    }
  }

  @Override
  public void setReadOnly(final boolean readOnly) {
    this.readOnly = readOnly;
  }

  @Override
  public void setRoles(final Set<String> roles) {
    if (roles instanceof SequencedSet<String> sequencedRoles) {
      this.roles = sequencedRoles;
    } else if (roles != null) {
      this.roles = new LinkedHashSet<>(roles);
    } else {
      this.roles = null;
    }
  }

  @Override
  public void setVersion(final int version) {
    this.version = version;
  }

  public MemoryCRole withDescription(final String description) {
    this.description = description;
    return this;
  }

  public MemoryCRole withId(final String id) {
    this.id = id;
    return this;
  }

  public MemoryCRole withName(final String name) {
    this.name = name;
    return this;
  }

  public MemoryCRole withPrivileges(final String... privileges) {
    this.privileges = LinkedHashSet.of(privileges);
    return this;
  }

  public MemoryCRole withReadOnly(final boolean readOnly) {
    this.readOnly = readOnly;
    return this;
  }

  public MemoryCRole withRoles(final String... roles) {
    this.roles = LinkedHashSet.of(roles);
    return this;
  }

  public MemoryCRole withVersion(final int version) {
    this.version = version;
    return this;
  }

  @Override
  public MemoryCRole clone() {
    try {
      MemoryCRole copy = (MemoryCRole) super.clone();

      // Use pattern matching for type checking and handle each case appropriately
      if (this.privileges != null) {
        copy.privileges = switch (this.privileges) {
          case LinkedHashSet<String> lhs -> new LinkedHashSet<>(lhs);
          case SequencedSet<String> ss -> new LinkedHashSet<>(ss);
          default -> new LinkedHashSet<>(this.privileges);
        };
      }

      if (this.roles != null) {
        copy.roles = switch (this.roles) {
          case LinkedHashSet<String> lhs -> new LinkedHashSet<>(lhs);
          case SequencedSet<String> ss -> new LinkedHashSet<>(ss);
          default -> new LinkedHashSet<>(this.roles);
        };
      }

      return copy;
    }
    catch (CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String toString() {
    return STR."{getClass().getSimpleName()}{
        id='{id}'
        name='{name}'
        description='{description}'
        privileges={privileges}
        roles={roles}
        readOnly={readOnly}
        version={version}
        }";
  }
}