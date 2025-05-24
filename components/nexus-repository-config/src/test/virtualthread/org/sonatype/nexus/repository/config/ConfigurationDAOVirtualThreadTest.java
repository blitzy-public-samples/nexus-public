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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;
import static org.sonatype.nexus.repository.routing.RoutingMode.ALLOW;

/**
 * Tests for {@link ConfigurationDAO} using Java 21 Virtual Threads.
 * 
 * This test validates that repository configuration persistence operations function correctly
 * when executed with Virtual Threads, ensuring high concurrency without thread pinning.
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
   * Test basic CRUD operations using Virtual Threads.
   */
  @Test
  public void testCRUDWithVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      ConfigurationData configuration = configurationData("foo", "bar", true, Map.of("baz", Map.of("buzz", "booz")), id1);

      dao.create(configuration);

      ConfigurationData read = dao.readByName(configuration.getName()).orElse(null);

      assertThat(read.getName(), is(configuration.getName()));
      assertThat(read.getRecipeName(), is(configuration.getRecipeName()));
      assertThat(read.isOnline(), is(configuration.isOnline()));
      assertThat(read.getRoutingRuleId(), is(configuration.getRoutingRuleId()));
      assertThat(read.getAttributes(), is(configuration.getAttributes()));

      // it is updated
      configuration.setRecipeName("notBar");
      configuration.setOnline(false);
      configuration.setRoutingRuleId(id2);
      configuration.setAttributes(Map.of("baz2", Map.of("buzz2", "booz2")));
      dao.update(configuration);

      // it is read back
      ConfigurationData update = dao.readByName(configuration.getName()).orElse(null);

      // the read value matches the update
      assertThat(update.getName(), is(configuration.getName()));
      assertThat(update.isOnline(), is(configuration.isOnline()));
      assertThat(update.getRoutingRuleId(), is(configuration.getRoutingRuleId()));
      assertThat(update.getAttributes(), is(configuration.getAttributes()));

      // recipe name is not changed
      assertThat(update.getRecipeName(), is("bar"));

      // it is deleted
      dao.deleteByName(configuration.getName());

      // no configuration is found by that name
      assertFalse(dao.readByName(configuration.getName()).isPresent());
    }, virtualThreadFactory);
    
    future.join(); // Wait for completion
  }

  /**
   * Test password attribute handling with Virtual Threads.
   */
  @Test
  public void testPasswordAttributeWithVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      ConfigurationData configuration =
          configurationData("foo", "bar", true, Map.of("baz", Map.of("userpassword", "booz")), id1);

      dao.create(configuration);

      ConfigurationData read = dao.readByName(configuration.getName()).orElse(null);

      assertThat(read.getAttributes().get("baz").get("userpassword"), is("booz"));
      
      // Clean up
      dao.deleteByName(configuration.getName());
    }, virtualThreadFactory);
    
    future.join(); // Wait for completion
  }

  /**
   * Test readByNames operation with Virtual Threads.
   */
  @Test
  public void testReadByNamesWithVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      ConfigurationData configuration1 =
          configurationData("foo", "foo", true, Map.of("baz", Map.of("buzz", "booz")), id1);

      ConfigurationData configuration2 =
          configurationData("barr", "barr", true, Map.of("bar", Map.of("burr", "foo")), id2);

      ConfigurationData configuration3 =
          configurationData("bazz", "bazz", true, Map.of("baz", Map.of("bazz", "bar")), id3);

      dao.create(configuration1);
      dao.create(configuration2);
      dao.create(configuration3);

      Collection<Configuration> results = dao.readByNames(ImmutableSet.of("_oo", "b%z_"));

      assertThat(results, hasSize(2));

      List<String> names = results.stream().map(Configuration::getRepositoryName).toList();
      assertThat(names, containsInAnyOrder(configuration1.getName(), configuration3.getName()));
      
      // Clean up
      dao.deleteByName(configuration1.getName());
      dao.deleteByName(configuration2.getName());
      dao.deleteByName(configuration3.getName());
    }, virtualThreadFactory);
    
    future.join(); // Wait for completion
  }

  /**
   * Test readByRecipe operation with Virtual Threads.
   */
  @Test
  public void testReadByRecipeWithVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
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

      dao.create(conanProxyConfig1);
      dao.create(conanProxyConfig2);
      dao.create(conanProxyConfig3);
      dao.create(conanProxyConfig4);
      dao.create(anotherConfig1);
      dao.create(anotherConfig2);
      dao.create(anotherConfig3);

      Collection<Configuration> results = dao.readByRecipe("conan-proxy");

      assertThat(results, hasSize(4));
      
      // Clean up
      dao.deleteByName(conanProxyConfig1.getName());
      dao.deleteByName(conanProxyConfig2.getName());
      dao.deleteByName(conanProxyConfig3.getName());
      dao.deleteByName(conanProxyConfig4.getName());
      dao.deleteByName(anotherConfig1.getName());
      dao.deleteByName(anotherConfig2.getName());
      dao.deleteByName(anotherConfig3.getName());
    }, virtualThreadFactory);
    
    future.join(); // Wait for completion
  }
  
  /**
   * Test high concurrency operations with Virtual Threads.
   * This test creates, reads, and deletes multiple configurations concurrently using Virtual Threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int operationCount = 100; // Number of concurrent operations
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<String, EntityId> createdConfigs = new ConcurrentHashMap<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String configName = "concurrent-config-" + index;
            ConfigurationData configuration = configurationData(
                configName, 
                "test-recipe", 
                true, 
                Map.of("test", Map.of("value", "concurrent-" + index)), 
                id1);
            
            // Create configuration
            dao.create(configuration);
            createdConfigs.put(configName, configuration.getId());
            
            // Read configuration
            Optional<ConfigurationData> readResult = dao.readByName(configName);
            assertTrue(readResult.isPresent(), "Configuration should be found: " + configName);
            assertThat(readResult.get().getName(), is(configName));
            
            // Update configuration
            configuration.setOnline(false);
            dao.update(configuration);
            
            // Verify update
            readResult = dao.readByName(configName);
            assertTrue(readResult.isPresent(), "Configuration should be found after update: " + configName);
            assertFalse(readResult.get().isOnline(), "Configuration should be offline after update");
          } catch (Exception e) {
            log.error("Error in concurrent operation", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete with timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "All concurrent operations should complete within timeout");
      assertThat(errorCount.get(), is(0));
      assertThat(createdConfigs.size(), is(operationCount));
      
      // Verify all configurations can be read
      Collection<Configuration> allConfigs = dao.readByRecipe("test-recipe");
      assertThat(allConfigs.size(), is(operationCount));
      
      // Clean up all created configurations
      createdConfigs.keySet().forEach(dao::deleteByName);
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test to detect thread pinning during database operations.
   * This test verifies that Virtual Threads are not pinned during database operations.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int operationCount = 50;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicReference<Throwable> pinnedThreadError = new AtomicReference<>();
    
    try {
      // Enable thread pinning detection
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String configName = "pinning-test-" + index;
            ConfigurationData configuration = configurationData(
                configName, 
                "pinning-recipe", 
                true, 
                Map.of("test", Map.of("value", "pinning-" + index)), 
                id1);
            
            // Create configuration
            dao.create(configuration);
            
            // Read configuration
            Optional<ConfigurationData> readResult = dao.readByName(configName);
            assertTrue(readResult.isPresent());
            
            // Delete configuration
            dao.deleteByName(configName);
          } catch (Throwable t) {
            pinnedThreadError.compareAndSet(null, t);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "All operations should complete within timeout");
      
      // Check if any thread pinning was detected
      if (pinnedThreadError.get() != null) {
        throw new AssertionError("Thread pinning detected", pinnedThreadError.get());
      }
    } finally {
      System.clearProperty("jdk.tracePinnedThreads");
      executor.shutdown();
    }
  }
  
  /**
   * Test to compare performance between platform threads and virtual threads.
   * This test measures the execution time for the same operations using both thread types.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    int operationCount = 100;
    int concurrentThreads = 50;
    
    // Run with platform threads
    long platformThreadTime = measureExecutionTime(platformThreadFactory, operationCount, concurrentThreads);
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    
    // Run with virtual threads
    long virtualThreadTime = measureExecutionTime(virtualThreadFactory, operationCount, concurrentThreads);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // At high concurrency, virtual threads should show better performance
    assertThat("Virtual threads should be faster than platform threads", 
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Measures execution time for database operations using the specified thread factory.
   */
  private long measureExecutionTime(
      ThreadFactory threadFactory, 
      int operationCount, 
      int concurrentThreads) throws Exception {
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(operationCount);
    List<String> configNames = IntStream.range(0, operationCount)
        .mapToObj(i -> "perf-config-" + i)
        .collect(Collectors.toList());
    
    try {
      long startTime = System.currentTimeMillis();
      
      // Submit tasks in batches to control concurrency
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String configName = configNames.get(index);
            ConfigurationData configuration = configurationData(
                configName, 
                "perf-recipe", 
                true, 
                Map.of("test", Map.of("value", "perf-" + index)), 
                id1);
            
            // Create configuration
            dao.create(configuration);
            
            // Read configuration
            dao.readByName(configName);
            
            // Delete configuration
            dao.deleteByName(configName);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      latch.await();
      
      return System.currentTimeMillis() - startTime;
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
      
      // Clean up any remaining configurations
      for (String configName : configNames) {
        try {
          dao.deleteByName(configName);
        } catch (Exception e) {
          // Ignore errors during cleanup
        }
      }
    }
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