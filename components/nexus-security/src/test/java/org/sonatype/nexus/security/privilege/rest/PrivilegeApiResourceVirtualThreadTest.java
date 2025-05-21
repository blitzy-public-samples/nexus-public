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
package org.sonatype.nexus.security.privilege.rest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.core.MediaType;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.security.ErrorMessageUtil;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.privilege.ApplicationPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.DuplicatePrivilegeException;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.ReadonlyPrivilegeException;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.privilege.rest.ApiPrivilegeApplication.DOMAIN_KEY;
import static org.sonatype.nexus.security.privilege.rest.ApiPrivilegeWildcard.PATTERN_KEY;
import static org.sonatype.nexus.security.privilege.rest.ApiPrivilegeWithActions.ACTIONS_KEY;

/**
 * Tests for {@link PrivilegeApiResource} using Java 21 Virtual Threads to verify
 * concurrent privilege operations and REST API compatibility.
 */
@ExtendWith(MockitoExtension.class)
class PrivilegeApiResourceVirtualThreadTest
    extends TestSupport
{
  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private AuthorizationManager authorizationManager;

  private PrivilegeApiResource underTest;

  @BeforeEach
  void setup() throws Exception {
    when(securitySystem.getAuthorizationManager("default")).thenReturn(authorizationManager);

    Map<String, PrivilegeDescriptor> privilegeDescriptors = new HashMap<>();
    privilegeDescriptors.put(ApplicationPrivilegeDescriptor.TYPE, new ApplicationPrivilegeDescriptor(false));
    privilegeDescriptors.put(WildcardPrivilegeDescriptor.TYPE, new WildcardPrivilegeDescriptor());

    underTest = new PrivilegeApiResource(securitySystem, privilegeDescriptors);
  }

  /**
   * Test concurrent retrieval of privileges using Virtual Threads.
   */
  @Test
  void testConcurrentGetPrivileges() throws Exception {
    // Create test privileges
    Privilege priv1 = createPrivilege("application", "priv1", "priv1desc", false, DOMAIN_KEY, "testDomain", ACTIONS_KEY,
        "create,read,update,delete");
    Privilege priv2 = createPrivilege("wildcard", "priv2", "priv2desc", true, PATTERN_KEY, "a:pattern");

    when(securitySystem.listPrivileges()).thenReturn(new LinkedHashSet<>(Arrays.asList(priv2, priv1)));

    // Number of concurrent threads to use
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Use Virtual Thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            List<ApiPrivilege> apiPrivileges = new ArrayList<>(underTest.getPrivileges());
            
            // Verify results
            assertThat(apiPrivileges.size(), is(2));
            assertApiPrivilegeApplication(apiPrivileges.get(0), "priv1", "priv1desc", false, "testDomain", PrivilegeAction.ADD,
                PrivilegeAction.READ, PrivilegeAction.EDIT, PrivilegeAction.DELETE);
            assertApiPrivilegeWildcard(apiPrivileges.get(1), "priv2", "priv2desc", true, "a:pattern");
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
      
      // Verify listPrivileges was called the expected number of times
      verify(securitySystem, times(threadCount)).listPrivileges();
    }
  }

  /**
   * Test concurrent privilege retrieval by name using Virtual Threads.
   */
  @Test
  void testConcurrentGetPrivilegeByName() throws Exception {
    Privilege priv = createPrivilege("application", "priv", "privdesc", true, DOMAIN_KEY, "testDomain", ACTIONS_KEY,
        "create,update");

    when(authorizationManager.getPrivilegeByName("priv")).thenReturn(priv);

    // Number of concurrent threads to use
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Use structured concurrency with Virtual Threads
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Fork multiple subtasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        scope.fork(() -> {
          try {
            ApiPrivilege apiPrivilege = underTest.getPrivilege("priv");
            
            // Verify results
            assertApiPrivilegeApplication(apiPrivilege, "priv", "privdesc", true, "testDomain", PrivilegeAction.ADD,
                PrivilegeAction.EDIT);
            return true;
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            return false;
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all subtasks to complete or fail
      scope.join();
      scope.throwIfFailed();
      
      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
      
      // Verify getPrivilegeByName was called the expected number of times
      verify(authorizationManager, times(threadCount)).getPrivilegeByName("priv");
    }
  }

  /**
   * Test concurrent privilege creation using Virtual Threads.
   */
  @Test
  void testConcurrentCreatePrivileges() throws Exception {
    // Setup mock to simulate privilege creation
    when(authorizationManager.getPrivilege(any())).thenThrow(new NoSuchPrivilegeException("name"));

    // Number of concurrent threads to use
    int threadCount = 20;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    // Use Virtual Thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          try {
            String name = "privilege" + index;
            ApiPrivilegeWildcardRequest apiPrivilege = new ApiPrivilegeWildcardRequest(name, "description", "pattern" + index);
            
            underTest.createPrivilege(apiPrivilege);
            successCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }));
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify all privileges were created successfully
      assertThat(successCount.get(), is(threadCount));
      
      // Verify addPrivilege was called the expected number of times
      verify(authorizationManager, times(threadCount)).addPrivilege(any());
    }
  }

  /**
   * Test concurrent privilege deletion using Virtual Threads.
   */
  @Test
  void testConcurrentDeletePrivileges() throws Exception {
    // Setup privileges for deletion
    List<String> privilegeNames = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String name = "priv" + i;
      privilegeNames.add(name);
      Privilege priv = createPrivilege("wildcard", name, "privdesc", false, PATTERN_KEY, "a:pattern");
      when(authorizationManager.getPrivilegeByName(name)).thenReturn(priv);
    }

    // Use structured concurrency with Virtual Threads
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Fork multiple subtasks using virtual threads
      for (String name : privilegeNames) {
        scope.fork(() -> {
          underTest.deletePrivilege(name);
          return true;
        });
      }

      // Wait for all subtasks to complete or fail
      scope.join();
      scope.throwIfFailed();
      
      // Verify deletePrivilegeByName was called for each privilege
      for (String name : privilegeNames) {
        verify(authorizationManager).deletePrivilegeByName(name);
      }
    }
  }

  /**
   * Test concurrent privilege updates using Virtual Threads.
   */
  @Test
  void testConcurrentUpdatePrivileges() throws Exception {
    // Setup privileges for update
    List<Privilege> privileges = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String name = "priv" + i;
      Privilege priv = createPrivilege("wildcard", name, "privdesc", false, PATTERN_KEY, "a:pattern");
      privileges.add(priv);
      when(authorizationManager.getPrivilegeByName(name)).thenReturn(priv);
    }

    // Use Virtual Thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(privileges.size());
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      for (Privilege priv : privileges) {
        executor.submit(() -> {
          try {
            String name = priv.getName();
            ApiPrivilegeWildcardRequest apiPrivilege = new ApiPrivilegeWildcardRequest(name, "newdescription", "a:new:pattern");
            
            underTest.updatePrivilege(name, apiPrivilege);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
      
      // Verify updatePrivilegeByName was called for each privilege
      verify(authorizationManager, times(privileges.size())).updatePrivilegeByName(any());
    }
  }

  /**
   * Test concurrent privilege operations with error handling using Virtual Threads.
   */
  @Test
  void testConcurrentPrivilegeOperationsWithErrors() throws Exception {
    // Setup a privilege that will succeed
    String successName = "success-priv";
    Privilege successPriv = createPrivilege("wildcard", successName, "privdesc", false, PATTERN_KEY, "a:pattern");
    when(authorizationManager.getPrivilegeByName(successName)).thenReturn(successPriv);
    
    // Setup a privilege that will fail with NoSuchPrivilegeException
    String notFoundName = "not-found-priv";
    when(authorizationManager.getPrivilegeByName(notFoundName)).thenThrow(new NoSuchPrivilegeException(notFoundName));
    
    // Setup a privilege that will fail with ReadonlyPrivilegeException
    String readonlyName = "readonly-priv";
    Privilege readonlyPriv = createPrivilege("wildcard", readonlyName, "privdesc", true, PATTERN_KEY, "a:pattern");
    when(authorizationManager.getPrivilegeByName(readonlyName)).thenReturn(readonlyPriv);
    doThrow(new ReadonlyPrivilegeException(readonlyName)).when(authorizationManager).deletePrivilegeByName(readonlyName);

    // Test concurrent operations with a mix of success and failure cases
    AtomicReference<WebApplicationMessageException> notFoundException = new AtomicReference<>();
    AtomicReference<WebApplicationMessageException> readonlyException = new AtomicReference<>();

    // Use structured concurrency with Virtual Threads
    try (var scope = new StructuredTaskScope<Boolean>()) {
      // Fork subtask for successful privilege
      var successTask = scope.fork(() -> {
        underTest.deletePrivilege(successName);
        return true;
      });
      
      // Fork subtask for not found privilege
      var notFoundTask = scope.fork(() -> {
        try {
          underTest.deletePrivilege(notFoundName);
          return false; // Should not reach here
        }
        catch (WebApplicationMessageException e) {
          notFoundException.set(e);
          return true;
        }
      });
      
      // Fork subtask for readonly privilege
      var readonlyTask = scope.fork(() -> {
        try {
          underTest.deletePrivilege(readonlyName);
          return false; // Should not reach here
        }
        catch (WebApplicationMessageException e) {
          readonlyException.set(e);
          return true;
        }
      });

      // Wait for all subtasks to complete
      scope.join();
      
      // Verify results
      assertThat(successTask.get(), is(true));
      assertThat(notFoundTask.get(), is(true));
      assertThat(readonlyTask.get(), is(true));
      
      // Verify exceptions
      WebApplicationMessageException e1 = notFoundException.get();
      assertThat(e1.getResponse().getStatus(), is(404));
      assertThat(e1.getResponse().getMediaType(), is(MediaType.APPLICATION_JSON_TYPE));
      assertThat(e1.getResponse().getEntity().toString(),
          is(ErrorMessageUtil.getFormattedMessage("\"Privilege 'not-found-priv' not found.\"")));
      
      WebApplicationMessageException e2 = readonlyException.get();
      assertThat(e2.getResponse().getStatus(), is(400));
      assertThat(e2.getResponse().getMediaType(), is(MediaType.APPLICATION_JSON_TYPE));
      assertThat(e2.getResponse().getEntity().toString(),
          is(ErrorMessageUtil.getFormattedMessage(
              "\"Privilege 'readonly-priv' is internal and cannot be modified or deleted.\"")));
      
      // Verify method calls
      verify(authorizationManager).deletePrivilegeByName(successName);
      verify(authorizationManager).getPrivilegeByName(notFoundName);
      verify(authorizationManager).deletePrivilegeByName(readonlyName);
    }
  }

  /**
   * Test stress test for privilege operations under high concurrent load using Virtual Threads.
   */
  @Test
  void testStressTestPrivilegeOperations() throws Exception {
    // Setup test data
    Privilege priv = createPrivilege("application", "priv", "privdesc", false, DOMAIN_KEY, "testDomain", ACTIONS_KEY,
        "create,read,update,delete");
    when(securitySystem.listPrivileges()).thenReturn(new LinkedHashSet<>(Collections.singletonList(priv)));
    when(authorizationManager.getPrivilegeByName("priv")).thenReturn(priv);

    // Number of concurrent threads to use for stress test
    int threadCount = 1000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Use Virtual Thread per task executor for high concurrency
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int index = i % 3; // Cycle through different operations
        executor.submit(() -> {
          try {
            switch (index) {
              case 0:
                // Get all privileges
                List<ApiPrivilege> apiPrivileges = new ArrayList<>(underTest.getPrivileges());
                assertThat(apiPrivileges.size(), is(1));
                break;
              case 1:
                // Get specific privilege
                ApiPrivilege apiPrivilege = underTest.getPrivilege("priv");
                assertThat(apiPrivilege.getName(), is("priv"));
                break;
              case 2:
                // Update privilege
                ApiPrivilegeApplicationRequest updateRequest = new ApiPrivilegeApplicationRequest(
                    "priv", "updated desc", "testDomain", Arrays.asList(PrivilegeAction.ADD, PrivilegeAction.EDIT));
                underTest.updatePrivilege("priv", updateRequest);
                break;
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete with timeout
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      
      // Verify all tasks completed within the timeout
      assertThat("All tasks should complete within timeout", completed, is(true));
      
      // Verify no errors occurred
      assertThat("No errors should occur during stress test", errorCount.get(), is(0));
    }
  }

  /**
   * Test JAX-RS compatibility with Virtual Threads by simulating concurrent REST API calls.
   */
  @Test
  void testJaxRsCompatibilityWithVirtualThreads() throws Exception {
    // Setup test data
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    // Create a privilege request
    ApiPrivilegeApplicationRequest apiPrivilege = new ApiPrivilegeApplicationRequest("name", "description", "domain",
        Arrays.asList(PrivilegeAction.ADD, PrivilegeAction.EDIT, PrivilegeAction.DELETE, PrivilegeAction.READ));

    // Use structured concurrency with Virtual Threads
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Fork multiple subtasks to simulate concurrent REST API calls
      for (int i = 0; i < 10; i++) {
        scope.fork(() -> {
          underTest.createPrivilege(apiPrivilege);
          return true;
        });
      }

      // Wait for all subtasks to complete or fail
      scope.join();
      scope.throwIfFailed();
      
      // Verify addPrivilege was called the expected number of times
      verify(authorizationManager, times(10)).addPrivilege(any());
      
      // Verify the privilege properties
      ArgumentCaptor<Privilege> argument = ArgumentCaptor.forClass(Privilege.class);
      verify(authorizationManager, times(10)).addPrivilege(argument.capture());
      
      List<Privilege> capturedPrivileges = argument.getAllValues();
      for (Privilege privilege : capturedPrivileges) {
        assertPrivilege(privilege, "name", "description", DOMAIN_KEY, "domain", ACTIONS_KEY,
            "create,update,delete,read");
      }
    }
  }

  private void assertPrivilege(Privilege privilege,
                               String name,
                               String description,
                               String... properties)
  {
    assertThat(privilege, notNullValue());
    assertThat(privilege.getName(), is(name));
    assertThat(privilege.getId(), is(name));
    assertThat(privilege.getDescription(), is(description));
    assertThat(privilege.isReadOnly(), is(false));

    for (int i = 0; i < properties.length; i += 2) {
      assertThat(privilege.getPrivilegeProperty(properties[i]), is(properties[i + 1]));
    }
  }

  private void assertApiPrivilegeApplication(ApiPrivilege apiPrivilege,
                                             String name,
                                             String description,
                                             boolean readOnly,
                                             String domain,
                                             PrivilegeAction... actions)
  {
    assertApiPrivilege(apiPrivilege, ApplicationPrivilegeDescriptor.TYPE, name, description, readOnly);
    assertThat(((ApiPrivilegeApplication) apiPrivilege).getDomain(), is(domain));
    assertThat(((ApiPrivilegeApplication) apiPrivilege).getActions(), containsInAnyOrder(actions));
  }

  private void assertApiPrivilegeWildcard(ApiPrivilege apiPrivilege,
                                          String name,
                                          String description,
                                          boolean readOnly,
                                          String pattern)
  {
    assertApiPrivilege(apiPrivilege, WildcardPrivilegeDescriptor.TYPE, name, description, readOnly);
    assertThat(((ApiPrivilegeWildcard) apiPrivilege).getPattern(), is(pattern));
  }

  private void assertApiPrivilege(ApiPrivilege apiPrivilege,
                                  String type,
                                  String name,
                                  String description,
                                  boolean readOnly)
  {
    assertThat(apiPrivilege, notNullValue());
    assertThat(apiPrivilege.getType(), is(type));
    assertThat(apiPrivilege.getName(), is(name));
    assertThat(apiPrivilege.getDescription(), is(description));
    assertThat(apiPrivilege.isReadOnly(), is(readOnly));
  }

  private Privilege createPrivilege(String type,
                                    String name,
                                    String description,
                                    boolean readOnly,
                                    String... properties)
  {
    Privilege privilege = new Privilege();
    privilege.setType(type);
    privilege.setId(name);
    privilege.setName(name);
    privilege.setDescription(description);
    privilege.setReadOnly(readOnly);

    for (int i = 0; i < properties.length; i += 2) {
      privilege.addProperty(properties[i], properties[i + 1]);
    }

    return privilege;
  }
}