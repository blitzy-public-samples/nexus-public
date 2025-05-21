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
package org.sonatype.nexus.security.role.rest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import javax.ws.rs.core.Response.Status;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.NoSuchAuthorizationManagerException;
import org.sonatype.nexus.security.role.Role;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleInternalResourceTest
    extends TestSupport
{
  @Mock
  private SecuritySystem securitySystem;

  private RoleInternalResource underTest;

  @BeforeEach
  void setup() {
    underTest = new RoleInternalResource(securitySystem);
  }

  @Test
  @DisplayName("List roles on empty source")
  void listRolesOnEmptySource() {
    Role r1 = createRole("role1", Arrays.asList("roleA", "roleB"), Arrays.asList("read", "write"));
    Role r2 = createRole("role2", Arrays.asList("roleC", "roleD"), Arrays.asList("read", "delete"));
    when(securitySystem.listRoles()).thenReturn(new HashSet<>(Arrays.asList(r1, r2)));

    List<RoleXOResponse> res = underTest.searchRoles("", "searchParam");

    verify(securitySystem).listRoles();
    assertEquals(2, res.size());
    assertEquals("role1", res.get(0).getName());
    assertEquals("role2", res.get(1).getName());
  }

  @Test
  @DisplayName("Search roles with custom source")
  void searchRoles() throws NoSuchAuthorizationManagerException {
    Role r1 = createRole("customRole1", Arrays.asList("roleA", "roleB"), Arrays.asList("read", "write"));
    Role r2 = createRole("customRole2", Arrays.asList("roleC", "roleD"), Arrays.asList("read", "delete"));
    Role r3 = createRole("customRole3", Arrays.asList("roleX", "roleY"), Arrays.asList("read", "delete"));
    when(securitySystem.searchRoles(anyString(), anyString())).thenReturn(new HashSet<>(Arrays.asList(r1, r2, r3)));

    List<RoleXOResponse> res = underTest.searchRoles("customSource", "searchParam");

    verify(securitySystem, never()).listRoles();
    verify(securitySystem).searchRoles("customSource", "searchParam");
    assertEquals(3, res.size());
    assertEquals("customRole1", res.get(0).getName());
    assertEquals("customRole2", res.get(1).getName());
    assertEquals("customRole3", res.get(2).getName());
  }

  @Test
  @DisplayName("Exception thrown when authorization manager not found")
  void exceptionThrown() throws NoSuchAuthorizationManagerException {
    when(securitySystem.searchRoles(anyString(), anyString())).thenThrow(
        new NoSuchAuthorizationManagerException("bad source"));
    WebApplicationMessageException ex =
        assertThrows(WebApplicationMessageException.class, () -> underTest.searchRoles("authSource", "searchParam"));
    assertEquals(Status.NOT_FOUND.getStatusCode(), ex.getResponse().getStatus());
  }
  
  @Test
  @DisplayName("Concurrent role searching using Virtual Threads")
  void concurrentRoleSearching() throws Exception {
    // Setup roles for testing
    Role r1 = createRole("role1", Arrays.asList("roleA", "roleB"), Arrays.asList("read", "write"));
    Role r2 = createRole("role2", Arrays.asList("roleC", "roleD"), Arrays.asList("read", "delete"));
    when(securitySystem.listRoles()).thenReturn(new HashSet<>(Arrays.asList(r1, r2)));
    
    // Number of concurrent threads to use
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    List<List<RoleXOResponse>> results = new CopyOnWriteArrayList<>();
    List<Throwable> exceptions = new CopyOnWriteArrayList<>();
    
    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual().name("role-search-" + i).start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform role search
          List<RoleXOResponse> res = underTest.searchRoles("", "searchParam");
          results.add(res);
        }
        catch (Throwable e) {
          exceptions.add(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Verify results
    assertEquals(0, exceptions.size(), "No exceptions should have been thrown");
    assertEquals(threadCount, results.size(), "All threads should have returned results");
    
    // Verify each result contains the expected roles
    for (List<RoleXOResponse> result : results) {
      assertEquals(2, result.size(), "Each result should contain 2 roles");
      assertEquals("role1", result.get(0).getName(), "First role should be role1");
      assertEquals("role2", result.get(1).getName(), "Second role should be role2");
    }
    
    // Verify the security system was called the expected number of times
    verify(securitySystem, never()).searchRoles(anyString(), anyString());
    verify(securitySystem).listRoles();
  }

  private Role createRole(String roleName, List<String> roles, List<String> privileges) {
    return new Role(roleName + "Id", roleName, roleName, "internal", true,
        new HashSet<>(roles), new HashSet<>(privileges));
  }
}