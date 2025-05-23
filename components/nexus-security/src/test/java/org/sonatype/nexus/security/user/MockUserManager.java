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

import org.sonatype.nexus.security.role.ExternalRoleMappedTest;
import org.sonatype.nexus.security.role.RoleIdentifier;

/**
 * Mock implementation of UserManager for testing purposes.
 * This implementation is thread-safe and compatible with Java 21 virtual threads.
 * It avoids using synchronized blocks or methods that could cause virtual thread pinning.
 *
 * @see ExternalRoleMappedTest
 * @see UserManagementTest
 */
public class MockUserManager
    extends AbstractReadOnlyUserManager
{
  @Override
  public String getSource() {
    return "Mock";
  }

  @Override
  public String getAuthenticationRealmName() {
    return "Mock";
  }

  // Cache of users to avoid recreating them on every call, making the implementation more efficient with virtual threads
  private final Set<User> userCache = Collections.newSetFromMap(new ConcurrentHashMap<>());
  
  /**
   * Initialize the user cache with a mock user
   */
  {  
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
    userCache.add(jcohen);
  }

  @Override
  public Set<User> listUsers() {
    // Return a copy of the user cache to prevent concurrent modification issues
    return new HashSet<>(userCache);
  }

  @Override
  public Set<String> listUserIds() {
    // Create a thread-safe set for user IDs
    Set<String> userIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    for (User user : this.listUsers()) {
      userIds.add(user.getUserId());
    }
    return userIds;
  }

  @Override
  public Set<User> searchUsers(UserSearchCriteria criteria) {
    // This implementation is intentionally left as a stub
    // In a real implementation, this would need to be thread-safe as well
    return null;
  }

  @Override
  public User getUser(String userId) throws UserNotFoundException {
    // Efficiently find user by ID without synchronization blocks that could cause virtual thread pinning
    for (User user : this.userCache) {
      if (user.getUserId().equals(userId)) {
        // Return a copy to prevent modification of the cached user
        return cloneUser(user);
      }
    }
    throw new UserNotFoundException(userId);
  }
  
  /**
   * Creates a copy of a user to prevent modification of cached instances
   * This helps maintain thread safety without using synchronized blocks
   */
  private User cloneUser(User source) {
    User clone = new User();
    clone.setEmailAddress(source.getEmailAddress());
    clone.setFirstName(source.getFirstName());
    clone.setLastName(source.getLastName());
    clone.setSource(source.getSource());
    clone.setStatus(source.getStatus());
    clone.setUserId(source.getUserId());
    
    // Copy roles
    for (RoleIdentifier role : source.getRoles()) {
      clone.addRole(new RoleIdentifier(role.getSource(), role.getRoleId()));
    }
    
    return clone;
  }

  @Override
  public User getUser(final String userId, final Set<String> roleIds) throws UserNotFoundException {
    // Delegate to the main getUser method which is already thread-safe and virtual thread compatible
    return getUser(userId);
  }

  @Override
  public boolean isConfigured() {
    throw new UnsupportedOperationException("Not supported yet.");
  }
}