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
package org.sonatype.nexus.security.user;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.sonatype.nexus.security.role.RoleIdentifier;

/**
 * An abstract, in-memory UserManager implementation for unit tests.
 * <p>
 * This implementation is optimized for Java 21 virtual threads by using thread-safe collections
 * and non-blocking operations instead of synchronized methods. It uses ConcurrentHashMap
 * for thread-safe storage and atomic operations to avoid blocking that could pin virtual threads.
 * </p>
 * <p>
 * The implementation provides basic CRUD operations for User objects, as well as listing and
 * searching functionality. It is designed to be extended by concrete test implementations
 * that provide specific user data and source information.
 * </p>
 */
public abstract class MockUserManagerSupport
    extends AbstractUserManager
{
  // Using ConcurrentHashMap for thread-safe operations without synchronization blocks
  // that could pin virtual threads
  private final Map<String, User> users = new ConcurrentHashMap<>();

  /**
   * Add a user to the in-memory store.
   *
   * @param user the user to add
   * @param userId the user ID to use as the key
   * @return the added user
   */
  public User addUser(User user, String userId) {
    // Use putIfAbsent for atomic check-and-put operation
    User existing = users.putIfAbsent(userId, user);
    if (existing != null) {
      throw new IllegalArgumentException("User ID already exists: " + userId);
    }
    return user;
  }

  /**
   * Update a user in the in-memory store.
   *
   * @param user the user to update
   * @return the updated user
   * @throws UserNotFoundException if the user does not exist
   */
  public User updateUser(User user) throws UserNotFoundException {
    String userId = user.getUserId();
    // Use computeIfPresent for atomic check-and-update operation
    User updated = users.computeIfPresent(userId, (key, oldValue) -> user);
    if (updated == null) {
      throw new UserNotFoundException(userId);
    }
    return user;
  }

  /**
   * Delete a user from the in-memory store.
   *
   * @param userId the ID of the user to delete
   * @throws UserNotFoundException if the user does not exist
   */
  public void deleteUser(String userId) throws UserNotFoundException {
    // Use computeIfPresent for atomic check-and-remove operation
    User removed = users.remove(userId);
    if (removed == null) {
      throw new UserNotFoundException(userId);
    }
  }

  /**
   * Get a user by ID.
   *
   * @param userId the ID of the user to retrieve
   * @return the user
   * @throws UserNotFoundException if the user does not exist
   */
  public User getUser(String userId) throws UserNotFoundException {
    User user = users.get(userId);
    if (user == null) {
      throw new UserNotFoundException(userId);
    }
    return user;
  }

  /**
   * Get a user by ID, filtering by roles if specified.
   *
   * @param userId the ID of the user to retrieve
   * @param roleIds the set of role IDs to filter by, or null for no filtering
   * @return the user
   * @throws UserNotFoundException if the user does not exist
   */
  public User getUser(String userId, Set<String> roleIds) throws UserNotFoundException {
    User user = getUser(userId);
    
    // If no role filtering is requested, return the user as is
    if (roleIds == null || roleIds.isEmpty()) {
      return user;
    }
    
    // Create a copy of the user for role filtering
    User filteredUser = new User();
    filteredUser.setUserId(user.getUserId());
    filteredUser.setFirstName(user.getFirstName());
    filteredUser.setLastName(user.getLastName());
    filteredUser.setEmailAddress(user.getEmailAddress());
    filteredUser.setSource(user.getSource());
    filteredUser.setStatus(user.getStatus());
    filteredUser.setReadOnly(user.isReadOnly());
    filteredUser.setVersion(user.getVersion());
    
    // Only include roles that match the requested role IDs
    for (RoleIdentifier role : user.getRoles()) {
      if (roleIds.contains(role.getRoleId())) {
        filteredUser.addRole(role);
      }
    }
    
    return filteredUser;
  }

  /**
   * List all user IDs.
   *
   * @return a set of user IDs
   */
  public Set<String> listUserIds() {
    return Collections.unmodifiableSet(users.keySet());
  }

  /**
   * List all users.
   *
   * @return a set of users
   */
  public Set<User> listUsers() {
    // Use stream API for efficient collection conversion without synchronization
    return users.values().stream()
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Search for users based on criteria.
   *
   * @param criteria the search criteria
   * @return a set of matching users
   */
  public Set<User> searchUsers(UserSearchCriteria criteria) {
    return filterListInMemeory(listUsers(), criteria);
  }

  /**
   * Change a user's password.
   *
   * @param userId the ID of the user
   * @param newPassword the new password
   * @throws UserNotFoundException if the user does not exist
   */
  public void changePassword(String userId, String newPassword) throws UserNotFoundException {
    // No-op for mock implementation
  }

  /**
   * Check if this UserManager is configured.
   *
   * @return always throws UnsupportedOperationException
   */
  public boolean isConfigured() {
    throw new UnsupportedOperationException("Not implemented in mock");
  }

  /**
   * Get the underlying users map for testing purposes.
   * <p>
   * Note: This returns a direct reference to the internal map for testing purposes.
   * Callers should not modify the map directly to avoid thread-safety issues.
   * </p>
   *
   * @return the users map
   */
  protected Map<String, User> getUsers() {
    return users;
  }
  
  /**
   * Determines if this UserManager supports write operations.
   * <p>
   * This implementation always returns true since it supports adding, updating,
   * and deleting users.
   * </p>
   *
   * @return true
   */
  @Override
  public boolean supportsWrite() {
    return true;
  }
}