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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.sonatype.nexus.security.role.ExternalRoleMappedTest;
import org.sonatype.nexus.security.role.RoleIdentifier;

/**
 * Mock implementation of UserManager for testing purposes.
 * This implementation is thread-safe and compatible with Java 21 virtual threads.
 * 
 * @see ExternalRoleMappedTest
 * @see UserManagementTest
 */
public class MockUserManager
    extends AbstractReadOnlyUserManager
{
  // Thread-safe cache of users to avoid recreating them on every call
  private final Set<User> userCache = createUserCache();
  
  /**
   * Creates and initializes the user cache with mock data.
   * This is called only once during initialization to ensure thread safety.
   */
  private Set<User> createUserCache() {
    Set<User> users = ConcurrentHashMap.newKeySet();

    User jcohen = new User();
    jcohen.setEmailAddress("JamesDCohen@example.com");
    jcohen.setFirstName("James");
    jcohen.setLastName("Cohen");
    // jcohen.setName( "James E. Cohen" );
    // jcohen.setReadOnly( true );
    jcohen.setSource("Mock");
    jcohen.setStatus(UserStatus.active);
    jcohen.setUserId("jcohen");
    jcohen.addRole(new RoleIdentifier("Mock", "mockrole1"));
    users.add(jcohen);

    return users;
  }

  @Override
  public String getSource() {
    return "Mock";
  }

  @Override
  public String getAuthenticationRealmName() {
    return "Mock";
  }

  /**
   * Returns a thread-safe view of all users.
   * Safe for concurrent access by multiple threads, including virtual threads.
   */
  @Override
  public Set<User> listUsers() {
    return Collections.unmodifiableSet(userCache);
  }

  /**
   * Returns a thread-safe set of all user IDs.
   * Safe for concurrent access by multiple threads, including virtual threads.
   */
  @Override
  public Set<String> listUserIds() {
    Set<String> userIds = ConcurrentHashMap.newKeySet();
    for (User user : this.userCache) {
      userIds.add(user.getUserId());
    }
    return userIds;
  }

  @Override
  public Set<User> searchUsers(UserSearchCriteria criteria) {
    return null;
  }

  /**
   * Retrieves a user by ID.
   * Thread-safe implementation that works with virtual threads.
   */
  @Override
  public User getUser(String userId) throws UserNotFoundException {
    // Using Java 21 pattern matching for instanceof with a binding variable
    for (User user : this.userCache) {
      if (userId.equals(user.getUserId())) {
        return user;
      }
    }
    throw new UserNotFoundException(userId);
  }

  @Override
  public User getUser(final String userId, final Set<String> roleIds) throws UserNotFoundException {
    return getUser(userId);
  }

  @Override
  public boolean isConfigured() {
    throw new UnsupportedOperationException("Not supported yet.");
  }
}
