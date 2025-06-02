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
 * Mock user manager implementation for testing purposes.
 * Updated for Java 21 compatibility with thread-safe initialization for virtual thread execution.
 * <p>
 * This implementation ensures proper JSR-330 annotation usage (@Singleton, @Named) compatible with
 * Java 21 and Eclipse Sisu 0.10.0/Google Guice 7.0.0 dependency injection framework.
 * <p>
 * Thread safety is ensured through the parent class's thread-safe collections and proper
 * initialization sequence in the constructor.  
 */
@Singleton
@Named("MockUserManagerB")
public class MockUserManagerB
    extends MockUserManagerSupport
{
  /**
   * Constructor initializes mock users in a thread-safe manner.
   * The initialization is performed once during singleton instantiation,
   * and the parent class's thread-safe collections ensure proper visibility
   * across virtual threads.
   */
  public MockUserManagerB() {
    // Create and initialize users in a thread-safe manner
    // Since this is a singleton, this initialization happens only once during application startup
    // The parent class's thread-safe collections (ConcurrentHashMap.newKeySet) ensure proper visibility
    initializeUsers();
  }

  /**
   * Initialize mock users with test data.
   * This method is called once during singleton instantiation.
   */
  private void initializeUsers() {
    User a = createUser(
        "Brenda D. Burton", 
        "bburton@sonatype.org", 
        "bburton", 
        UserStatus.active, 
        new String[]{"RoleA", "RoleB", "RoleC"}
    );

    User b = createUser(
        "Julian R. Blevins", 
        "jblevins@sonatype.org", 
        "jblevins", 
        UserStatus.active, 
        new String[]{"RoleA", "RoleB"}
    );

    User c = createUser(
        "Kathryn J. Simmons", 
        "ksimmons@sonatype.org", 
        "ksimmons", 
        UserStatus.active, 
        new String[]{"RoleA", "RoleB"}
    );

    User d = createUser(
        "Florence T. Dahmen", 
        "fdahmen@sonatype.org", 
        "fdahmen", 
        UserStatus.active, 
        new String[]{"RoleA", "RoleB"}
    );

    User e = createUser(
        "Jill  Codar", 
        "jcodar@sonatype.org", 
        "jcodar", 
        UserStatus.active, 
        new String[]{}
    );

    User f = createUser(
        "Joe Coder", 
        "jcoder@sonatype.org", 
        "jcoder", 
        UserStatus.active, 
        new String[]{"Role1", "Role2", "Role3"}
    );

    // Add users to the thread-safe collection in the parent class
    this.addUser(a, a.getUserId());
    this.addUser(b, b.getUserId());
    this.addUser(c, c.getUserId());
    this.addUser(d, d.getUserId());
    this.addUser(e, e.getUserId());
    this.addUser(f, f.getUserId());
  }

  /**
   * Helper method to create a user with the specified attributes.
   * This improves code readability and maintainability while ensuring consistent user creation.  
   *
   * @param name User's full name
   * @param email User's email address
   * @param userId User's ID
   * @param status User's status
   * @param roles Array of role names to assign to the user
   * @return The created User object
   */
  private User createUser(String name, String email, String userId, UserStatus status, String[] roles) {
    User user = new User();
    user.setName(name);
    user.setEmailAddress(email);
    user.setSource(this.getSource());
    user.setUserId(userId);
    user.setStatus(status);
    
    // Add roles if provided
    for (String role : roles) {
      user.addRole(new RoleIdentifier(this.getSource(), role));
    }
    
    return user;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSource() {
    return "MockUserManagerB";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getAuthenticationRealmName() {
    return "MockRealmB";
  }
}