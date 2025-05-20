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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
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
 * Implementation of the SecurityApi interface that provides security provisioning capabilities.
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
  
  // Virtual thread executor for handling concurrent security operations
  // This provides lightweight thread management for I/O-bound security operations
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public SecurityApiImpl(final AnonymousManager anonymousManager, final SecuritySystem securitySystem) {
    this.anonymousManager = anonymousManager;
    this.securitySystem = securitySystem;
  }

  @Override
  public AnonymousConfiguration setAnonymousAccess(final boolean enabled) {
    // Use virtualThreadExecutor for this I/O-bound operation to improve concurrency
    try {
      return virtualThreadExecutor.submit(() -> {
        AnonymousConfiguration anonymousConfiguration = anonymousManager.getConfiguration();

        if (!anonymousManager.isConfigured() || anonymousConfiguration.isEnabled() != enabled) {
          anonymousConfiguration.setEnabled(enabled);
          anonymousManager.setConfiguration(anonymousConfiguration);
          log.info(STR."Anonymous access configuration updated to: \{anonymousConfiguration}");
        }
        else {
          log.info(STR."Anonymous access configuration unchanged at: \{anonymousConfiguration}");
        }
        return anonymousConfiguration;
      }).get(); // Wait for the virtual thread to complete
    } catch (Exception e) {
      log.error(STR."Error setting anonymous access: \{e.getMessage()}", e);
      // Fallback to synchronous execution
      AnonymousConfiguration anonymousConfiguration = anonymousManager.getConfiguration();
      anonymousConfiguration.setEnabled(enabled);
      anonymousManager.setConfiguration(anonymousConfiguration);
      return anonymousConfiguration;
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
    // Use pattern matching to determine user status
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
    Role role = new Role();
    role.setRoleId(checkNotNull(id));
    role.setSource(DEFAULT_SOURCE);
    role.setName(checkNotNull(name));
    role.setDescription(description);
    
    // Use Java 21 enhanced collections operations
    role.setPrivileges(Set.copyOf(checkNotNull(privileges)));
    role.setRoles(Set.copyOf(checkNotNull(roles)));

    return securitySystem.getAuthorizationManager(DEFAULT_SOURCE).addRole(role);
  }

  @Override
  public User setUserRoles(
      final String userId,
      final List<String> roleIds) throws UserNotFoundException, NoSuchUserManagerException
  {
    // Fetch user and update roles
    User user = securitySystem.getUser(userId, DEFAULT_SOURCE);
    
    // Use pattern matching to validate user object
    if (user instanceof User userObj && userObj.getUserId().equals(userId)) {
      userObj.setRoles(toIdentifiers(roleIds));
      return securitySystem.updateUser(userObj);
    } else {
      // This should never happen as getUser would throw UserNotFoundException
      // but added for completeness and to demonstrate pattern matching
      throw new UserNotFoundException(userId);
    }
  }

  /**
   * Converts a collection of role IDs to a set of RoleIdentifier objects.
   * Uses Java 21 enhanced collections operations for improved performance.
   *
   * @param roleIds the collection of role IDs to convert
   * @return a set of RoleIdentifier objects
   */
  private static Set<RoleIdentifier> toIdentifiers(final Collection<String> roleIds) {
    checkNotNull(roleIds);
    
    // Use Java 21 enhanced collections operations with pattern matching in lambda
    return roleIds.stream()
        .map(roleId -> {
          // Demonstrate pattern matching in lambda expressions
          return switch (roleId) {
            // When the roleId is not null, create a new RoleIdentifier
            case String id when id != null -> new RoleIdentifier(DEFAULT_SOURCE, id);
            // This case should never happen due to the stream source and checkNotNull,
            // but included to demonstrate pattern matching
            default -> throw new IllegalArgumentException("Role ID cannot be null");
          };
        })
        .collect(Collectors.toUnmodifiableSet());
  }
}