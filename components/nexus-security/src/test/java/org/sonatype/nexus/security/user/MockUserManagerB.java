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

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.role.RoleIdentifier;

/**
 * Mock implementation of a UserManager for testing purposes.
 * <p>
 * This implementation is optimized for Java 21 virtual threads by leveraging the thread-safe
 * implementation of its parent class {@link MockUserManagerSupport}. It initializes a set of
 * test users in its constructor using the thread-safe {@code addUser} method from the parent class.
 * </p>
 * <p>
 * The JSR-330 annotations (@Named, @Singleton) ensure proper dependency injection in the Java 21
 * environment, allowing this mock to be properly discovered and instantiated as a singleton.
 * </p>
 */
@Singleton
@Named("MockUserManagerB")
public class MockUserManagerB
    extends MockUserManagerSupport
{
  /**
   * Constructs a new instance with a predefined set of users.
   * <p>
   * This constructor is thread-safe because it uses the thread-safe {@code addUser} method
   * from the parent class, which uses ConcurrentHashMap's non-blocking operations.
   * </p>
   */
  public MockUserManagerB() {
    // Create and initialize user objects
    // Each user is created independently and then added to the thread-safe storage
    User a = createUser(
        "bburton",
        "Brenda D. Burton",
        "bburton@sonatype.org",
        UserStatus.active,
        new String[]{"RoleA", "RoleB", "RoleC"}
    );

    User b = createUser(
        "jblevins",
        "Julian R. Blevins",
        "jblevins@sonatype.org",
        UserStatus.active,
        new String[]{"RoleA", "RoleB"}
    );

    User c = createUser(
        "ksimmons",
        "Kathryn J. Simmons",
        "ksimmons@sonatype.org",
        UserStatus.active,
        new String[]{"RoleA", "RoleB"}
    );

    User d = createUser(
        "fdahmen",
        "Florence T. Dahmen",
        "fdahmen@sonatype.org",
        UserStatus.active,
        new String[]{"RoleA", "RoleB"}
    );

    User e = createUser(
        "jcodar",
        "Jill Codar",
        "jcodar@sonatype.org",
        UserStatus.active,
        new String[]{}
    );

    User f = createUser(
        "jcoder",
        "Joe Coder",
        "jcoder@sonatype.org",
        UserStatus.active,
        new String[]{"Role1", "Role2", "Role3"}
    );

    // Add users to the thread-safe storage using the parent class's addUser method
    // which uses ConcurrentHashMap's putIfAbsent for thread-safety
    this.addUser(a, a.getUserId());
    this.addUser(b, b.getUserId());
    this.addUser(c, c.getUserId());
    this.addUser(d, d.getUserId());
    this.addUser(e, e.getUserId());
    this.addUser(f, f.getUserId());
  }

  /**
   * Helper method to create a user with the specified properties.
   * <p>
   * This method encapsulates the user creation logic to improve readability and maintainability.
   * Each user is created independently, making this process thread-safe.
   * </p>
   *
   * @param userId the user ID
   * @param name the user's name
   * @param email the user's email address
   * @param status the user's status
   * @param roleIds the role IDs to assign to the user
   * @return the created user
   */
  private User createUser(String userId, String name, String email, UserStatus status, String[] roleIds) {
    User user = new User();
    user.setName(name);
    user.setEmailAddress(email);
    user.setSource(this.getSource());
    user.setUserId(userId);
    user.setStatus(status);
    
    // Add roles if specified
    for (String roleId : roleIds) {
      user.addRole(new RoleIdentifier(this.getSource(), roleId));
    }
    
    return user;
  }

  /**
   * Gets the source identifier for this user manager.
   *
   * @return the source identifier
   */
  @Override
  public String getSource() {
    return "MockUserManagerB";
  }

  /**
   * Gets the authentication realm name for this user manager.
   *
   * @return the authentication realm name
   */
  @Override
  public String getAuthenticationRealmName() {
    return "MockRealmB";
  }
}
