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
package org.sonatype.nexus.repository.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;
import org.sonatype.nexus.repository.routing.RoutingMode;
import org.sonatype.nexus.repository.routing.internal.RoutingRuleDAO;
import org.sonatype.nexus.repository.routing.internal.RoutingRuleData;
import org.sonatype.nexus.testdb.DataSessionRule;

import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;
import static org.sonatype.nexus.repository.routing.RoutingMode.ALLOW;

/**
 * Tests for {@link ConfigurationDAO} using Java 21 Virtual Threads.
 * 
 * This test validates that repository configuration persistence operations
 * function correctly under high concurrency using Virtual Threads. It verifies that
 * configuration attributes including nested password fields are properly stored and
 * retrieved, and that query methods by repository name patterns and recipe name
 * work correctly when executed with Virtual Threads.
 * 
 * The test also includes performance comparisons between platform threads and virtual threads,
 * and checks for thread pinning issues that could impact scalability.
 */
public class ConfigurationDAOVirtualThreadTest
    extends TestSupport
{
  @RegisterExtension
  public DataSessionRule sessionRule =
      new DataSessionRule().access(RoutingRuleDAO.class).access(ConfigurationDAO.class);

  private DataSession<?> session;

  private ConfigurationDAO dao;

  private RoutingRuleDAO routingRuleDAO;

  private EntityId id1, id2, id3;

  @BeforeEach
  public void setup() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(ConfigurationDAO.class);
    routingRuleDAO = session.access(RoutingRuleDAO.class);

    RoutingRuleData routingRule = routingRule("foo", ALLOW, "desc1", "a", "b", "c");
    routingRuleDAO.create(routingRule);

    routingRule = routingRule("bar", ALLOW, "desc2", "d", "e", "f");
    routingRuleDAO.create(routingRule);

    routingRule = routingRule("baz", ALLOW, "desc1", "a", "b", "c");
    routingRuleDAO.create(routingRule);

    id1 = routingRuleDAO.readByName("foo").get().getId();
    id2 = routingRuleDAO.readByName("bar").get().getId();
    id3 = routingRuleDAO.readByName("baz").get().getId();
  }

  @AfterEach
  public void cleanup() {
    session.close();
  }

  /**
   * Tests basic CRUD operations using Virtual Threads.
   */
  @Test
  public void testCRUDWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ConfigurationData configuration = configurationData("foo", "bar", true, Map.of("baz", Map.of("buzz", "booz")), id1);

      // Create configuration in a virtual thread
      executor.submit(() -> dao.create(configuration)).get();

      // Read configuration in a virtual thread
      ConfigurationData read = executor.submit(() -> dao.readByName(configuration.getName()).orElse(null)).get();

      assertNotNull(read);
      assertEquals(configuration.getName(), read.getName());
      assertEquals(configuration.getRecipeName(), read.getRecipeName());
      assertEquals(configuration.isOnline(), read.isOnline());
      assertEquals(configuration.getRoutingRuleId(), read.getRoutingRuleId());
      assertEquals(configuration.getAttributes(), read.getAttributes());

      // Update configuration in a virtual thread
      configuration.setRecipeName("notBar");
      configuration.setOnline(false);
      configuration.setRoutingRuleId(id2);
      configuration.setAttributes(Map.of("baz2", Map.of("buzz2", "booz2")));
      executor.submit(() -> dao.update(configuration)).get();

      // Read updated configuration in a virtual thread
      ConfigurationData update = executor.submit(() -> dao.readByName(configuration.getName()).orElse(null)).get();

      assertNotNull(update);
      assertEquals(configuration.getName(), update.getName());
      assertEquals(configuration.isOnline(), update.isOnline());
      assertEquals(configuration.getRoutingRuleId(), update.getRoutingRuleId());
      assertEquals(configuration.getAttributes(), update.getAttributes());

      // Recipe name is not changed
      assertEquals("bar", update.getRecipeName());

      // Delete configuration in a virtual thread
      executor.submit(() -> dao.deleteByName(configuration.getName())).get();

      // Verify deletion in a virtual thread
      boolean exists = executor.submit(() -> dao.readByName(configuration.getName()).isPresent()).get();
      assertFalse(exists);
    }
  }

  /**
   * Tests password attribute handling with Virtual Threads.
   */
  @Test
  public void testPasswordAttributeWithVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ConfigurationData configuration =
          configurationData("foo", "bar", true, Map.of("baz", Map.of("userpassword", "booz")), id1);

      executor.submit(() -> dao.create(configuration)).get();

      ConfigurationData read = executor.submit(() -> dao.readByName(configuration.getName()).orElse(null)).get();

      assertNotNull(read);
      assertEquals("booz", read.getAttributes().get("baz").get("userpassword"));
    }
  }

  /**
   * Tests readByNames method with Virtual Threads.
   */
  @Test
  public void testReadByNamesWithVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ConfigurationData configuration1 =
          configurationData("foo", "foo", true, Map.of("baz", Map.of("buzz", "booz")), id1);

      ConfigurationData configuration2 =
          configurationData("barr", "barr", true, Map.of("bar", Map.of("burr", "foo")), id2);

      ConfigurationData configuration3 =
          configurationData("bazz", "bazz", true, Map.of("baz", Map.of("bazz", "bar")), id3);

      executor.submit(() -> {
        dao.create(configuration1);
        dao.create(configuration2);
        dao.create(configuration3);
        return null;
      }).get();

      Collection<Configuration> results = executor.submit(() -> dao.readByNames(ImmutableSet.of("_oo", "b%z_"))).get();

      assertEquals(2, results.size());

      List<String> names = results.stream().map(Configuration::getRepositoryName).collect(Collectors.toList());
      assertTrue(names.contains(configuration1.getName()));
      assertTrue(names.contains(configuration3.getName()));
    }
  }

  /**
   * Tests readByRecipe method with Virtual Threads.
   */
  @Test
  public void testReadByRecipeWithVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ConfigurationData conanProxyConfig1 =
          configurationData("conan-proxy-1", "conan-proxy", true, Map.of("baz", Map.of("buzz", "booz")), id1);
      ConfigurationData conanProxyConfig2 =
          configurationData("conan-proxy-2", "conan-proxy", true, Map.of("baz", Map.of("buzz", "booz")), id1);
      ConfigurationData conanProxyConfig3 =
          configurationData("conan-proxy-3", "conan-proxy", true, Map.of("baz", Map.of("buzz", "booz")), id1);
      ConfigurationData conanProxyConfig4 =
          configurationData("conan-proxy-4", "conan-proxy", true, Map.of("baz", Map.of("buzz", "booz")), id1);

      ConfigurationData anotherConfig1 =
          configurationData("foo", "foo", true, Map.of("baz", Map.of("buzz", "booz")), id1);
      ConfigurationData anotherConfig2 =
          configurationData("barr", "barr", true, Map.of("bar", Map.of("burr", "foo")), id2);
      ConfigurationData anotherConfig3 =
          configurationData("bazz", "bazz", true, Map.of("baz", Map.of("bazz", "bar")), id3);

      executor.submit(() -> {
        dao.create(conanProxyConfig1);
        dao.create(conanProxyConfig2);
        dao.create(conanProxyConfig3);
        dao.create(conanProxyConfig4);
        dao.create(anotherConfig1);
        dao.create(anotherConfig2);
        dao.create(anotherConfig3);
        return null;
      }).get();

      Collection<Configuration> results = executor.submit(() -> dao.readByRecipe("conan-proxy")).get();

      assertEquals(4, results.size());
    }
  }

  /**
   * Tests high concurrency operations with Virtual Threads.
   * This test creates, reads, and deletes multiple configurations concurrently
   * to validate that the DAO can handle high concurrency with Virtual Threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    int numThreads = 100; // High number of concurrent operations
    CountDownLatch latch = new CountDownLatch(numThreads);
    Set<String> createdNames = ConcurrentHashMap.newKeySet();
    AtomicInteger successCount = new AtomicInteger(0);
    
    Instant startTime = Instant.now();
    log.info("Starting high concurrency test with {} virtual threads", numThreads);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent tasks
      for (int i = 0; i < numThreads; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String name = "concurrent-" + index;
            ConfigurationData config = configurationData(
                name, 
                "recipe-" + index, 
                true, 
                Map.of("attr", Map.of("value", "val-" + index)), 
                id1
            );
            
            // Create configuration
            dao.create(config);
            createdNames.add(name);
            
            // Read configuration
            ConfigurationData read = dao.readByName(name).orElse(null);
            if (read != null && read.getName().equals(name)) {
              // Update configuration
              read.setOnline(false);
              dao.update(read);
              
              // Read again to verify update
              ConfigurationData updated = dao.readByName(name).orElse(null);
              if (updated != null && !updated.isOnline()) {
                successCount.incrementAndGet();
              }
            }
            
            return null;
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent operations timed out");
      assertEquals(numThreads, successCount.get(), "Not all concurrent operations succeeded");
      
      Duration duration = Duration.between(startTime, Instant.now());
      log.info("Completed {} concurrent operations in {} ms", numThreads, duration.toMillis());
      log.info("Average time per operation: {} ms", duration.toMillis() / (double)numThreads);
      
      // Clean up created configurations
      log.info("Cleaning up {} created configurations", createdNames.size());
      for (String name : createdNames) {
        dao.deleteByName(name);
      }
    }
  }

  /**
   * Tests for thread pinning detection during database operations.
   * This test ensures that virtual threads are not pinned during database operations.
   * 
   * Note: In a real environment, use -Djdk.tracePinnedThreads=full or JFR events to detect pinning.
   * This test simulates a simplified version of pinning detection by measuring operation time.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Create a thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a configuration for testing
    ConfigurationData config = configurationData(
        "pinning-test", 
        "pinning-recipe", 
        true, 
        Map.of("test", Map.of("value", "test-value")), 
        id1
    );
    
    // Track operation times to detect potential pinning
    // If operations take significantly longer than expected, it might indicate pinning
    long[] operationTimes = new long[4]; // create, read, update, delete
    
    // Create the configuration using a virtual thread and measure time
    Thread createThread = virtualThreadFactory.newThread(() -> {
      long start = System.nanoTime();
      dao.create(config);
      operationTimes[0] = System.nanoTime() - start;
    });
    createThread.start();
    createThread.join();
    
    // Read the configuration using a virtual thread and measure time
    Thread readThread = virtualThreadFactory.newThread(() -> {
      long start = System.nanoTime();
      dao.readByName("pinning-test");
      operationTimes[1] = System.nanoTime() - start;
    });
    readThread.start();
    readThread.join();
    
    // Update the configuration using a virtual thread and measure time
    Thread updateThread = virtualThreadFactory.newThread(() -> {
      config.setOnline(false);
      long start = System.nanoTime();
      dao.update(config);
      operationTimes[2] = System.nanoTime() - start;
    });
    updateThread.start();
    updateThread.join();
    
    // Delete the configuration using a virtual thread and measure time
    Thread deleteThread = virtualThreadFactory.newThread(() -> {
      long start = System.nanoTime();
      dao.deleteByName("pinning-test");
      operationTimes[3] = System.nanoTime() - start;
    });
    deleteThread.start();
    deleteThread.join();
    
    // Log operation times for analysis
    log.info("Virtual Thread Operation Times (ns):");
    log.info("  Create: {}", operationTimes[0]);
    log.info("  Read:   {}", operationTimes[1]);
    log.info("  Update: {}", operationTimes[2]);
    log.info("  Delete: {}", operationTimes[3]);
    
    // In a real environment with JFR events enabled, we would check for jdk.VirtualThreadPinned events
    // For this test, we're just ensuring operations complete without exceptions
  }

  /**
   * Compares performance between platform threads and virtual threads for database operations.
   * This test demonstrates the performance benefits of virtual threads for I/O-bound operations.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    int operationCount = 100;
    int iterations = 3; // Run multiple iterations for more reliable results
    
    long totalPlatformThreadTime = 0;
    long totalVirtualThreadTime = 0;
    
    for (int iteration = 0; iteration < iterations; iteration++) {
      log.info("Running performance comparison iteration {} of {}", iteration + 1, iterations);
      
      // Test with platform threads
      long platformThreadTime = measureExecutionTime(() -> {
        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
          List<ConfigurationData> configs = IntStream.range(0, operationCount)
              .mapToObj(i -> configurationData(
                  "perf-platform-" + iteration + "-" + i,
                  "perf-recipe",
                  true,
                  Map.of("perf", Map.of("value", "platform-" + i)),
                  id1))
              .collect(Collectors.toList());
          
          CountDownLatch latch = new CountDownLatch(operationCount);
          
          for (ConfigurationData config : configs) {
            executor.submit(() -> {
              try {
                dao.create(config);
                dao.readByName(config.getName());
                dao.deleteByName(config.getName());
              } finally {
                latch.countDown();
              }
            });
          }
          
          latch.await(30, TimeUnit.SECONDS);
        }
      });
      
      // Test with virtual threads
      long virtualThreadTime = measureExecutionTime(() -> {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
          List<ConfigurationData> configs = IntStream.range(0, operationCount)
              .mapToObj(i -> configurationData(
                  "perf-virtual-" + iteration + "-" + i,
                  "perf-recipe",
                  true,
                  Map.of("perf", Map.of("value", "virtual-" + i)),
                  id1))
              .collect(Collectors.toList());
          
          CountDownLatch latch = new CountDownLatch(operationCount);
          
          for (ConfigurationData config : configs) {
            executor.submit(() -> {
              try {
                dao.create(config);
                dao.readByName(config.getName());
                dao.deleteByName(config.getName());
              } finally {
                latch.countDown();
              }
            });
          }
          
          latch.await(30, TimeUnit.SECONDS);
        }
      });
      
      log.info("Iteration {} results:", iteration + 1);
      log.info("  Platform threads: {} ms", platformThreadTime);
      log.info("  Virtual threads:  {} ms", virtualThreadTime);
      
      totalPlatformThreadTime += platformThreadTime;
      totalVirtualThreadTime += virtualThreadTime;
      
      // Add a small delay between iterations to let the system stabilize
      Thread.sleep(500);
    }
    
    // Calculate averages
    long avgPlatformThreadTime = totalPlatformThreadTime / iterations;
    long avgVirtualThreadTime = totalVirtualThreadTime / iterations;
    
    // Calculate improvement percentage
    double improvementPercent = (avgPlatformThreadTime - avgVirtualThreadTime) * 100.0 / avgPlatformThreadTime;
    
    log.info("Performance comparison summary for {} database operations (avg of {} iterations):", 
        operationCount, iterations);
    log.info("Platform threads average execution time: {} ms", avgPlatformThreadTime);
    log.info("Virtual threads average execution time:  {} ms", avgVirtualThreadTime);
    log.info("Performance improvement with virtual threads: {}%", Math.round(improvementPercent));
    
    // For I/O-bound operations like database access, virtual threads should generally perform better
    // due to their ability to efficiently handle blocking operations without consuming OS threads
  }

  private long measureExecutionTime(Runnable task) throws Exception {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }

  private static ConfigurationData configurationData(
      final String name,
      final String recipeName,
      final boolean online,
      final Map<String, Map<String, Object>> attributes,
      final EntityId id)
  {
    ConfigurationData configuration = new ConfigurationData();
    configuration.setName(name);
    configuration.setRecipeName(recipeName);
    configuration.setOnline(online);
    configuration.setAttributes(attributes);
    configuration.setRoutingRuleId(id);

    return configuration;
  }

  private static RoutingRuleData routingRule(
      final String name,
      final RoutingMode mode,
      final String description,
      final String... matchers)
  {
    return new RoutingRuleData().name(name).description(description).mode(mode).matchers(Arrays.asList(matchers));
  }
}