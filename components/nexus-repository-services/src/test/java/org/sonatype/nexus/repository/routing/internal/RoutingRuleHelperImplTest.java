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
package org.sonatype.nexus.repository.routing.internal;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.DetachedEntityId;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.routing.RoutingMode;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RoutingRuleHelperImplTest
    extends TestSupport
{
  private RoutingRuleHelperImpl underTest;

  private RoutingRuleCache cache;

  @Mock
  private RoutingRuleStore routingRuleStore;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private Repository repository;

  @Mock
  RepositoryPermissionChecker repositoryPermissionChecker;

  @BeforeEach
  public void setup() {
    RoutingRuleData block = new RoutingRuleData();
    block.name("block");
    block.description("some description");
    block.mode(RoutingMode.BLOCK);
    block.matchers(Arrays.asList("^/com/sonatype/.*", ".*foobar.*"));


    RoutingRuleData allow = new RoutingRuleData();
    block.name("allow");
    block.description("some description");
    block.mode(RoutingMode.ALLOW);
    block.matchers(Arrays.asList(".*foobar.*", "^/org/apache/.*"));

    when(routingRuleStore.getById("block")).thenReturn(block);
    when(routingRuleStore.getById("allow")).thenReturn(allow);
    when(repository.getName()).thenReturn("test-repo");
    cache = new RoutingRuleCache(routingRuleStore);
    underTest = new RoutingRuleHelperImpl(cache, repositoryManager, repositoryPermissionChecker);
  }

  @Test
  public void testHandle_disabled() throws Exception {
    assertBlocked("block", "/com/sonatype/internal/secrets");
  }

  @Test
  public void testHandle_blockMode() throws Exception {
    assertAllowed("block", "/org/apache/tomcat/catalina");

    assertBlocked("block", "/com/sonatype/internal/secrets");
    assertBlocked("block", "/com/foobar/");
  }

  @Test
  public void testHandle_allowMode() throws Exception {
    assertAllowed("allow", "/org/apache/tomcat/catalina");
    assertAllowed("allow", "/com/foobar/");

    assertBlocked("allow", "/com/sonatype/internal/secrets");
  }
  
  @Test
  public void testRuleTypePatternMatching() throws Exception {
    // Setup test paths
    String apachePath = "/org/apache/tomcat/catalina";
    String sonatypePath = "/com/sonatype/internal/secrets";
    String foobarPath = "/com/foobar/";
    
    // Get rules from cache and use pattern matching to determine behavior
    RoutingRuleData blockRule = cache.get("block");
    RoutingRuleData allowRule = cache.get("allow");
    
    // Using pattern matching for rule type checking
    if (blockRule.mode() instanceof RoutingMode mode) {
      switch (mode) {
        case BLOCK -> {
          // For BLOCK mode, paths matching patterns should be blocked
          assertTrue(isPathMatched(blockRule, sonatypePath));
          assertTrue(isPathMatched(blockRule, foobarPath));
          assertFalse(isPathMatched(blockRule, apachePath));
        }
        case ALLOW -> {
          // This branch shouldn't be reached for blockRule
          fail("Block rule should have BLOCK mode");
        }
      }
    }
    
    if (allowRule.mode() instanceof RoutingMode mode) {
      switch (mode) {
        case ALLOW -> {
          // For ALLOW mode, only paths matching patterns should be allowed
          assertTrue(isPathMatched(allowRule, apachePath));
          assertTrue(isPathMatched(allowRule, foobarPath));
          assertFalse(isPathMatched(allowRule, sonatypePath));
        }
        case BLOCK -> {
          // This branch shouldn't be reached for allowRule
          fail("Allow rule should have ALLOW mode");
        }
      }
    }
  }

  @Test
  public void testHandle_noRuleAssigned() throws Exception {
    configureRepositoryMock(null);

    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository));

    assertThat(underTest.isAllowed(repository, "/com/sonatype/internal/secrets"), is(true));
    assertThat(underTest.calculateAssignedRepositories().size(), is(0));
  }

  @Test
  public void testHandle_nullRepositoryConfiguration() throws Exception {
    Repository repository = mock(Repository.class);
    Configuration configuration = mock(Configuration.class);
    when(repository.getConfiguration()).thenReturn(configuration);
    when(configuration.getRoutingRuleId()).thenReturn(null);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository));

    assertTrue(underTest.isAllowed(repository, "/some/path"));
    assertEquals(0, underTest.calculateAssignedRepositories().size());
  }

  @Test
  public void testAssignedRepositories_singleRepositoryAssigned() throws Exception {
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository));
    configureRepositoryMock("singleRule");

    Map<EntityId, List<Repository>> assignedRepositoryMap = underTest.calculateAssignedRepositories();
    assertEquals(1, assignedRepositoryMap.size());
    List<Repository> assignedRepositories = assignedRepositoryMap.get(new DetachedEntityId("singleRule"));
    assertEquals(ImmutableList.of(repository), assignedRepositories);
  }

  @Test
  public void testAssignedRepositories_multipleRulesAndRepositories() throws Exception {
    Repository repository2 = mock(Repository.class);
    Repository repository3 = mock(Repository.class);

    when(repository2.getName()).thenReturn("test-repo-2");
    when(repository3.getName()).thenReturn("test-repo-3");
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository, repository2, repository3));

    configureRepositoryMock(repository, "rule-1");
    configureRepositoryMock(repository2, "rule-2");
    configureRepositoryMock(repository3, "rule-2");

    Map<EntityId, List<Repository>> assignedRepositoryMap = underTest.calculateAssignedRepositories();
    assertEquals(2, assignedRepositoryMap.size());
    assertEquals(ImmutableList.of(repository), assignedRepositoryMap.get(new DetachedEntityId("rule-1")));
    assertEquals(ImmutableList.of(repository2, repository3), assignedRepositoryMap.get(new DetachedEntityId("rule-2")));
  }

  @Test
  public void testConcurrentRuleEvaluationWithVirtualThreads() throws Exception {
    // Setup test data
    final int numThreads = 1000;
    final String testPath = "/com/sonatype/internal/secrets";
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(numThreads);
    final ConcurrentHashMap<String, Boolean> results = new ConcurrentHashMap<>();
    final AtomicInteger successCounter = new AtomicInteger(0);
    
    // Configure repository with block rule
    configureRepositoryMock("block");
    
    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create executor with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit tasks
      for (int i = 0; i < numThreads; i++) {
        final String threadId = "thread-" + i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            boolean allowed = underTest.isAllowed(repository, testPath);
            results.put(threadId, allowed);
            if (!allowed) {
              successCounter.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread execution", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for completion with timeout
      boolean completed = completionLatch.await(5, TimeUnit.SECONDS);
      assertTrue(completed, "All virtual threads should complete within timeout");
      
      // Verify results
      assertEquals(numThreads, results.size(), "All threads should have produced results");
      assertEquals(numThreads, successCounter.get(), "All evaluations should have blocked the path");
    }
  }

  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    // Setup test data
    final int numThreads = 500;
    final String testPath = "/com/sonatype/internal/secrets";
    configureRepositoryMock("block");
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      runConcurrentTest(numThreads, testPath, Thread.ofPlatform().factory());
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      runConcurrentTest(numThreads, testPath, Thread.ofVirtual().factory());
    });
    
    log.info("Performance comparison for {} concurrent rule evaluations:", numThreads);
    log.info("Platform threads: {} ms", platformThreadTime);
    log.info("Virtual threads: {} ms", virtualThreadTime);
    
    // We don't assert on specific times as they can vary by environment,
    // but we log the results for analysis
  }
  
  private void runConcurrentTest(int numThreads, String testPath, ThreadFactory threadFactory) throws Exception {
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(numThreads);
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      // Submit tasks
      for (int i = 0; i < numThreads; i++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            underTest.isAllowed(repository, testPath);
          } 
          catch (Exception e) {
            log.error("Error in thread execution", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      startLatch.countDown();
      boolean completed = completionLatch.await(5, TimeUnit.SECONDS);
      assertTrue(completed, "All threads should complete within timeout");
    }
  }
  
  private long measureExecutionTime(RunnableWithException task) throws Exception {
    long startTime = System.nanoTime();
    task.run();
    long endTime = System.nanoTime();
    return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
  }
  
  @FunctionalInterface
  private interface RunnableWithException {
    void run() throws Exception;
  }

  private void assertBlocked(final String ruleId, final String path) throws Exception {
    configureRepositoryMock(ruleId);
    assertFalse(underTest.isAllowed(repository, path));
  }

  private void assertAllowed(final String ruleId, final String path) throws Exception {
    configureRepositoryMock(ruleId);
    assertTrue(underTest.isAllowed(repository, path));
  }

  private void configureRepositoryMock(final String repositoryRuleId) {
    configureRepositoryMock(repository, repositoryRuleId);
  }

  private void configureRepositoryMock(final Repository repo, final String repositoryRuleId) {
    Configuration configuration = mock(Configuration.class);
    when(repo.getConfiguration()).thenReturn(configuration);

    if (repositoryRuleId != null) {
      when(configuration.getRoutingRuleId()).thenReturn(new DetachedEntityId(repositoryRuleId));
    }
  }
  
  /**
   * Helper method to check if a path matches any pattern in a routing rule.
   * This is a simplified version of the logic in RoutingRuleHelperImpl for testing purposes.
   */
  private boolean isPathMatched(RoutingRuleData rule, String path) {
    return rule.matchers().stream().anyMatch(pattern -> path.matches(pattern));
  }
}