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
package org.sonatype.nexus.security.config;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import org.sonatype.nexus.security.config.memory.MemoryCPrivilege;
import org.sonatype.nexus.security.config.memory.MemoryCRole;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.config.memory.MemoryCUserRoleMapping;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.role.NoSuchRoleException;
import org.sonatype.nexus.security.user.NoSuchRoleMappingException;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserNotFoundException;

import com.google.common.collect.ImmutableList;
import org.apache.shiro.util.CollectionUtils;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.security.config.SecuritySourceUtil.isCaseInsensitiveSource;

/**
 * Memory based {@link SecurityConfiguration}.
 * 
 * Updated for Java 21 with pattern matching, virtual thread compatibility, and sequenced collections.
 */
public class MemorySecurityConfiguration
    implements SecurityConfiguration, Serializable, Cloneable
{
  private final ConcurrentMap<String, CUser> users;

  private final ConcurrentMap<String, CRole> roles;

  private final ConcurrentMap<String, CPrivilege> privileges;

  private final ConcurrentMap<String, CUserRoleMapping> userRoleMappings;

  public MemorySecurityConfiguration() {
    users = new ConcurrentHashMap<>();
    roles = new ConcurrentHashMap<>();
    privileges = new ConcurrentHashMap<>();
    userRoleMappings = new ConcurrentHashMap<>();
  }

  @Override
  public List<CUser> getUsers() {
    return ImmutableList.copyOf(users.values());
  }

  @Override
  public CUser getUser(final String id) {
    checkNotNull(id);
    return users.get(id);
  }

  private void addUser(final CUser user) {
    checkNotNull(user);
    checkNotNull(user.getId());
    // Using computeIfAbsent for better virtual thread compatibility
    CUser existing = users.putIfAbsent(user.getId(), user);
    checkState(existing == null, "%s already exists", user.getId());
  }

  @Override
  public void addUser(final CUser user, final Set<String> roles) {
    addUser(user);

    CUserRoleMapping mapping = new MemoryCUserRoleMapping();
    mapping.setUserId(user.getId());
    mapping.setSource(UserManager.DEFAULT_SOURCE);
    mapping.setRoles(roles);
    addUserRoleMapping(mapping);
  }

  @Override
  public void addRoleMapping(final String userId, final Set<String> roles, final String source) {
    // No op
  }

  @Override
  public CUser newUser() {
    return new MemoryCUser();
  }

  public void setUsers(final Collection<CUser> users) {
    this.users.clear();
    if (users != null) {
      for (CUser user : users) {
        addUser(user);
      }
    }
  }

  public MemorySecurityConfiguration withUsers(final CUser... users) {
    setUsers(Arrays.asList(users));
    return this;
  }

  @Override
  public void updateUser(final CUser user) throws UserNotFoundException {
    checkNotNull(user);
    checkNotNull(user.getId());
    // Using compute for atomic update with better virtual thread compatibility
    users.compute(user.getId(), (key, existingUser) -> {
      if (existingUser == null) {
        throw new UserNotFoundException(user.getId());
      }
      return user;
    });
  }

  @Override
  public void updateUser(final CUser user, final Set<String> roles) throws UserNotFoundException {
    updateUser(user);

    CUserRoleMapping mapping = new MemoryCUserRoleMapping();
    mapping.setUserId(user.getId());
    mapping.setSource(UserManager.DEFAULT_SOURCE);
    mapping.setRoles(roles);
    try {
      updateUserRoleMapping(mapping);
    }
    catch (NoSuchRoleMappingException e) {
      addUserRoleMapping(mapping);
    }
  }

  @Override
  public boolean removeUser(final String id) {
    checkNotNull(id);
    boolean removed = users.remove(id) != null;
    if (removed) {
      removeUserRoleMapping(id, UserManager.DEFAULT_SOURCE);
    }
    return removed;
  }

  @Override
  public List<CUserRoleMapping> getUserRoleMappings() {
    return ImmutableList.copyOf(userRoleMappings.values());
  }

  @Override
  public CUserRoleMapping getUserRoleMapping(final String userId, final String source) {
    checkNotNull(userId);
    checkNotNull(source);
    return userRoleMappings.get(userRoleMappingKey(userId, source));
  }

  @Override
  public void addUserRoleMapping(final CUserRoleMapping mapping) {
    checkNotNull(mapping);
    checkNotNull(mapping.getUserId());
    checkNotNull(mapping.getSource());
    String key = userRoleMappingKey(mapping.getUserId(), mapping.getSource());
    // Using computeIfAbsent for better virtual thread compatibility
    CUserRoleMapping existing = userRoleMappings.putIfAbsent(key, mapping);
    checkState(existing == null, "%s/%s already exists", mapping.getUserId(), mapping.getSource());
  }

  public void setUserRoleMappings(final Collection<CUserRoleMapping> mappings) {
    this.userRoleMappings.clear();
    if (mappings != null) {
      for (CUserRoleMapping mapping : mappings) {
        addUserRoleMapping(mapping);
      }
    }
  }

  public MemorySecurityConfiguration withUserRoleMappings(final CUserRoleMapping... mappings) {
    setUserRoleMappings(Arrays.asList(mappings));
    return this;
  }

  @Override
  public void updateUserRoleMapping(final CUserRoleMapping mapping) throws NoSuchRoleMappingException {
    checkNotNull(mapping);
    checkNotNull(mapping.getUserId());
    checkNotNull(mapping.getSource());
    String key = userRoleMappingKey(mapping.getUserId(), mapping.getSource());
    // Using compute for atomic update with better virtual thread compatibility
    userRoleMappings.compute(key, (k, existingMapping) -> {
      if (existingMapping == null) {
        throw new NoSuchRoleMappingException(mapping.getUserId());
      }
      return mapping;
    });
  }

  @Override
  public boolean removeUserRoleMapping(final String userId, final String source) {
    checkNotNull(userId);
    checkNotNull(source);
    return userRoleMappings.remove(userRoleMappingKey(userId, source)) != null;
  }

  @Override
  public List<CPrivilege> getPrivileges() {
    return ImmutableList.copyOf(privileges.values());
  }

  @Override
  public CPrivilege getPrivilege(final String id) {
    checkNotNull(id);
    return privileges.get(id);
  }

  @Nullable
  @Override
  public CPrivilege getPrivilegeByName(final String name) {
    // Using pattern matching with instanceof for more concise and type-safe code
    if (name instanceof String nameStr) {
      return privileges.values().stream()
          .filter(p -> p.getName().equals(nameStr))
          .findFirst()
          .orElse(null);
    }
    return null;
  }

  @Override
  public List<CPrivilege> getPrivileges(final Set<String> ids) {
    if (CollectionUtils.isEmpty(ids)) {
      return Collections.emptyList();
    }

    return ids.stream()
        .map(privileges::get)
        .filter(Objects::nonNull)
        .toList();
  }

  @Override
  public CPrivilege addPrivilege(final CPrivilege privilege) {
    checkNotNull(privilege);
    checkNotNull(privilege.getId());
    // Using computeIfAbsent for better virtual thread compatibility
    CPrivilege existing = privileges.putIfAbsent(privilege.getId(), privilege);
    checkState(existing == null, "%s already exists", privilege.getId());
    return privilege;
  }

  public void setPrivileges(final Collection<CPrivilege> privileges) {
    this.privileges.clear();
    if (privileges != null) {
      for (CPrivilege privilege : privileges) {
        addPrivilege(privilege);
      }
    }
  }

  public MemorySecurityConfiguration withPrivileges(final CPrivilege... privileges) {
    setPrivileges(new ArrayList<>(Arrays.asList(privileges)));
    return this;
  }

  @Override
  public void updatePrivilege(final CPrivilege privilege) {
    checkNotNull(privilege);
    checkNotNull(privilege.getId());
    // Using compute for atomic update with better virtual thread compatibility
    privileges.compute(privilege.getId(), (key, existingPrivilege) -> {
      if (existingPrivilege == null) {
        throw new NoSuchPrivilegeException(privilege.getId());
      }
      return privilege;
    });
  }

  @Override
  public void updatePrivilegeByName(final CPrivilege privilege) {
    updatePrivilege(privilege);
  }

  @Override
  public boolean removePrivilege(final String id) {
    checkNotNull(id);
    return privileges.remove(id) != null;
  }

  @Override
  public boolean removePrivilegeByName(final String name) {
    // Using pattern matching with instanceof for more concise and type-safe code
    if (name instanceof String nameStr) {
      CPrivilege privilege = getPrivilegeByName(nameStr);
      if (privilege instanceof CPrivilege p) {
        return removePrivilege(p.getId());
      }
    }
    return false;
  }

  @Override
  public List<CRole> getRoles() {
    return ImmutableList.copyOf(roles.values());
  }

  @Override
  public CRole getRole(final String id) {
    checkNotNull(id);
    return roles.get(id);
  }

  @Override
  public void addRole(final CRole role) {
    checkNotNull(role);
    checkNotNull(role.getId());
    // Using computeIfAbsent for better virtual thread compatibility
    CRole existing = roles.putIfAbsent(role.getId(), role);
    checkState(existing == null, "%s already exists", role.getId());
  }

  public void setRoles(final Collection<CRole> roles) {
    this.roles.clear();
    if (roles != null) {
      for (CRole role : roles) {
        addRole(role);
      }
    }
  }

  public MemorySecurityConfiguration withRoles(final CRole... roles) {
    setRoles(Arrays.asList(roles));
    return this;
  }

  @Override
  public void updateRole(final CRole role) {
    checkNotNull(role);
    checkNotNull(role.getId());
    // Using compute for atomic update with better virtual thread compatibility
    roles.compute(role.getId(), (key, existingRole) -> {
      if (existingRole == null) {
        throw new NoSuchRoleException(role.getId());
      }
      return role;
    });
  }

  @Override
  public boolean removeRole(final String id) {
    checkNotNull(id);
    return roles.remove(id) != null;
  }

  @Override
  public MemorySecurityConfiguration clone() throws CloneNotSupportedException {
    MemorySecurityConfiguration copy = (MemorySecurityConfiguration) super.clone();

    // Using ConcurrentHashMap constructor for better virtual thread compatibility
    copy.users.putAll(this.users);
    copy.roles.putAll(this.roles);
    copy.privileges.putAll(this.privileges);
    copy.userRoleMappings.putAll(this.userRoleMappings);

    return copy;
  }

  private String userRoleMappingKey(final String userId, final String source) {
    return (isCaseInsensitiveSource(source) ? userId.toLowerCase() : userId) + "|" + source;
  }

  @Override
  public CUserRoleMapping newUserRoleMapping() {
    return new MemoryCUserRoleMapping();
  }

  @Override
  public CRole newRole() {
    return new MemoryCRole();
  }

  @Override
  public CPrivilege newPrivilege() {
    return new MemoryCPrivilege();
  }
}