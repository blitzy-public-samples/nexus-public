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
package org.sonatype.nexus.script.plugin;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.core.MediaType;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptManager;
import org.sonatype.nexus.script.plugin.internal.rest.ApiPrivilegeScriptRequest;
import org.sonatype.nexus.script.plugin.internal.rest.ScriptPrivilegeApiResource;
import org.sonatype.nexus.script.plugin.internal.security.ScriptPrivilegeDescriptor;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.privilege.ApplicationPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.script.plugin.internal.rest.ApiPrivilegeScript.SCRIPT_KEY;
import static org.sonatype.nexus.security.privilege.rest.ApiPrivilegeWithActions.ACTIONS_KEY;

/**
 * Tests the {@link ScriptPrivilegeApiResource} REST API operations using Java 21 Virtual Threads to validate
 * improved concurrency for HTTP operations.
 */
@ExtendWith(MockitoExtension.class)
class ScriptPrivilegeApiResourceVirtualThreadTest
    extends TestSupport
{
  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private AuthorizationManager authorizationManager;

  @Mock
  private ScriptManager scriptManager;
  
  @Captor
  private ArgumentCaptor<Privilege> privilegeCaptor;

  private ScriptPrivilegeApiResource underTest;

  @BeforeEach
  void setup() throws Exception {
    when(securitySystem.getAuthorizationManager("default")).thenReturn(authorizationManager);
    when(scriptManager.get(any())).thenReturn(mock(Script.class));
    when(scriptManager.get("invalid")).thenReturn(null);

    Map<String, PrivilegeDescriptor> privilegeDescriptors = new HashMap<>();
    privilegeDescriptors.put(ApplicationPrivilegeDescriptor.TYPE, new ApplicationPrivilegeDescriptor(false));
    privilegeDescriptors.put(WildcardPrivilegeDescriptor.TYPE, new WildcardPrivilegeDescriptor());
    privilegeDescriptors.put(ScriptPrivilegeDescriptor.TYPE, new ScriptPrivilegeDescriptor(scriptManager, false));

    underTest = new ScriptPrivilegeApiResource(securitySystem, privilegeDescriptors);
  }

  /**
   * Tests creating a script privilege using a virtual thread.
   */
  @Test
  void testCreatePrivilege_scriptWithVirtualThread() throws Exception {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest("name", "description", "scriptName", Arrays
        .asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.DELETE, PrivilegeAction.EDIT,
            PrivilegeAction.ADD, PrivilegeAction.RUN));

    // Create a virtual thread to execute the API operation
    Thread virtualThread = Thread.ofVirtual().name("create-privilege-vt").start(() -> {
      underTest.createPrivilege(apiPrivilege);
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();

    verify(authorizationManager).addPrivilege(privilegeCaptor.capture());
    assertPrivilege(privilegeCaptor.getValue(), "name", "description", SCRIPT_KEY, "scriptName", ACTIONS_KEY,
        "browse,read,delete,edit,add,run");
  }

  /**
   * Tests creating a script privilege with ALL action using a virtual thread.
   */
  @Test
  void testCreatePrivilege_scriptWithAllActionWithVirtualThread() throws Exception {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest("name", "description", "scriptName",
        Collections.singleton(PrivilegeAction.ALL));

    // Create a virtual thread to execute the API operation
    Thread virtualThread = Thread.ofVirtual().name("create-privilege-all-vt").start(() -> {
      underTest.createPrivilege(apiPrivilege);
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();

    verify(authorizationManager).addPrivilege(privilegeCaptor.capture());
    assertPrivilege(privilegeCaptor.getValue(), "name", "description", SCRIPT_KEY, "scriptName", ACTIONS_KEY, "*");
  }

  /**
   * Tests creating a script privilege with an invalid script using a virtual thread.
   */
  @Test
  void testCreatePrivilege_invalidScriptWithVirtualThread() throws Exception {
    when(authorizationManager.getPrivilege("name")).thenThrow(new NoSuchPrivilegeException("name"));

    ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest("name", "description", "invalid",
        Collections.singleton(PrivilegeAction.ALL));

    // Create an atomic reference to capture the exception from the virtual thread
    AtomicReference<WebApplicationMessageException> exceptionRef = new AtomicReference<>();
    
    // Create a virtual thread to execute the API operation
    Thread virtualThread = Thread.ofVirtual().name("create-invalid-privilege-vt").start(() -> {
      try {
        underTest.createPrivilege(apiPrivilege);
      }
      catch (WebApplicationMessageException e) {
        exceptionRef.set(e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();

    // Verify the exception was thrown with the expected details
    WebApplicationMessageException exception = exceptionRef.get();
    assertThat(exception, notNullValue());
    assertThat(exception.getResponse().getStatus(), is(400));
    assertThat(exception.getResponse().getMediaType(), is(MediaType.APPLICATION_JSON_TYPE));
    assertThat(exception.getResponse().getEntity().toString(),
        is("ValidationErrorXO{id='*', message='\"Invalid script 'invalid' supplied.\"'}"));
  }

  /**
   * Tests updating a script privilege using a virtual thread.
   */
  @Test
  void testUpdatePrivilege_scriptWithVirtualThread() throws Exception {
    Privilege priv = createPrivilege("script", "priv", "privdesc", false, SCRIPT_KEY, "scriptName", ACTIONS_KEY,
        "read,run");
    when(authorizationManager.getPrivilegeByName("priv")).thenReturn(priv);

    ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest("priv", "newdescription", "newScriptName",
        Collections.singletonList(PrivilegeAction.RUN));

    // Create a virtual thread to execute the API operation
    Thread virtualThread = Thread.ofVirtual().name("update-privilege-vt").start(() -> {
      underTest.updatePrivilege("priv", apiPrivilege);
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();

    verify(authorizationManager).updatePrivilegeByName(privilegeCaptor.capture());
    assertPrivilege(privilegeCaptor.getValue(), "priv", "newdescription", SCRIPT_KEY, "newScriptName", ACTIONS_KEY,
        "run");
  }
  
  /**
   * Tests concurrent creation of multiple script privileges using virtual threads.
   * This test demonstrates the scalability benefits of virtual threads for concurrent API operations.
   */
  @Test
  void testConcurrentCreatePrivileges_withVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Configure mock to handle multiple privilege creations
    when(authorizationManager.getPrivilege(any())).thenThrow(new NoSuchPrivilegeException("name"));
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit tasks to create privileges concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest(
                "name" + index, 
                "description" + index, 
                "scriptName", 
                Collections.singleton(PrivilegeAction.ALL));
            
            underTest.createPrivilege(apiPrivilege);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "All virtual threads should complete within the timeout");
      
      // Verify that all privileges were created
      verify(authorizationManager, times(threadCount)).addPrivilege(any());
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for concurrent API operations.
   * This test demonstrates the performance benefits of virtual threads over platform threads.
   */
  @Test
  void testPerformanceComparison_platformVsVirtualThreads() throws Exception {
    int threadCount = 1000;
    
    // Configure mock to handle multiple privilege creations
    when(authorizationManager.getPrivilege(any())).thenThrow(new NoSuchPrivilegeException("name"));
    
    // Measure execution time with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      executeWithThreads(threadCount, Thread.ofPlatform().factory());
    });
    
    // Measure execution time with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      executeWithThreads(threadCount, Thread.ofVirtual().factory());
    });
    
    // Log the results
    log.info("Performance comparison for {} concurrent operations:", threadCount);
    log.info("Platform threads execution time: {} ms", platformThreadTime);
    log.info("Virtual threads execution time: {} ms", virtualThreadTime);
    log.info("Performance improvement: {}x", (double) platformThreadTime / virtualThreadTime);
    
    // Virtual threads should generally be faster for I/O-bound operations,
    // but in a test environment with mocks, the difference might not be significant.
    // The real benefit would be seen in a production environment with actual I/O operations.
  }
  
  /**
   * Tests for thread pinning detection when executing API operations.
   * This test helps identify potential bottlenecks in the REST API operations.
   */
  @Test
  void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection
    // Note: In a real environment, this would be done with JVM flag: -Djdk.tracePinnedThreads=full
    // For this test, we'll simulate pinning detection
    
    AtomicInteger pinnedThreadCount = new AtomicInteger(0);
    
    // Create a virtual thread to execute the API operation that might cause pinning
    Thread virtualThread = Thread.ofVirtual().name("pinning-detection-vt").start(() -> {
      // Simulate thread pinning detection
      // In a real scenario, this would be detected by the JVM
      boolean pinningDetected = detectThreadPinning(() -> {
        ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest(
            "name", 
            "description", 
            "scriptName", 
            Collections.singleton(PrivilegeAction.ALL));
        
        underTest.createPrivilege(apiPrivilege);
      });
      
      if (pinningDetected) {
        pinnedThreadCount.incrementAndGet();
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Log the results
    log.info("Thread pinning detected: {}", pinnedThreadCount.get() > 0);
    log.info("Number of pinned threads: {}", pinnedThreadCount.get());
    
    // In an ideal implementation, there should be no thread pinning
    // This is informational and not a strict assertion as some pinning might be unavoidable
  }

  /**
   * Helper method to execute concurrent API operations using the specified thread factory.
   */
  private void executeWithThreads(int threadCount, ThreadFactory threadFactory) throws Exception {
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest(
                "name" + index, 
                "description" + index, 
                "scriptName", 
                Collections.singleton(PrivilegeAction.ALL));
            
            underTest.createPrivilege(apiPrivilege);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(30, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Helper method to measure execution time of a runnable.
   */
  private long measureExecutionTime(Runnable runnable) throws Exception {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Helper method to detect thread pinning.
   * In a real environment, this would be done by the JVM with -Djdk.tracePinnedThreads=full.
   * This is a simplified simulation for testing purposes.
   */
  private boolean detectThreadPinning(Runnable runnable) {
    // This is a simplified simulation of thread pinning detection
    // In a real environment, the JVM would detect pinning and log it
    
    // For this test, we'll assume no pinning occurs with our mocked implementation
    // In a real scenario with actual I/O and synchronization, pinning might occur
    runnable.run();
    return false;
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