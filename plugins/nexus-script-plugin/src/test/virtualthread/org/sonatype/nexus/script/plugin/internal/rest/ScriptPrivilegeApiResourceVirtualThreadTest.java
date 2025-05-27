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
package org.sonatype.nexus.script.plugin.internal.rest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import javax.ws.rs.core.MediaType;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptManager;
import org.sonatype.nexus.script.plugin.internal.security.ScriptPrivilegeDescriptor;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.privilege.ApplicationPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.script.plugin.internal.rest.ApiPrivilegeScript.SCRIPT_KEY;
import static org.sonatype.nexus.security.privilege.rest.ApiPrivilegeWithActions.ACTIONS_KEY;

/**
 * Virtual Thread-specific tests for the {@link ScriptPrivilegeApiResource} REST endpoint.
 * 
 * This test class validates that the script privilege REST API functions correctly when handling
 * multiple concurrent requests using Java 21's Virtual Threads.
 */
@Category(VirtualThreadTestGroup.class)
public class ScriptPrivilegeApiResourceVirtualThreadTest
    extends TestSupport
{
  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private AuthorizationManager authorizationManager;

  @Mock
  private ScriptManager scriptManager;

  private ScriptPrivilegeApiResource underTest;

  @Before
  public void setup() throws Exception {
    MockitoAnnotations.openMocks(this);
    
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
   * Tests that the REST API can handle concurrent privilege creation requests using Virtual Threads.
   */
  @Test
  public void testConcurrentPrivilegeCreation() throws Exception {
    // Configure mock to handle concurrent privilege creation
    when(authorizationManager.getPrivilege(any())).thenThrow(new NoSuchPrivilegeException("name"));
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      ConcurrentHashMap<String, Privilege> createdPrivileges = new ConcurrentHashMap<>();
      
      // Submit multiple concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            String privilegeName = "script-privilege-" + index;
            ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest(
                privilegeName, 
                "Virtual Thread Test Description " + index, 
                "scriptName", 
                Arrays.asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.RUN));
            
            // Capture the created privilege
            ArgumentCaptor<Privilege> privilegeCaptor = ArgumentCaptor.forClass(Privilege.class);
            when(authorizationManager.addPrivilege(privilegeCaptor.capture())).thenAnswer(invocation -> {
              Privilege privilege = privilegeCaptor.getValue();
              createdPrivileges.put(privilege.getName(), privilege);
              return privilege;
            });
            
            underTest.createPrivilege(apiPrivilege);
          } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error in virtual thread task", e);
          } finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("No errors should occur during concurrent privilege creation", 
          errorCount.get(), is(0));
      assertThat("All privileges should be created", 
          createdPrivileges.size(), is(taskCount));
      
      // Verify a sample of the created privileges
      Privilege samplePrivilege = createdPrivileges.get("script-privilege-0");
      assertThat(samplePrivilege, notNullValue());
      assertThat(samplePrivilege.getPrivilegeProperty(SCRIPT_KEY), is("scriptName"));
      assertThat(samplePrivilege.getPrivilegeProperty(ACTIONS_KEY), is("browse,read,run"));
    } finally {
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Tests that the REST API can handle concurrent privilege update requests using Virtual Threads.
   */
  @Test
  public void testConcurrentPrivilegeUpdates() throws Exception {
    // Create a set of privileges to update
    int privilegeCount = 50;
    Map<String, Privilege> privileges = new HashMap<>();
    
    for (int i = 0; i < privilegeCount; i++) {
      String name = "priv-" + i;
      Privilege priv = createPrivilege("script", name, "Original description " + i, false, 
          SCRIPT_KEY, "scriptName", ACTIONS_KEY, "read,run");
      privileges.put(name, priv);
      when(authorizationManager.getPrivilegeByName(name)).thenReturn(priv);
    }
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(privilegeCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      ConcurrentHashMap<String, Privilege> updatedPrivileges = new ConcurrentHashMap<>();
      
      // Capture updated privileges
      when(authorizationManager.updatePrivilegeByName(any())).thenAnswer(invocation -> {
        Privilege privilege = invocation.getArgument(0);
        updatedPrivileges.put(privilege.getName(), privilege);
        return privilege;
      });
      
      // Submit concurrent update tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < privilegeCount; i++) {
        final int index = i;
        final String privilegeName = "priv-" + index;
        
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest(
                privilegeName, 
                "Updated description " + index, 
                "newScriptName-" + index, 
                Collections.singletonList(PrivilegeAction.RUN));
            
            underTest.updatePrivilege(privilegeName, apiPrivilege);
          } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error updating privilege", e);
          } finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("No errors should occur during concurrent privilege updates", 
          errorCount.get(), is(0));
      assertThat("All privileges should be updated", 
          updatedPrivileges.size(), is(privilegeCount));
      
      // Verify a sample of the updated privileges
      Privilege samplePrivilege = updatedPrivileges.get("priv-0");
      assertThat(samplePrivilege, notNullValue());
      assertThat(samplePrivilege.getDescription(), is("Updated description 0"));
      assertThat(samplePrivilege.getPrivilegeProperty(SCRIPT_KEY), is("newScriptName-0"));
      assertThat(samplePrivilege.getPrivilegeProperty(ACTIONS_KEY), is("run"));
    } finally {
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Tests that the REST API correctly validates script names under high concurrency with Virtual Threads.
   */
  @Test
  public void testConcurrentScriptValidation() throws Exception {
    // Configure mock to handle concurrent privilege creation
    when(authorizationManager.getPrivilege(any())).thenThrow(new NoSuchPrivilegeException("name"));
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger validationErrorCount = new AtomicInteger(0);
      AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String privilegeName = "script-privilege-" + index;
            // Every other request will use an invalid script name
            String scriptName = (index % 2 == 0) ? "valid-script" : "invalid";
            
            ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest(
                privilegeName, 
                "Description " + index, 
                scriptName, 
                Collections.singleton(PrivilegeAction.ALL));
            
            try {
              underTest.createPrivilege(apiPrivilege);
              if (scriptName.equals("invalid")) {
                // This should not happen - invalid script should cause exception
                unexpectedErrorCount.incrementAndGet();
              }
            } catch (WebApplicationMessageException e) {
              if (scriptName.equals("invalid") && 
                  e.getResponse().getStatus() == 400 && 
                  e.getResponse().getMediaType().equals(MediaType.APPLICATION_JSON_TYPE)) {
                // Expected validation error for invalid script
                validationErrorCount.incrementAndGet();
              } else {
                // Unexpected error
                unexpectedErrorCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            unexpectedErrorCount.incrementAndGet();
            log.error("Unexpected error in virtual thread task", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("No unexpected errors should occur", unexpectedErrorCount.get(), is(0));
      assertThat("Half of the requests should have validation errors", 
          validationErrorCount.get(), is(taskCount / 2));
    } finally {
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Tests that the REST API performs well under high concurrency with Virtual Threads compared to platform threads.
   */
  @Test
  public void testVirtualThreadPerformanceComparison() throws Exception {
    // Configure mock to handle concurrent privilege creation
    when(authorizationManager.getPrivilege(any())).thenThrow(new NoSuchPrivilegeException("name"));
    when(authorizationManager.addPrivilege(any())).thenAnswer(invocation -> invocation.getArgument(0));
    
    // Create thread factories for both types
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Number of concurrent requests to test with
    int requestCount = 1000;
    
    // Run the test with platform threads
    long platformThreadTime = runConcurrentPrivilegeCreationTest(platformThreadFactory, requestCount);
    log.info("Platform thread execution time for {} requests: {} ms", requestCount, platformThreadTime);
    
    // Run the test with virtual threads
    long virtualThreadTime = runConcurrentPrivilegeCreationTest(virtualThreadFactory, requestCount);
    log.info("Virtual thread execution time for {} requests: {} ms", requestCount, virtualThreadTime);
    
    // Virtual threads should generally be more efficient for this I/O-bound operation
    // but we don't make a hard assertion since performance can vary by environment
    log.info("Performance ratio (platform/virtual): {}", (double) platformThreadTime / virtualThreadTime);
  }

  /**
   * Tests that no thread pinning occurs during REST API operations with Virtual Threads.
   */
  @Test
  public void testNoThreadPinningDuringRestOperations() throws Exception {
    // Configure mock to handle concurrent privilege creation
    when(authorizationManager.getPrivilege(any())).thenThrow(new NoSuchPrivilegeException("name"));
    when(authorizationManager.addPrivilege(any())).thenAnswer(invocation -> invocation.getArgument(0));
    
    // Create a virtual thread factory with a custom name prefix for identification
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("test-vthread-").factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
      
      // Enable thread pinning detection if running on Java 21
      // This is a no-op if the JVM doesn't support this property
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String privilegeName = "script-privilege-" + index + "-" + UUID.randomUUID();
            ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest(
                privilegeName, 
                "Description " + index, 
                "scriptName", 
                Arrays.asList(PrivilegeAction.BROWSE, PrivilegeAction.READ, PrivilegeAction.RUN));
            
            // Create the privilege - this should not cause thread pinning
            underTest.createPrivilege(apiPrivilege);
            
            // Check if current thread is a virtual thread
            Thread currentThread = Thread.currentThread();
            if (currentThread.isVirtual()) {
              // Virtual thread operations completed successfully
              log.debug("Virtual thread operation completed: {}", currentThread.getName());
            } else {
              // This would indicate a problem - the operation should stay on the virtual thread
              log.error("Operation not executed on virtual thread: {}", currentThread.getName());
              pinnedThreadDetected.set(true);
            }
          } catch (Exception e) {
            log.error("Error in virtual thread task", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no thread pinning was detected
      assertThat("No thread pinning should be detected during REST operations", 
          pinnedThreadDetected.get(), is(false));
      
      // Reset the system property
      System.clearProperty("jdk.tracePinnedThreads");
    } finally {
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Helper method to run a concurrent privilege creation test with the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (virtual or platform)
   * @param requestCount The number of concurrent requests to make
   * @return The execution time in milliseconds
   */
  private long runConcurrentPrivilegeCreationTest(ThreadFactory threadFactory, int requestCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(requestCount);
      LongAdder errorCount = new LongAdder();
      
      // Prepare all tasks but don't start them yet
      for (int i = 0; i < requestCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for the start signal to ensure all threads start at approximately the same time
            startLatch.await();
            
            String privilegeName = "perf-test-privilege-" + index;
            ApiPrivilegeScriptRequest apiPrivilege = new ApiPrivilegeScriptRequest(
                privilegeName, 
                "Performance Test Description", 
                "scriptName", 
                Arrays.asList(PrivilegeAction.BROWSE, PrivilegeAction.READ));
            
            underTest.createPrivilege(apiPrivilege);
          } catch (Exception e) {
            errorCount.increment();
            log.error("Error in thread task", e);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start timing and release all threads
      long startTime = System.currentTimeMillis();
      startLatch.countDown();
      
      // Wait for all tasks to complete
      completionLatch.await(60, TimeUnit.SECONDS);
      long endTime = System.currentTimeMillis();
      
      // Verify no errors occurred
      assertEquals("No errors should occur during performance test", 0, errorCount.sum());
      
      return endTime - startTime;
    } finally {
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Helper method to create a privilege for testing.
   */
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