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
package org.sonatype.nexus.security.authz;

import java.util.List;
import java.util.Map;

import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.memory.MemoryCPrivilege.MemoryCPrivilegeBuilder;
import org.sonatype.nexus.security.config.memory.MemoryCRole;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.config.memory.MemoryCUserRoleMapping;

/**
 * Helper class for creating in-memory security configurations for JUnit 5 tests.
 * 
 * @since 3.0
 */
public class AuthorizationManagerTestSecurity
{
  /**
   * Creates a standard in-memory security configuration for testing.
   * 
   * @return A pre-configured MemorySecurityConfiguration instance
   */
  public static MemorySecurityConfiguration securityModel() {
    // Create users with their credentials and details
    var users = List.of(
        new MemoryCUser()
            .withId("admin")
            .withPassword("f865b53623b121fd34ee5426c792e5c33af8c227")
            .withFirstName("Administrator")
            .withStatus("active")
            .withEmail("admin@example.org"),
        new MemoryCUser()
            .withId("test-user")
            .withPassword("b2a0e378437817cebdf753d7dff3dd75483af9e0")
            .withFirstName("Test User")
            .withStatus("active")
            .withEmail("test-user@example.org"),
        new MemoryCUser()
            .withId("anonymous")
            .withPassword("0a92fab3230134cca6eadd9898325b9b2ae67998")
            .withFirstName("Anonynmous User")
            .withStatus("active")
            .withEmail("anonymous@example.org")
    );
    
    // Create user role mappings
    var userRoleMappings = List.of(
        new MemoryCUserRoleMapping()
            .withUserId("other-user")
            .withSource("default")
            .withRoles("role2", "role3"),
        new MemoryCUserRoleMapping()
            .withUserId("admin")
            .withSource("default")
            .withRoles("role1"),
        new MemoryCUserRoleMapping()
            .withUserId("test-user")
            .withSource("default")
            .withRoles("role1", "role2"),
        new MemoryCUserRoleMapping()
            .withUserId("anonymous")
            .withSource("default")
            .withRoles("role2")
    );
    
    // Create privileges with their properties
    var privileges = List.of(
        createPrivilege("1", "1", "", Map.of(
            "method", "read",
            "permission", "/some/path/"
        )),
        createPrivilege("2", "2", "", Map.of(
            "method", "read",
            "permission", "/some/path/"
        )),
        createPrivilege("3", "3-name", "Privilege Three", Map.of(
            "method", "read",
            "permission", "/some/path/"
        )),
        createPrivilege("4", "4", "", Map.of(
            "method", "read",
            "permission", "/some/path/"
        ))
    );
    
    // Create roles with their privileges
    var roles = List.of(
        new MemoryCRole()
            .withId("role1")
            .withName("RoleOne")
            .withDescription("Role One")
            .withPrivileges("1", "2"),
        new MemoryCRole()
            .withId("role2")
            .withName("RoleTwo")
            .withDescription("Role Two")
            .withPrivileges("3", "4"),
        new MemoryCRole()
            .withId("role3")
            .withName("RoleThree")
            .withDescription("Role Three")
            .withPrivileges("1", "4")
    );
    
    // Build and return the complete security configuration
    return new MemorySecurityConfiguration()
        .withUsers(users)
        .withUserRoleMappings(userRoleMappings)
        .withPrivileges(privileges)
        .withRoles(roles);
  }
  
  /**
   * Helper method to create a privilege with the given properties.
   * 
   * @param id The privilege ID
   * @param name The privilege name
   * @param description The privilege description
   * @param properties The privilege properties
   * @return A built MemoryCPrivilege instance
   */
  private static MemoryCPrivilegeBuilder createPrivilege(String id, String name, String description, Map<String, String> properties) {
    var builder = new MemoryCPrivilegeBuilder(id)
        .type("method")
        .name(name)
        .description(description);
        
    properties.forEach(builder::property);
    
    return builder;
  }
}