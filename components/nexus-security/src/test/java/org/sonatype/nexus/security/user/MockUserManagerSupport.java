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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract support class for mock user managers used in testing.
 * Updated for Java 21 virtual thread compatibility with thread-safe collections.
 */
public abstract class MockUserManagerSupport
    extends AbstractUserManager
{
  // Using ConcurrentHashMap.newKeySet() for thread-safe set implementation compatible with virtual threads
  private final Set<User> users = ConcurrentHashMap.newKeySet();

  public boolean supportsWrite() {
    return true;
  }

  /**
   * Add a user to the manager.
   * Thread-safe implementation for virtual thread compatibility.
   */
  public User addUser(User user, String password) {
    // ConcurrentHashMap.newKeySet() provides thread-safe add operations
    this.getUsers().add(user);
    return user;
  }

  /**
   * Update a user in the manager.
   * Thread-safe implementation for virtual thread compatibility.
   */
  public User updateUser(User user) throws UserNotFoundException {
    User existingUser = this.getUser(user.getUserId());

    if (existingUser == null) {
      throw new UserNotFoundException(user.getUserId());
    }

    return user;
  }

  /**
   * Delete a user from the manager.
   * Thread-safe implementation for virtual thread compatibility.
   */
  public void deleteUser(String userId) throws UserNotFoundException {
    User existingUser = this.getUser(userId);

    if (existingUser == null) {
      throw new UserNotFoundException(userId);
    }

    // ConcurrentHashMap.newKeySet() provides thread-safe remove operations
    this.getUsers().remove(existingUser);
  }

  /**
   * Get a user by ID.
   * Thread-safe implementation for virtual thread compatibility.
   */
  public User getUser(String userId) {
    // Creating a snapshot of users to avoid ConcurrentModificationException
    // when iterating while the collection might be modified by another thread
    Set<User> userSnapshot = Set.copyOf(this.getUsers());
    
    for (User user : userSnapshot) {
      if (user.getUserId().equals(userId)) {
        return user;
      }
    }
    return null;
  }

  @Override
  public User getUser(final String userId, final Set<String> roleIds) throws UserNotFoundException {
    return getUser(userId);
  }

  /**
   * List all user IDs.
   * Thread-safe implementation for virtual thread compatibility.
   */
  public Set<String> listUserIds() {
    // Creating a thread-safe result set
    Set<String> userIds = ConcurrentHashMap.newKeySet();

    // Creating a snapshot of users to avoid ConcurrentModificationException
    Set<User> userSnapshot = Set.copyOf(this.getUsers());
    
    for (User user : userSnapshot) {
      userIds.add(user.getUserId());
    }

    return userIds;
  }

  /**
   * List all users.
   * Thread-safe implementation for virtual thread compatibility.
   */
  public Set<User> listUsers() {
    // Return an unmodifiable view of the users set
    // ConcurrentHashMap.newKeySet() is already thread-safe for iteration
    return Collections.unmodifiableSet(this.getUsers());
  }

  /**
   * Search users based on criteria.
   * Thread-safe implementation for virtual thread compatibility.
   */
  public Set<User> searchUsers(UserSearchCriteria criteria) {
    // Creating a snapshot of users to avoid ConcurrentModificationException
    Set<User> userSnapshot = Set.copyOf(this.getUsers());
    return this.filterListInMemeory(userSnapshot, criteria);
  }

  /**
   * Get the set of users.
   * @return Thread-safe set of users
   */
  protected Set<User> getUsers() {
    return users;
  }

  /**
   * Change a user's password.
   * Thread-safe implementation for virtual thread compatibility.
   */
  public void changePassword(String userId, String newPassword) throws UserNotFoundException {
    // empty implementation - no thread safety concerns
  }

  @Override
  public boolean isConfigured() {
    throw new UnsupportedOperationException("Not supported yet.");
  }
}