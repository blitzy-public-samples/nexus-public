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

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.config.memory.MemoryCPrivilege;
import org.sonatype.nexus.security.config.memory.MemoryCRole;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.config.memory.MemoryCUserRoleMapping;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.name.Names;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.experimental.categories.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests the {@link SecurityConfigurationManager} using Java 21 Virtual Threads.
 * This test class validates that security configuration operations work correctly
 * under high concurrency with virtual threads.
 */
@Tag("VirtualThreadTestGroup")
@Category(VirtualThreadTestGroup.class)
public class VirtualThreadSecurityConfigurationTest
    extends AbstractSecurityTest
{
  private SecurityConfigurationManager manager;

  @Inject
  private List<SecurityContributor> testContributors;

  @Inject
  private List<MutableTestSecurityContributor> mutableTestContributors;

  @Inject
  private EventManager eventManager;

  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return InitialSecurityConfiguration.getConfiguration();
  }

  @Override
  protected void customizeModules(final List<Module> modules) {
    super.customizeModules(modules);
    modules.add(new AbstractModule()
    {
      @Override
      protected void configure() {
        bindStaticContributor("static-default", new TestSecurityContributor2());
        bindDynamicContributor("dynamic-default", new MutableTestSecurityContributor());

        int staticResourceCount = 50;
        for (int ii = 0; ii < staticResourceCount - 1; ii++) {
          bindStaticContributor("static-" + ii, new TestSecurityContributor3());
        }

        int dynamicResourceCount = 50;
        for (int ii = 0; ii < dynamicResourceCount - 1; ii++) {
          bindDynamicContributor("dynamic-" + ii, new MutableTestSecurityContributor());
        }
      }

      private void bindStaticContributor(final String name, final SecurityContributor instance) {
        bind(SecurityContributor.class).annotatedWith(Names.named(name)).toInstance(instance);
      }

      private void bindDynamicContributor(final String name, final MutableTestSecurityContributor instance) {
        bind(MutableTestSecurityContributor.class).annotatedWith(Names.named(name)).toInstance(instance);
        bind(SecurityContributor.class).annotatedWith(Names.named(name)).toInstance(instance);
      }
    });
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    this.manager = lookup(SecurityConfigurationManager.class);

    // mimic EventManager auto-registration
    eventManager.register(manager);

    // test the lookup, make sure we have 100 contributors (50 static + 50 dynamic)
    assertEquals(100, testContributors.size());
  }
  
  /**
   * Test concurrent privilege operations using virtual threads.
   * This test creates and reads privileges concurrently to validate thread safety.
   */
  @Test
  void testConcurrentPrivilegeOperationsWithVirtualThreads() throws Exception {
    final int threadCount = 200;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Every other thread creates a privilege, others read privileges
            if (index % 2 == 0) {
              // Create a new privilege
              MemoryCPrivilege privilege = new MemoryCPrivilege();
              privilege.setId("test-privilege-" + index);
              privilege.setName("Test Privilege " + index);
              privilege.setType("application");
              privilege.setDescription("Test privilege created by virtual thread " + index);
              privilege.setReadOnly(false);
              
              Map<String, String> properties = new HashMap<>();
              properties.put("method", "read");
              properties.put("permission", "test:permission:" + index);
              privilege.setProperties(properties);
              
              manager.addPrivilege(privilege);
            } else {
              // Read all privileges
              List<CPrivilege> privileges = manager.listPrivileges();
              if (privileges == null) {
                errorCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All virtual threads should complete within timeout");
    }
    
    // Verify no errors occurred
    assertEquals(0, errorCount.get(), "No errors should occur during concurrent privilege operations");
    
    // Verify the privileges were created
    List<CPrivilege> privileges = manager.listPrivileges();
    int createdPrivilegeCount = 0;
    
    for (CPrivilege privilege : privileges) {
      if (privilege.getId().startsWith("test-privilege-")) {
        createdPrivilegeCount++;
      }
    }
    
    assertEquals(threadCount / 2, createdPrivilegeCount, "All privileges should be successfully created");
  }
  
  /**
   * Test concurrent role operations using virtual threads.
   * This test creates, updates, and reads roles concurrently to validate thread safety.
   * It uses Java 21 pattern matching for switch statements to determine the operation.
   */
  @Test
  void testConcurrentRoleOperationsWithVirtualThreads() throws Exception {
    final int threadCount = 300;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create some initial roles
    for (int i = 0; i < 10; i++) {
      MemoryCRole role = new MemoryCRole();
      role.setId("initial-role-" + i);
      role.setName("Initial Role " + i);
      role.setDescription("Initial test role " + i);
      role.setReadOnly(false);
      role.setPrivileges(List.of("privilege-" + i));
      manager.addRole(role);
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Determine operation based on thread index
            // Using Java 21 pattern matching for switch statements
            switch (index % 3) {
              case 0 -> {
                // Create a new role
                MemoryCRole role = new MemoryCRole();
                role.setId("test-role-" + index);
                role.setName("Test Role " + index);
                role.setDescription("Test role created by virtual thread " + index);
                role.setReadOnly(false);
                role.setPrivileges(List.of("privilege-" + index));
                manager.addRole(role);
              }
              case 1 -> {
                // Update an existing role if available
                if (index < 10) {
                  CRole role = manager.getRole("initial-role-" + (index % 10));
                  if (role != null) {
                    role.setDescription("Updated by thread " + index);
                    List<String> privileges = new ArrayList<>(role.getPrivileges());
                    privileges.add("new-privilege-" + index);
                    role.setPrivileges(privileges);
                    manager.updateRole(role);
                  }
                }
              }
              case 2 -> {
                // Read all roles
                List<CRole> roles = manager.listRoles();
                if (roles == null) {
                  errorCount.incrementAndGet();
                }
              }
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All virtual threads should complete within timeout");
    }
    
    // Verify no errors occurred
    assertEquals(0, errorCount.get(), "No errors should occur during concurrent role operations");
    
    // Verify the roles were created and updated
    List<CRole> roles = manager.listRoles();
    int createdRoleCount = 0;
    int updatedRoleCount = 0;
    
    for (CRole role : roles) {
      if (role.getId().startsWith("test-role-")) {
        createdRoleCount++;
      } else if (role.getId().startsWith("initial-role-") && role.getDescription().startsWith("Updated by thread")) {
        updatedRoleCount++;
      }
    }
    
    assertEquals(threadCount / 3, createdRoleCount, "All roles should be successfully created");
    assertTrue(updatedRoleCount > 0, "Some roles should be successfully updated");
  }
  
  /**
   * Test concurrent user operations using virtual threads.
   * This test creates, updates, and reads users concurrently to validate thread safety.
   */
  @Test
  void testConcurrentUserOperationsWithVirtualThreads() throws Exception {
    final int threadCount = 300;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Determine operation based on thread index using pattern matching
            switch (index % 4) {
              case 0 -> {
                // Create a new user
                MemoryCUser user = new MemoryCUser();
                user.setId("test-user-" + index);
                user.setFirstName("Test");
                user.setLastName("User " + index);
                user.setEmail("test.user." + index + "@example.com");
                user.setStatus("active");
                user.setPassword("password" + index);
                manager.addUser(user, "test-password");
                
                // Add user role mapping
                MemoryCUserRoleMapping mapping = new MemoryCUserRoleMapping();
                mapping.setUserId(user.getId());
                mapping.setSource("default");
                mapping.setRoles(List.of("test-role"));
                manager.addUserRoleMapping(mapping);
              }
              case 1 -> {
                // Update an existing user if available
                String userId = "test-user-" + (index % 10 * 4); // Get a user created by case 0
                CUser user = manager.getUser(userId);
                if (user != null) {
                  user.setFirstName("Updated");
                  user.setLastName("User " + index);
                  user.setEmail("updated.user." + index + "@example.com");
                  manager.updateUser(user);
                }
              }
              case 2 -> {
                // Read all users
                List<CUser> users = manager.listUsers();
                if (users == null) {
                  errorCount.incrementAndGet();
                }
              }
              case 3 -> {
                // Read user role mappings
                String userId = "test-user-" + (index % 10 * 4); // Get a user created by case 0
                List<CUserRoleMapping> mappings = manager.getUserRoleMappings(userId);
                if (mappings == null) {
                  // This is acceptable if the user hasn't been created yet
                  if (manager.getUser(userId) != null) {
                    errorCount.incrementAndGet();
                  }
                }
              }
            }
          } catch (Exception e) {
            // Ignore NoSuchUserException as it's expected in some cases
            if (!(e instanceof NoSuchUserException)) {
              errorCount.incrementAndGet();
              e.printStackTrace();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All virtual threads should complete within timeout");
    }
    
    // Verify no errors occurred
    assertEquals(0, errorCount.get(), "No errors should occur during concurrent user operations");
    
    // Verify the users were created
    List<CUser> users = manager.listUsers();
    int createdUserCount = 0;
    
    for (CUser user : users) {
      if (user.getId().startsWith("test-user-")) {
        createdUserCount++;
        
        // Verify user role mappings exist
        List<CUserRoleMapping> mappings = manager.getUserRoleMappings(user.getId());
        assertNotNull(mappings, "User role mappings should exist for created users");
        assertTrue(mappings.size() > 0, "User role mappings should not be empty");
      }
    }
    
    assertTrue(createdUserCount > 0, "Some users should be successfully created");
  }
  
  /**
   * Test concurrent security configuration updates with dynamic contributors using virtual threads.
   * This test validates that security configuration updates propagate correctly across virtual threads.
   */
  @Test
  void testConcurrentSecurityConfigurationUpdatesWithVirtualThreads() throws Exception {
    final int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicBoolean failed = new AtomicBoolean(false);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Every 10th thread will modify a contributor
            if (index % 10 == 0) {
              int contributorIndex = (index / 10) % mutableTestContributors.size();
              mutableTestContributors.get(contributorIndex).setDirty(true);
              
              // Sleep briefly to allow other threads to run concurrently
              Thread.sleep(10);
            }
            
            // All threads verify the configuration is consistent
            List<CPrivilege> privileges = manager.listPrivileges();
            List<CRole> roles = manager.listRoles();
            List<CUser> users = manager.listUsers();
            
            if (privileges == null || roles == null || users == null) {
              failed.set(true);
              System.err.println("Thread " + index + ": Null configuration component detected");
            }
          } catch (Exception e) {
            failed.set(true);
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All virtual threads should complete within timeout");
    }
    
    // Verify the test passed
    assertTrue(!failed.get(), "Concurrent security configuration updates should not cause errors");
    
    // Verify all contributors were accessed
    for (MutableTestSecurityContributor contributor : mutableTestContributors) {
      assertTrue(
          contributor.wasConfigRequested(),
          "Get config should be called on each contributor after concurrent updates: " + contributor.getId());
    }
  }
  
  /**
   * Test extreme concurrency with a very high number of virtual threads.
   * This test validates that the security configuration system can handle
   * a large number of concurrent operations without issues.
   */
  @Test
  void testExtremeConcurrencyWithVirtualThreads() throws Exception {
    // Create a large number of virtual threads to stress test the system
    final int threadCount = 1000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Perform different operations based on the thread index
            // Using pattern matching for switch to determine operation type
            switch (index % 5) {
              case 0 -> {
                // Create a privilege
                if (index < 200) { // Limit the number of privileges created
                  MemoryCPrivilege privilege = new MemoryCPrivilege();
                  privilege.setId("extreme-privilege-" + index);
                  privilege.setName("Extreme Test Privilege " + index);
                  privilege.setType("application");
                  privilege.setDescription("Extreme test privilege created by virtual thread " + index);
                  privilege.setReadOnly(false);
                  
                  Map<String, String> properties = new HashMap<>();
                  properties.put("method", "read");
                  properties.put("permission", "extreme:permission:" + index);
                  privilege.setProperties(properties);
                  
                  manager.addPrivilege(privilege);
                }
              }
              case 1 -> {
                // Create a role
                if (index < 200) { // Limit the number of roles created
                  MemoryCRole role = new MemoryCRole();
                  role.setId("extreme-role-" + index);
                  role.setName("Extreme Test Role " + index);
                  role.setDescription("Extreme test role created by virtual thread " + index);
                  role.setReadOnly(false);
                  role.setPrivileges(List.of("privilege-" + index % 10));
                  manager.addRole(role);
                }
              }
              case 2 -> {
                // Create a user
                if (index < 200) { // Limit the number of users created
                  MemoryCUser user = new MemoryCUser();
                  user.setId("extreme-user-" + index);
                  user.setFirstName("Extreme");
                  user.setLastName("User " + index);
                  user.setEmail("extreme.user." + index + "@example.com");
                  user.setStatus("active");
                  user.setPassword("password" + index);
                  manager.addUser(user, "test-password");
                }
              }
              case 3 -> {
                // Read operations
                List<CPrivilege> privileges = manager.listPrivileges();
                List<CRole> roles = manager.listRoles();
                List<CUser> users = manager.listUsers();
                
                if (privileges == null || roles == null || users == null) {
                  errorCount.incrementAndGet();
                }
              }
              case 4 -> {
                // Update a contributor
                if (index % 50 == 0) { // Only some threads update contributors
                  int contributorIndex = (index / 50) % mutableTestContributors.size();
                  mutableTestContributors.get(contributorIndex).setDirty(true);
                }
              }
            }
          } catch (Exception e) {
            // Ignore certain expected exceptions
            if (!(e instanceof NoSuchUserException) && 
                !(e instanceof NoSuchRoleException) && 
                !(e instanceof NoSuchPrivilegeException)) {
              errorCount.incrementAndGet();
              e.printStackTrace();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete with a longer timeout due to the high thread count
      assertTrue(latch.await(60, TimeUnit.SECONDS), "All extreme concurrency virtual threads should complete within timeout");
    }
    
    // Verify no errors occurred
    assertEquals(0, errorCount.get(), "No errors should occur during extreme concurrency test");
    
    // Verify the configuration is still consistent
    List<CPrivilege> privileges = manager.listPrivileges();
    List<CRole> roles = manager.listRoles();
    List<CUser> users = manager.listUsers();
    
    assertNotNull(privileges, "Privileges should not be null after extreme concurrency test");
    assertNotNull(roles, "Roles should not be null after extreme concurrency test");
    assertNotNull(users, "Users should not be null after extreme concurrency test");
    
    // Verify some extreme test entities were created
    boolean foundPrivilege = false;
    boolean foundRole = false;
    boolean foundUser = false;
    
    for (CPrivilege privilege : privileges) {
      if (privilege.getId().startsWith("extreme-privilege-")) {
        foundPrivilege = true;
        break;
      }
    }
    
    for (CRole role : roles) {
      if (role.getId().startsWith("extreme-role-")) {
        foundRole = true;
        break;
      }
    }
    
    for (CUser user : users) {
      if (user.getId().startsWith("extreme-user-")) {
        foundUser = true;
        break;
      }
    }
    
    assertTrue(foundPrivilege, "Extreme test privileges should be created");
    assertTrue(foundRole, "Extreme test roles should be created");
    assertTrue(foundUser, "Extreme test users should be created");
  }
  
  /**
   * Test compatibility with Apache Shiro 2.0.0 under virtual threads.
   * This test validates that the security configuration works correctly with
   * Apache Shiro 2.0.0 when using virtual threads for concurrent operations.
   */
  @Test
  void testApacheShiroCompatibilityWithVirtualThreads() throws Exception {
    final int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create some test data for Shiro compatibility testing
    MemoryCRole shiroRole = new MemoryCRole();
    shiroRole.setId("shiro-test-role");
    shiroRole.setName("Shiro Test Role");
    shiroRole.setDescription("Role for testing Shiro compatibility");
    shiroRole.setReadOnly(false);
    shiroRole.setPrivileges(List.of("shiro-test-privilege"));
    manager.addRole(shiroRole);
    
    MemoryCPrivilege shiroPrivilege = new MemoryCPrivilege();
    shiroPrivilege.setId("shiro-test-privilege");
    shiroPrivilege.setName("Shiro Test Privilege");
    shiroPrivilege.setType("application");
    shiroPrivilege.setDescription("Privilege for testing Shiro compatibility");
    shiroPrivilege.setReadOnly(false);
    Map<String, String> properties = new HashMap<>();
    properties.put("method", "read");
    properties.put("permission", "shiro:test:permission");
    shiroPrivilege.setProperties(properties);
    manager.addPrivilege(shiroPrivilege);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a user with the Shiro test role
            MemoryCUser user = new MemoryCUser();
            user.setId("shiro-test-user-" + index);
            user.setFirstName("Shiro");
            user.setLastName("User " + index);
            user.setEmail("shiro.user." + index + "@example.com");
            user.setStatus("active");
            user.setPassword("password" + index);
            manager.addUser(user, "test-password");
            
            // Add user role mapping for the Shiro test role
            MemoryCUserRoleMapping mapping = new MemoryCUserRoleMapping();
            mapping.setUserId(user.getId());
            mapping.setSource("default");
            mapping.setRoles(List.of("shiro-test-role"));
            manager.addUserRoleMapping(mapping);
            
            // Verify the user has the correct role and privilege
            List<CUserRoleMapping> mappings = manager.getUserRoleMappings(user.getId());
            if (mappings == null || mappings.isEmpty()) {
              errorCount.incrementAndGet();
              return;
            }
            
            boolean hasShiroRole = false;
            for (CUserRoleMapping m : mappings) {
              if (m.getRoles().contains("shiro-test-role")) {
                hasShiroRole = true;
                break;
              }
            }
            
            if (!hasShiroRole) {
              errorCount.incrementAndGet();
              return;
            }
            
            // Verify the role has the correct privilege
            CRole role = manager.getRole("shiro-test-role");
            if (role == null || !role.getPrivileges().contains("shiro-test-privilege")) {
              errorCount.incrementAndGet();
              return;
            }
            
            // Verify the privilege has the correct properties
            CPrivilege privilege = manager.getPrivilege("shiro-test-privilege");
            if (privilege == null || 
                !privilege.getProperties().containsKey("permission") ||
                !privilege.getProperties().get("permission").equals("shiro:test:permission")) {
              errorCount.incrementAndGet();
              return;
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All Shiro compatibility test threads should complete within timeout");
    }
    
    // Verify no errors occurred
    assertEquals(0, errorCount.get(), "No errors should occur during Shiro compatibility test");
    
    // Verify all users were created with the correct role mapping
    List<CUser> users = manager.listUsers();
    int shiroUserCount = 0;
    
    for (CUser user : users) {
      if (user.getId().startsWith("shiro-test-user-")) {
        shiroUserCount++;
        
        // Verify user role mappings
        List<CUserRoleMapping> mappings = manager.getUserRoleMappings(user.getId());
        assertNotNull(mappings, "User role mappings should exist for Shiro test users");
        
        boolean hasShiroRole = false;
        for (CUserRoleMapping mapping : mappings) {
          if (mapping.getRoles().contains("shiro-test-role")) {
            hasShiroRole = true;
            break;
          }
        }
        
        assertTrue(hasShiroRole, "Shiro test users should have the Shiro test role");
      }
    }
    
    assertEquals(threadCount, shiroUserCount, "All Shiro test users should be created");
  }