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

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.security.SecurityApi;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousManager;
import org.sonatype.nexus.security.authz.NoSuchAuthorizationManagerException;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserStatus;

import com.google.common.collect.Sets;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.security.user.UserManager.DEFAULT_SOURCE;

/**
 * Implementation of the {@link SecurityApi} interface that provides security provisioning capabilities.
 *
 * @since 3.0
 */
@Named
@Singleton
public class SecurityApiImpl
    extends ComponentSupport
    implements SecurityApi
{
  private final AnonymousManager anonymousManager;

  private final SecuritySystem securitySystem;
  
  // Thread-safety lock for Java 21 concurrency model
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  @Inject
  public SecurityApiImpl(final AnonymousManager anonymousManager, final SecuritySystem securitySystem) {
    this.anonymousManager = anonymousManager;
    this.securitySystem = securitySystem;
  }

  @Override
  public AnonymousConfiguration setAnonymousAccess(final boolean enabled) {
    lock.writeLock().lock();
    try {
      AnonymousConfiguration anonymousConfiguration = anonymousManager.getConfiguration();

      // Using pattern matching with instanceof to check configuration state
      if (anonymousConfiguration instanceof AnonymousConfiguration config && 
          (!anonymousManager.isConfigured() || config.isEnabled() != enabled)) {
        config.setEnabled(enabled);
        anonymousManager.setConfiguration(config);
        log.info("Anonymous access configuration updated to: {}", config);
      }
      else {
        log.info("Anonymous access configuration unchanged at: {}", anonymousConfiguration);
      }
      return anonymousConfiguration;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public User addUser(
      final String id,
      final String firstName,
      final String lastName,
      final String email,
      final boolean active,
      final String password,
      final List<String> roleIds) throws NoSuchUserManagerException
  {
    // Using pattern matching for switch to determine user status
    UserStatus status = switch (active) {
      case true -> UserStatus.active;
      case false -> UserStatus.disabled;
    };
    
    User user = new User();
    user.setUserId(checkNotNull(id));
    user.setSource(DEFAULT_SOURCE);
    user.setFirstName(checkNotNull(firstName));
    user.setLastName(checkNotNull(lastName));
    user.setEmailAddress(checkNotNull(email));
    user.setStatus(status);
    user.setRoles(toIdentifiers(roleIds));

    return securitySystem.addUser(user, password);
  }

  @Override
  public Role addRole(
      final String id,
      final String name,
      final String description,
      final List<String> privileges,
      final List<String> roles) throws NoSuchAuthorizationManagerException
  {
    lock.readLock().lock();
    try {
      Role role = new Role();
      role.setRoleId(checkNotNull(id));
      role.setSource(DEFAULT_SOURCE);
      role.setName(checkNotNull(name));
      role.setDescription(description);
      
      // Using Java 21 pattern matching for switch to handle different collection states
      role.setPrivileges(switch (privileges) {
        case null -> throw new NullPointerException("Privileges cannot be null");
        case List<String> list when list.isEmpty() -> Set.of();
        case List<String> list -> Set.copyOf(list); // Immutable copy using Java 21 Set.copyOf
        default -> Set.of();
      });
      
      role.setRoles(switch (roles) {
        case null -> throw new NullPointerException("Roles cannot be null");
        case List<String> list when list.isEmpty() -> Set.of();
        case List<String> list -> Set.copyOf(list); // Immutable copy using Java 21 Set.copyOf
        default -> Set.of();
      });

      return securitySystem.getAuthorizationManager(DEFAULT_SOURCE).addRole(role);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public User setUserRoles(
      final String userId,
      final List<String> roleIds) throws UserNotFoundException, NoSuchUserManagerException
  {
    lock.writeLock().lock();
    try {
      User user = securitySystem.getUser(userId, DEFAULT_SOURCE);
      if (user != null) {
        user.setRoles(toIdentifiers(roleIds));
        return securitySystem.updateUser(user);
      } else {
        throw new UserNotFoundException(userId);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Converts a collection of role IDs to a set of {@link RoleIdentifier} objects.
   * Uses Java 21 enhanced stream operations and pattern matching for improved type checking.
   * 
   * @param roleIds the collection of role IDs to convert
   * @return a set of role identifiers
   */
  private static Set<RoleIdentifier> toIdentifiers(final Collection<String> roleIds) {
    // Using Java 21 enhanced collections and pattern matching
    if (roleIds instanceof Collection<String> collection) {
      return collection.stream()
          .filter(roleId -> roleId != null && !roleId.isBlank()) // Enhanced filtering with Java 21
          .map(roleId -> new RoleIdentifier(DEFAULT_SOURCE, roleId))
          .collect(Collectors.toUnmodifiableSet()); // Using unmodifiableSet for thread safety
    }
    return Set.of(); // Return empty immutable set if collection is null
  }
}
