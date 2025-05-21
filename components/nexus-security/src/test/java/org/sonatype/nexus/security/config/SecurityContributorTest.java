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
package org.sonatype.nexus.security.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.sonatype.nexus.security.AbstractSecurityTest;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.name.Names;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class SecurityContributorTest
    extends AbstractSecurityTest
{
  private SecurityConfigurationManager manager;

  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return InitialSecurityConfiguration.getConfiguration();
  }

  @Override
  protected void customizeModules(List<Module> modules) {
    super.customizeModules(modules);
    modules.add(new AbstractModule()
    {
      @Override
      protected void configure() {
        // Bind security contributors with named annotations for Shiro 2.0.0 compatibility
        bind(SecurityContributor.class).annotatedWith(Names.named("s2")).to(TestSecurityContributor2.class);
        bind(SecurityContributor.class).annotatedWith(Names.named("s1")).to(TestSecurityContributor1.class);
      }
    });
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    manager = lookup(SecurityConfigurationManager.class);
  }
  
  /**
   * Test role merging functionality with multiple security contributors.
   */
  @Test
  public void testRoleMerging() throws Exception {
    List<CRole> roles = manager.listRoles();

    CRole anon = manager.readRole("anon");
    assertTrue(anon.getRoles().contains("other"), "roles: " + anon.getRoles());
    assertTrue(anon.getRoles().contains("role2"), "roles: " + anon.getRoles());
    assertEquals(2, anon.getRoles().size(), "roles: " + anon.getRoles());

    assertTrue(anon.getPrivileges().contains("priv1"));
    assertTrue(anon.getPrivileges().contains("4-test"));
    assertEquals(2, anon.getPrivileges().size(), "privs: " + anon.getPrivileges());

    assertEquals("Test Anon Role", anon.getName());
    assertEquals("Test Anon Role Description", anon.getDescription());

    CRole other = manager.readRole("other");
    assertTrue(other.getRoles().contains("role2"));
    assertEquals(1, other.getRoles().size(), "roles: " + other.getRoles());

    assertTrue(other.getPrivileges().contains("6-test"));
    assertTrue(other.getPrivileges().contains("priv2"));
    assertEquals(2, other.getPrivileges().size(), "privs: " + other.getPrivileges());

    assertEquals("Other Role", other.getName());
    assertEquals("Other Role Description", other.getDescription());

    // all roles
    assertEquals(8, roles.size());
  }
  
  /**
   * Test privilege merging functionality with multiple security contributors.
   */
  @Test
  public void testPrivsMerging() throws Exception {
    List<CPrivilege> privs = manager.listPrivileges();

    CPrivilege priv = manager.readPrivilege("1-test");
    assertNotNull(priv);

    priv = manager.readPrivilege("2-test");
    assertNotNull(priv);

    priv = manager.readPrivilege("4-test");
    assertNotNull(priv);

    priv = manager.readPrivilege("5-test");
    assertNotNull(priv);

    priv = manager.readPrivilege("6-test");
    assertNotNull(priv);

    assertNotNull(manager.readPrivilege("priv1"));
    assertNotNull(manager.readPrivilege("priv2"));
    assertNotNull(manager.readPrivilege("priv3"));
    assertNotNull(manager.readPrivilege("priv4"));
    assertNotNull(manager.readPrivilege("priv5"));

    assertEquals(10, privs.size(), "privs: " + this.privilegeListToStringList(privs));
  }
  
  /**
   * Test privilege merging using Java 21 pattern matching for instanceof.
   */
  @Test
  public void testPrivilegePatternMatching() throws Exception {
    CPrivilege priv = manager.readPrivilege("priv1");
    
    // Using Java 21 pattern matching for instanceof
    if (priv instanceof MemoryCPrivilege memPriv) {
      assertEquals("priv1", memPriv.getId());
      assertEquals("method", memPriv.getType());
      assertEquals("priv1", memPriv.getName());
      assertEquals("read", memPriv.getProperty("method"));
      assertEquals("priv1-ONE", memPriv.getProperty("permission"));
    } else {
      // This should not happen
      assertTrue(false, "Expected MemoryCPrivilege instance");
    }
  }
  
  /**
   * Test role hierarchy using Java 21 switch pattern matching.
   */
  @Test
  public void testRoleHierarchyWithPatternMatching() throws Exception {
    CRole role = manager.readRole("role2");
    
    // Using Java 21 switch pattern matching
    String roleType = switch (role) {
      case MemoryCRole memRole when memRole.getRoles().size() > 2 -> "Complex Role";
      case MemoryCRole memRole when memRole.getRoles().size() > 0 -> "Simple Role";
      case MemoryCRole memRole -> "Basic Role";
      default -> "Unknown Role Type";
    };
    
    assertEquals("Complex Role", roleType, "Role should be classified as Complex Role");
    
    // Verify the role hierarchy
    assertTrue(role.getRoles().contains("role3"));
    assertTrue(role.getRoles().contains("role4"));
    assertTrue(role.getRoles().contains("role5"));
    assertEquals(3, role.getRoles().size());
  }
  
  /**
   * Test security configuration with Java 21 Virtual Threads for concurrent access.
   */
  @Test
  public void testConcurrentAccessWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple tasks to read roles concurrently
      Future<CRole> anonRoleFuture = executor.submit(() -> manager.readRole("anon"));
      Future<CRole> otherRoleFuture = executor.submit(() -> manager.readRole("other"));
      Future<CRole> role1Future = executor.submit(() -> manager.readRole("role1"));
      Future<CRole> role2Future = executor.submit(() -> manager.readRole("role2"));
      
      // Get results and verify they are correct
      CRole anonRole = anonRoleFuture.get();
      CRole otherRole = otherRoleFuture.get();
      CRole role1 = role1Future.get();
      CRole role2 = role2Future.get();
      
      // Verify the roles were retrieved correctly
      assertEquals("Test Anon Role", anonRole.getName());
      assertEquals("Other Role", otherRole.getName());
      assertEquals("role1", role1.getName());
      assertEquals("role2", role2.getName());
      
      // Verify the roles have the correct privileges
      assertTrue(anonRole.getPrivileges().contains("priv1"));
      assertTrue(otherRole.getPrivileges().contains("priv2"));
      assertTrue(role1.getPrivileges().contains("priv1"));
      assertTrue(role2.getPrivileges().contains("priv1"));
      assertTrue(role2.getPrivileges().contains("priv2"));
    }
  }
  
  /**
   * Test security configuration with record patterns for privilege properties.
   */
  @Test
  public void testPrivilegePropertiesWithRecordPatterns() throws Exception {
    CPrivilege priv = manager.readPrivilege("priv1");
    assertInstanceOf(MemoryCPrivilege.class, priv);
    
    MemoryCPrivilege memPriv = (MemoryCPrivilege) priv;
    
    // Using record patterns to extract properties
    if (memPriv instanceof MemoryCPrivilege(String id, String type, String name, _, var properties)) {
      assertEquals("priv1", id);
      assertEquals("method", type);
      assertEquals("priv1", name);
      assertEquals("read", properties.get("method"));
      assertEquals("priv1-ONE", properties.get("permission"));
    }
  }
  
  /**
   * Helper method to convert a list of privileges to a list of privilege IDs.
   */
  private List<String> privilegeListToStringList(List<CPrivilege> privs) {
    // Using Java 21 stream features
    return privs.stream()
        .map(CPrivilege::getId)
        .toList();
  }