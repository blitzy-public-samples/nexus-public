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
package org.sonatype.nexus.security.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.event.EventHelper;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.distributed.event.service.api.EventType;
import org.sonatype.nexus.distributed.event.service.api.common.AuthorizationChangedDistributedEvent;
import org.sonatype.nexus.distributed.event.service.api.common.PrivilegeConfigurationEvent;
import org.sonatype.nexus.distributed.event.service.api.common.RoleConfigurationEvent;
import org.sonatype.nexus.security.authz.AuthorizationConfigurationChanged;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.PrivilegeCreatedEvent;
import org.sonatype.nexus.security.privilege.PrivilegeDeletedEvent;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.PrivilegeUpdatedEvent;
import org.sonatype.nexus.security.role.NoSuchRoleException;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleCreatedEvent;
import org.sonatype.nexus.security.role.RoleDeletedEvent;
import org.sonatype.nexus.security.role.RoleUpdatedEvent;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.distributed.event.service.api.EventType.CREATED;
import static org.sonatype.nexus.distributed.event.service.api.EventType.DELETED;
import static org.sonatype.nexus.distributed.event.service.api.EventType.UPDATED;
import static org.sonatype.nexus.security.internal.DefaultRealmConstants.DEFAULT_REALM_NAME;
import static org.sonatype.nexus.security.internal.DefaultRealmConstants.DEFAULT_USER_SOURCE;

/**
 * Default {@link AuthorizationManager}.
 */
@Named(DEFAULT_USER_SOURCE)
@Singleton
public class AuthorizationManagerImpl
    extends ComponentSupport
    implements AuthorizationManager, EventAware
{
  public static final String SOURCE = DEFAULT_USER_SOURCE;

  private final SecurityConfigurationManager configuration;

  private final EventManager eventManager;

  private final List<PrivilegeDescriptor> privilegeDescriptors;
  
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  @Inject
  public AuthorizationManagerImpl(
      final SecurityConfigurationManager configuration,
      final EventManager eventManager,
      final List<PrivilegeDescriptor> privilegeDescriptors)
  {
    this.configuration = configuration;
    this.eventManager = eventManager;
    this.privilegeDescriptors = checkNotNull(privilegeDescriptors);
  }

  @Override
  public String getSource() {
    return DEFAULT_USER_SOURCE;
  }

  @Override
  public String getRealmName() {
    return DEFAULT_REALM_NAME;
  }

  private Role convert(final CRole source) {
    // Using pattern matching for improved type checking
    if (source instanceof CRole cRole) {
      Role target = new Role();
      target.setRoleId(cRole.getId());
      target.setVersion(cRole.getVersion());
      target.setName(cRole.getName());
      target.setSource(DEFAULT_USER_SOURCE);
      target.setDescription(cRole.getDescription());
      target.setReadOnly(cRole.isReadOnly());
      target.setPrivileges(Sets.newHashSet(cRole.getPrivileges()));
      target.setRoles(Sets.newHashSet(cRole.getRoles()));
      return target;
    }
    return null; // This should never happen as we're checking the type above
  }

  private CRole convert(final Role source) {
    // Using pattern matching for improved type checking
    if (source instanceof Role role) {
      CRole target = configuration.newRole();
      target.setId(role.getRoleId());
      target.setVersion(role.getVersion());
      target.setName(role.getName());
      target.setDescription(role.getDescription());
      target.setReadOnly(role.isReadOnly());

      // Using pattern matching with null check
      if (var privileges = role.getPrivileges()) {
        target.setPrivileges(Sets.newHashSet(privileges));
      }
      else {
        target.setPrivileges(Sets.<String>newHashSet());
      }

      // Using pattern matching with null check
      if (var roles = role.getRoles()) {
        target.setRoles(Sets.newHashSet(roles));
      }
      else {
        target.setRoles(Sets.<String>newHashSet());
      }

      return target;
    }
    return null; // This should never happen as we're checking the type above
  }

  private CPrivilege convert(final Privilege source) {
    // Using pattern matching for improved type checking
    if (source instanceof Privilege privilege) {
      CPrivilege target = configuration.newPrivilege();
      target.setId(privilege.getId());
      target.setVersion(privilege.getVersion());
      target.setName(privilege.getName());
      target.setDescription(privilege.getDescription());
      target.setReadOnly(privilege.isReadOnly());
      target.setType(privilege.getType());
      
      // Using pattern matching with null check
      if (var properties = privilege.getProperties()) {
        target.setProperties(Maps.newHashMap(properties));
      }

      return target;
    }
    return null; // This should never happen as we're checking the type above
  }

  private Privilege convert(final CPrivilege source) {
    Privilege target = new Privilege();
    target.setId(source.getId());
    target.setVersion(source.getVersion());
    target.setName(source.getName() == null ? source.getId() : source.getName());
    target.setDescription(source.getDescription());
    target.setReadOnly(source.isReadOnly());
    target.setType(source.getType());
    target.setProperties(Maps.newHashMap(source.getProperties()));

    // expose permission string representation
    // Using pattern matching for improved type checking
    if (var descriptor = descriptor(source.getType())) {
      target.setPermission(descriptor.createPermission(source));
    }

    return target;
  }

  @Nullable
  private PrivilegeDescriptor descriptor(final String type) {
    for (PrivilegeDescriptor descriptor : privilegeDescriptors) {
      // Using pattern matching for improved type checking
      if (descriptor instanceof PrivilegeDescriptor pd && type.equals(pd.getType())) {
        return pd;
      }
    }
    return null;
  }

  // //
  // ROLE CRUDS
  // //

  @Override
  public Set<Role> listRoles() {
    lock.readLock().lock();
    try {
      Set<Role> roles = new HashSet<Role>();
      List<CRole> secRoles = this.configuration.listRoles();

      for (CRole cRole : secRoles) {
        // Using pattern matching for improved type checking
        if (var role = this.convert(cRole)) {
          roles.add(role);
        }
      }

      return roles;
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Role getRole(final String roleId) throws NoSuchRoleException {
    lock.readLock().lock();
    try {
      return this.convert(this.configuration.readRole(roleId));
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Set<Role> searchRoles(final String query) {
    return listRoles();
  }

  @Override
  public Role addRole(final Role role) {
    lock.writeLock().lock();
    try {
      // the roleId of the secRole might change, so we need to keep the reference
      CRole secRole = this.convert(role);

      configuration.createRole(secRole);

      log.info("Added role {}", role.getName());

      fireRoleCreatedEvent(role);
      fireRoleConfigurationDistributedEvent(role.getRoleId(), CREATED);

      // notify any listeners that the config changed
      fireAuthorizationChangedEvent();

      return this.convert(secRole);
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Role updateRole(final Role role) throws NoSuchRoleException {
    lock.writeLock().lock();
    try {
      CRole secRole = this.convert(role);

      configuration.updateRole(secRole);

      fireRoleUpdatedEvent(role);
      fireRoleConfigurationDistributedEvent(role.getRoleId(), UPDATED);

      // notify any listeners that the config changed
      fireAuthorizationChangedEvent();

      return this.convert(secRole);
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void deleteRole(final String roleId) throws NoSuchRoleException {
    lock.writeLock().lock();
    try {
      Role role = getRole(roleId);
      configuration.deleteRole(roleId);

      log.info("Removed role {}", role.getName());
      fireRoleDeletedEvent(role);
      fireRoleConfigurationDistributedEvent(roleId, DELETED);

      // notify any listeners that the config changed
      fireAuthorizationChangedEvent();
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  // //
  // PRIVILEGE CRUDS
  // //

  @Override
  public Set<Privilege> listPrivileges() {
    lock.readLock().lock();
    try {
      Set<Privilege> privileges = new HashSet<Privilege>();
      List<CPrivilege> secPrivs = this.configuration.listPrivileges();

      // Using Java 21 stream API with pattern matching
      return secPrivs.stream()
          .map(this::convert)
          .collect(Collectors.toSet());
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Privilege getPrivilege(final String privilegeId) throws NoSuchPrivilegeException {
    lock.readLock().lock();
    try {
      return this.convert(this.configuration.readPrivilege(privilegeId));
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Privilege getPrivilegeByName(final String privilegeName) throws NoSuchPrivilegeException {
    lock.readLock().lock();
    try {
      return this.convert(this.configuration.readPrivilegeByName(privilegeName));
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public List<Privilege> getPrivileges(final Set<String> privilegeIds) {
    lock.readLock().lock();
    try {
      List<CPrivilege> privileges = configuration.readPrivileges(privilegeIds);
      // Using Java 21 stream API with pattern matching and improved collector
      return privileges.stream()
          .map(this::convert)
          .collect(Collectors.toList());
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Privilege addPrivilege(final Privilege privilege) {
    lock.writeLock().lock();
    try {
      final CPrivilege secPriv = this.convert(privilege);
      configuration.createPrivilege(secPriv);

      log.info("Added privilege {}", privilege.getName());

      firePrivilegeCreatedEvent(privilege);
      firePrivilegeConfigurationDistributedEvent(privilege.getId(), CREATED);

      // notify any listeners that the config changed
      fireAuthorizationChangedEvent();

      return this.convert(secPriv);
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Privilege updatePrivilege(final Privilege privilege) throws NoSuchPrivilegeException {
    lock.writeLock().lock();
    try {
      final CPrivilege secPriv = this.convert(privilege);

      configuration.updatePrivilege(secPriv);

      firePrivilegeUpdatedEvent(privilege);
      firePrivilegeConfigurationDistributedEvent(privilege.getId(), UPDATED);

      // notify any listeners that the config changed
      fireAuthorizationChangedEvent();

      return this.convert(secPriv);
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Privilege updatePrivilegeByName(final Privilege privilege) throws NoSuchPrivilegeException {
    lock.writeLock().lock();
    try {
      final CPrivilege toUpdate = this.convert(privilege);

      configuration.updatePrivilegeByName(toUpdate);

      firePrivilegeUpdatedEvent(privilege);
      firePrivilegeConfigurationDistributedEvent(privilege.getId(), UPDATED);

      // notify any listeners that the config changed
      fireAuthorizationChangedEvent();

      return this.convert(toUpdate);
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void deletePrivilege(final String privilegeId) throws NoSuchPrivilegeException {
    lock.writeLock().lock();
    try {
      Privilege privilege = getPrivilege(privilegeId);
      configuration.deletePrivilege(privilegeId);

      log.info("Removed privilege {}", privilege.getName());
      firePrivilegeDeletedEvent(privilege);
      firePrivilegeConfigurationDistributedEvent(privilegeId, DELETED);

      // notify any listeners that the config changed
      fireAuthorizationChangedEvent();
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void deletePrivilegeByName(final String privilegeName) throws NoSuchPrivilegeException {
    lock.writeLock().lock();
    try {
      Privilege privilege = getPrivilegeByName(privilegeName);
      configuration.deletePrivilegeByName(privilegeName);

      log.info("Removed privilege by name {}", privilegeName);
      firePrivilegeDeletedEvent(privilege);
      firePrivilegeConfigurationDistributedEvent(privilegeName, DELETED);

      // notify any listeners that the config changed
      fireAuthorizationChangedEvent();
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public boolean supportsWrite() {
    return true;
  }

  @Subscribe
  public void onRoleConfigurationEvent(final RoleConfigurationEvent event) {
    if (!EventHelper.isReplicating()) {
      return;
    }
    checkNotNull(event);

    String roleId = event.getRoleId();
    EventType eventType = event.getEventType();

    log.debug("Consume distributed RoleConfigurationEvent: roleId={}, type={}", roleId, eventType);

    switch (eventType) {
      case CREATED -> handleRoleCreatedDistributedEvent(roleId);
      case UPDATED -> handleRoleUpdatedDistributedEvent(roleId);
      case DELETED -> handleRoleDeletedDistributedEvent(roleId);
    }
  }

  @Subscribe
  public void onPrivilegeConfigurationEvent(final PrivilegeConfigurationEvent event) {
    if (!EventHelper.isReplicating()) {
      return;
    }
    checkNotNull(event);

    String privilegeId = event.getPrivilegeId();
    EventType eventType = event.getEventType();

    log.debug("Consume distributed PrivilegeConfigurationEvent: privilegeId={}, type={}", privilegeId, eventType);

    switch (eventType) {
      case CREATED -> handlePrivilegeCreatedDistributedEvent(privilegeId);
      case UPDATED -> handlePrivilegeUpdatedDistributedEvent(privilegeId);
      case DELETED -> handlePrivilegeDeletedDistributedEvent(privilegeId);
    }
  }

  // role DES events handlers
  private void handleRoleCreatedDistributedEvent(final String roleId) {
    try {
      Role role = getRole(roleId);
      fireRoleCreatedEvent(role);
    }
    catch (NoSuchRoleException e) {
      log.error("Could not load role={} while handling distributed event", roleId, e);
    }
  }

  private void handleRoleUpdatedDistributedEvent(final String roleId) {
    try {
      Role role = getRole(roleId);
      fireRoleUpdatedEvent(role);
    }
    catch (NoSuchRoleException e) {
      log.error("Could not load role={} while handling distributed event", roleId, e);
    }
  }

  private void handleRoleDeletedDistributedEvent(final String roleId) {
    try {
      Role role = getRole(roleId);
      fireRoleDeletedEvent(role);
    }
    catch (NoSuchRoleException e) {
      log.error("Could not load role={} while handling distributed event", roleId);
    }
  }

  // privilege DES event handlers
  private void handlePrivilegeCreatedDistributedEvent(final String privilegeId) {
    try {
      Privilege privilege = getPrivilege(privilegeId);
      firePrivilegeCreatedEvent(privilege);
    }
    catch (NoSuchPrivilegeException e) {
      log.error("Could not load privilege={} while handling distributed event", privilegeId);
    }
  }

  private void handlePrivilegeUpdatedDistributedEvent(final String privilegeId) {
    try {
      Privilege privilege = getPrivilege(privilegeId);
      firePrivilegeUpdatedEvent(privilege);
    }
    catch (NoSuchPrivilegeException e) {
      log.error("Could not load privilege={} while handling distributed event", privilegeId);
    }
  }

  private void handlePrivilegeDeletedDistributedEvent(final String privilegeId) {
    try {
      Privilege privilege = getPrivilege(privilegeId);
      firePrivilegeDeletedEvent(privilege);
    }
    catch (NoSuchPrivilegeException e) {
      log.error("Could not load privilege={} while handling distributed event", privilegeId);
    }
  }

  private void fireAuthorizationChangedEvent() {
    eventManager.post(new AuthorizationConfigurationChanged());
    eventManager.post(new AuthorizationChangedDistributedEvent());
  }

  private void fireRoleCreatedEvent(final Role role) {
    eventManager.post(new RoleCreatedEvent(role));
  }

  private void fireRoleUpdatedEvent(final Role role) {
    eventManager.post(new RoleUpdatedEvent(role));
  }

  private void fireRoleDeletedEvent(final Role role) {
    eventManager.post(new RoleDeletedEvent(role));
  }

  private void firePrivilegeCreatedEvent(final Privilege privilege) {
    eventManager.post(new PrivilegeCreatedEvent(privilege));
  }

  private void firePrivilegeUpdatedEvent(final Privilege privilege) {
    eventManager.post(new PrivilegeUpdatedEvent(privilege));
  }

  private void firePrivilegeDeletedEvent(final Privilege privilege) {
    eventManager.post(new PrivilegeDeletedEvent(privilege));
  }

  private void fireRoleConfigurationDistributedEvent(final String roleId, final EventType eventType) {
    log.debug("Distribute event: roleId={}, type={}", roleId, eventType);

    eventManager.post(new RoleConfigurationEvent(roleId, eventType));
  }

  private void firePrivilegeConfigurationDistributedEvent(final String privilegeId, final EventType eventType) {
    log.debug("Distribute event: privilegeId={}, type={}", privilegeId, eventType);

    eventManager.post(new PrivilegeConfigurationEvent(privilegeId, eventType));
  }
}