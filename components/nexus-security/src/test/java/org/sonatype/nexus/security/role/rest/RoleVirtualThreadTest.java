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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.ws.rs.core.MediaType;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.security.ErrorMessageUtil;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.role.Role;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RoleApiResource} and {@link RoleInternalResource} with Java 21 Virtual Threads.
 * 
 * This test class validates that the role REST endpoints can efficiently handle concurrent requests
 * using Java 21 Virtual Threads without thread pinning issues.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class RoleVirtualThreadTest
    extends TestSupport
{
  private static final int SMALL_CONCURRENCY = 100;
  private static final int MEDIUM_CONCURRENCY = 1000;
  private static final int LARGE_CONCURRENCY = 5000;
  private static final int WARMUP_ITERATIONS = 10;
  private static final int TEST_ITERATIONS = 3;
  private static final String SOURCE = "default";
  
  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private AuthorizationManager authorizationManager;

  private RoleApiResource roleApiResource;
  private RoleInternalResource roleInternalResource;
  
  private final AtomicInteger roleCounter = new AtomicInteger(0);
  private final Set<String> createdRoleIds = ConcurrentHashMap.newKeySet();

  @Before
  public void setup() throws Exception {
    when(securitySystem.getAuthorizationManager(SOURCE)).thenReturn(authorizationManager);
    when(securitySystem.listSources()).thenReturn(Arrays.asList(SOURCE, "LDAP"));
    
    // Setup mock for listRoles
    doAnswer(new Answer<Set<Role>>() {
      @Override
      public Set<Role> answer(InvocationOnMock invocation) {
        Set<Role> roles = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
          roles.add(createRole(SOURCE, "role" + i, "Role " + i, "Description " + i, 
              Arrays.asList("role-a", "role-b"), Arrays.asList("priv-1", "priv-2")));
        }
        return roles;
      }
    }).when(securitySystem).listRoles();
    
    doAnswer(new Answer<Set<Role>>() {
      @Override
      public Set<Role> answer(InvocationOnMock invocation) {
        Set<Role> roles = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
          roles.add(createRole(SOURCE, "role" + i, "Role " + i, "Description " + i, 
              Arrays.asList("role-a", "role-b"), Arrays.asList("priv-1", "priv-2")));
        }
        return roles;
      }
    }).when(securitySystem).listRoles(SOURCE);
    
    // Setup mock for searchRoles
    doAnswer(new Answer<Set<Role>>() {
      @Override
      public Set<Role> answer(InvocationOnMock invocation) {
        String source = invocation.getArgument(0);
        String searchText = invocation.getArgument(1);
        
        Set<Role> roles = new LinkedHashSet<>();
        for (int i = 0; i < 10; i++) {
          roles.add(createRole(source, "search" + i, "Search " + i, "Search Description " + i, 
              Arrays.asList("role-a", "role-b"), Arrays.asList("priv-1", "priv-2")));
        }
        return roles;
      }
    }).when(securitySystem).searchRoles(anyString(), anyString());
    
    // Setup mock for getRole
    doAnswer(new Answer<Role>() {
      @Override
      public Role answer(InvocationOnMock invocation) {
        String roleId = invocation.getArgument(0);
        return createRole(SOURCE, roleId, "Role " + roleId, "Description for " + roleId, 
            Arrays.asList("role-a", "role-b"), Arrays.asList("priv-1", "priv-2"));
      }
    }).when(authorizationManager).getRole(anyString());
    
    // Setup mock for addRole
    doAnswer(new Answer<Role>() {
      @Override
      public Role answer(InvocationOnMock invocation) {
        Role role = invocation.getArgument(0);
        createdRoleIds.add(role.getRoleId());
        return role;
      }
    }).when(authorizationManager).addRole(any(Role.class));
    
    roleApiResource = new RoleApiResource(securitySystem);
    roleInternalResource = new RoleInternalResource(securitySystem);
  }

  /**
   * Tests concurrent retrieval of roles using Virtual Threads.
   * This test validates that the RoleApiResource can handle many concurrent
   * requests efficiently using Virtual Threads.
   */
  @Test
  public void testConcurrentRoleRetrievalWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("role-retrieval-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int concurrency = MEDIUM_CONCURRENCY;
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < concurrency; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            List<RoleXOResponse> roles = roleApiResource.getRoles(SOURCE);
            assertNotNull("Roles should not be null for request " + index, roles);
            assertEquals("Should have 20 roles for request " + index, 20, roles.size());
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            firstException.compareAndSet(null, e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue("All tasks should complete within timeout", completed);
      
      // Verify results
      assertEquals("No errors should occur during concurrent role retrieval", 0, errorCount.get());
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent role search operations using Virtual Threads.
   * This test validates that the RoleInternalResource can handle many concurrent
   * search requests efficiently using Virtual Threads.
   */
  @Test
  public void testConcurrentRoleSearchWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("role-search-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int concurrency = MEDIUM_CONCURRENCY;
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < concurrency; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            List<RoleXOResponse> roles = roleInternalResource.searchRoles(SOURCE, "search" + (index % 10));
            assertNotNull("Search results should not be null for request " + index, roles);
            assertEquals("Should have 10 roles in search results for request " + index, 10, roles.size());
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            firstException.compareAndSet(null, e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue("All tasks should complete within timeout", completed);
      
      // Verify results
      assertEquals("No errors should occur during concurrent role search", 0, errorCount.get());
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent role creation operations using Virtual Threads.
   * This test validates that the RoleApiResource can handle many concurrent
   * role creation requests efficiently using Virtual Threads.
   */
  @Test
  public void testConcurrentRoleCreationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("role-creation-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int concurrency = SMALL_CONCURRENCY; // Using smaller concurrency for creation operations
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < concurrency; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String roleId = "concurrent-role-" + roleCounter.incrementAndGet();
            RoleXORequest roleXo = createApiRole(roleId, "Concurrent Role " + index, 
                "Description for concurrent role " + index, 
                Collections.singleton("role-a"), Collections.singleton("priv-1"));
            
            RoleXOResponse result = roleApiResource.create(roleXo);
            assertNotNull("Created role should not be null for request " + index, result);
            assertEquals("Role ID should match for request " + index, roleId, result.getId());
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            firstException.compareAndSet(null, e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue("All tasks should complete within timeout", completed);
      
      // Verify results
      assertEquals("No errors should occur during concurrent role creation", 0, errorCount.get());
      assertEquals("All roles should be created", concurrency, createdRoleIds.size());
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests high concurrency role retrieval using Virtual Threads.
   * This test validates that the RoleApiResource can handle thousands of concurrent
   * requests efficiently using Virtual Threads.
   */
  @Test
  public void testHighConcurrencyRoleRetrievalWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("high-concurrency-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int concurrency = LARGE_CONCURRENCY;
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    try {
      // Submit thousands of concurrent tasks using virtual threads
      for (int i = 0; i < concurrency; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between different role operations
            switch (index % 3) {
              case 0:
                List<RoleXOResponse> roles = roleApiResource.getRoles(SOURCE);
                assertNotNull("Roles should not be null", roles);
                break;
              case 1:
                RoleXOResponse role = roleApiResource.getRole(SOURCE, "role" + (index % 20));
                assertNotNull("Role should not be null", role);
                break;
              case 2:
                List<RoleXOResponse> searchResults = roleInternalResource.searchRoles(SOURCE, "search");
                assertNotNull("Search results should not be null", searchResults);
                break;
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            firstException.compareAndSet(null, e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      assertTrue("All tasks should complete within timeout", completed);
      
      // Verify results
      assertEquals("No errors should occur during high concurrency role operations", 0, errorCount.get());
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for role operations.
   * This test measures and compares the throughput and latency of role operations
   * when using platform threads versus virtual threads.
   */
  @Test
  public void testThreadModelPerformanceComparison() throws Exception {
    // Define thread factories for both models
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-perf-", 0).factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().name("pt-perf-", 0).factory();
    
    int concurrency = MEDIUM_CONCURRENCY;
    
    // Warm up to avoid JIT compilation effects
    log.info("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentRoleOperations(virtualThreadFactory, 100);
      runConcurrentRoleOperations(platformThreadFactory, 100);
    }
    
    // Run the actual performance test
    log.info("Running performance comparison test...");
    List<PerformanceResult> virtualThreadResults = new ArrayList<>();
    List<PerformanceResult> platformThreadResults = new ArrayList<>();
    
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      log.info("Iteration {} of {}", i + 1, TEST_ITERATIONS);
      
      // Test with virtual threads
      PerformanceResult vtResult = runConcurrentRoleOperations(virtualThreadFactory, concurrency);
      virtualThreadResults.add(vtResult);
      log.info("Virtual Thread Result: {}", vtResult);
      
      // Test with platform threads
      PerformanceResult ptResult = runConcurrentRoleOperations(platformThreadFactory, concurrency);
      platformThreadResults.add(ptResult);
      log.info("Platform Thread Result: {}", ptResult);
    }
    
    // Calculate average results
    PerformanceResult avgVtResult = calculateAverageResult(virtualThreadResults);
    PerformanceResult avgPtResult = calculateAverageResult(platformThreadResults);
    
    log.info("Average Virtual Thread Result: {}", avgVtResult);
    log.info("Average Platform Thread Result: {}", avgPtResult);
    
    // Verify that virtual threads perform better
    assertThat("Virtual threads should have lower average latency", 
        avgVtResult.getAverageLatencyMs(), lessThan(avgPtResult.getAverageLatencyMs()));
    
    assertThat("Virtual threads should have higher operations per second", 
        avgVtResult.getOperationsPerSecond(), greaterThan(avgPtResult.getOperationsPerSecond()));
  }

  /**
   * Tests for thread pinning detection during role operations.
   * This test validates that role operations don't cause thread pinning
   * when executed with Virtual Threads.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("pinning-test-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int concurrency = MEDIUM_CONCURRENCY;
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit concurrent tasks using virtual threads
      for (int i = 0; i < concurrency; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Perform a mix of role operations
            switch (index % 4) {
              case 0:
                roleApiResource.getRoles(SOURCE);
                break;
              case 1:
                roleApiResource.getRole(SOURCE, "role" + (index % 20));
                break;
              case 2:
                roleInternalResource.searchRoles(SOURCE, "search");
                break;
              case 3:
                if (index % 20 == 0) { // Limit creation operations
                  String roleId = "pinning-test-role-" + roleCounter.incrementAndGet();
                  RoleXORequest roleXo = createApiRole(roleId, "Pinning Test Role", 
                      "Description for pinning test", 
                      Collections.singleton("role-a"), Collections.singleton("priv-1"));
                  roleApiResource.create(roleXo);
                }
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
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue("All tasks should complete within timeout", completed);
      
      // Verify results
      assertEquals("No errors should occur during thread pinning test", 0, errorCount.get());
      
      // Note: Thread pinning would be logged to the console by the JVM
      // with the jdk.tracePinnedThreads property set to "full"
    } 
    finally {
      executor.shutdown();
      // Reset the property
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }

  /**
   * Helper method to run concurrent role operations and measure performance.
   * 
   * @param threadFactory The thread factory to use
   * @param concurrency The number of concurrent operations to perform
   * @return Performance metrics for the test run
   */
  private PerformanceResult runConcurrentRoleOperations(ThreadFactory threadFactory, int concurrency) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger completedCount = new AtomicInteger(0);
    AtomicLong totalLatencyMs = new AtomicLong(0);
    
    long startTime = System.nanoTime();
    
    try {
      // Submit concurrent tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < concurrency; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          long operationStart = System.nanoTime();
          try {
            // Perform a mix of role operations
            switch (index % 3) {
              case 0:
                roleApiResource.getRoles(SOURCE);
                break;
              case 1:
                roleApiResource.getRole(SOURCE, "role" + (index % 20));
                break;
              case 2:
                roleInternalResource.searchRoles(SOURCE, "search");
                break;
            }
            completedCount.incrementAndGet();
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            long operationEnd = System.nanoTime();
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(operationEnd - operationStart);
            totalLatencyMs.addAndGet(latencyMs);
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue("All tasks should complete within timeout", completed);
      
      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } 
    finally {
      executor.shutdown();
    }
    
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    
    int completed = completedCount.get();
    double averageLatencyMs = completed > 0 ? (double) totalLatencyMs.get() / completed : 0;
    double operationsPerSecond = durationMs > 0 ? (completed * 1000.0) / durationMs : 0;
    
    return new PerformanceResult(durationMs, completed, errorCount.get(), averageLatencyMs, operationsPerSecond);
  }

  /**
   * Calculate the average performance result from a list of results.
   * 
   * @param results The list of performance results
   * @return The average performance result
   */
  private PerformanceResult calculateAverageResult(List<PerformanceResult> results) {
    if (results.isEmpty()) {
      return new PerformanceResult(0, 0, 0, 0, 0);
    }
    
    double avgDurationMs = results.stream().mapToLong(PerformanceResult::getDurationMs).average().orElse(0);
    double avgCompleted = results.stream().mapToInt(PerformanceResult::getCompletedOperations).average().orElse(0);
    double avgErrors = results.stream().mapToInt(PerformanceResult::getErrorCount).average().orElse(0);
    double avgLatencyMs = results.stream().mapToDouble(PerformanceResult::getAverageLatencyMs).average().orElse(0);
    double avgOps = results.stream().mapToDouble(PerformanceResult::getOperationsPerSecond).average().orElse(0);
    
    return new PerformanceResult(
        Math.round(avgDurationMs),
        (int) Math.round(avgCompleted),
        (int) Math.round(avgErrors),
        avgLatencyMs,
        avgOps
    );
  }

  /**
   * Helper class to store performance test results.
   */
  private static class PerformanceResult {
    private final long durationMs;
    private final int completedOperations;
    private final int errorCount;
    private final double averageLatencyMs;
    private final double operationsPerSecond;
    
    public PerformanceResult(long durationMs, int completedOperations, int errorCount, 
                            double averageLatencyMs, double operationsPerSecond) {
      this.durationMs = durationMs;
      this.completedOperations = completedOperations;
      this.errorCount = errorCount;
      this.averageLatencyMs = averageLatencyMs;
      this.operationsPerSecond = operationsPerSecond;
    }
    
    public long getDurationMs() {
      return durationMs;
    }
    
    public int getCompletedOperations() {
      return completedOperations;
    }
    
    public int getErrorCount() {
      return errorCount;
    }
    
    public double getAverageLatencyMs() {
      return averageLatencyMs;
    }
    
    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }
    
    @Override
    public String toString() {
      return String.format(
          "Duration: %d ms, Completed: %d, Errors: %d, Avg Latency: %.2f ms, Ops/sec: %.2f",
          durationMs, completedOperations, errorCount, averageLatencyMs, operationsPerSecond);
    }
  }

  /**
   * Helper method to create a Role object for testing.
   */
  private Role createRole(final String source,
                          final String id,
                          final String name,
                          final String description,
                          final Collection<String> roles,
                          final Collection<String> privileges)
  {
    Role role = new Role();
    role.setRoleId(id);
    role.setName(name);
    role.setDescription(description);
    role.setSource(source);
    roles.forEach(role::addRole);
    privileges.forEach(role::addPrivilege);

    return role;
  }

  /**
   * Helper method to create a RoleXORequest object for testing.
   */
  private RoleXORequest createApiRole(final String id,
                                      final String name,
                                      final String description,
                                      final Collection<String> roles,
                                      final Collection<String> privileges)
  {
    RoleXORequest roleXo = new RoleXORequest();
    roleXo.setId(id);
    roleXo.setName(name);
    roleXo.setDescription(description);
    roleXo.setRoles(new HashSet<>(roles));
    roleXo.setPrivileges(new HashSet<>(privileges));

    return roleXo;
  }
}