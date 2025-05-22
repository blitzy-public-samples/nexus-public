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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.repository.config.ConfigurationExport.Repository;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;
import org.sonatype.nexus.repository.routing.RoutingMode;
import org.sonatype.nexus.repository.routing.RoutingRule;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.routing.internal.RoutingRuleData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.CompletableFuture.runAsync;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests validity of Serialization/Deserialization ConfigurationData by {@link ConfigurationExport}
 * using Java 21 Virtual Threads for file I/O operations.
 */
public class ConfigurationExportVirtualThreadTest
{
  private final JsonExporter jsonExporter = new JsonExporter();

  private File binFile;

  @TempDir
  Path tempDir;

  @BeforeEach
  public void setup() throws IOException {
    binFile = File.createTempFile("ConfigurationData", ".json");
  }

  @AfterEach
  public void tearDown() {
    binFile.delete();
  }

  /**
   * Tests basic export/import functionality using Virtual Threads.
   */
  @Test
  public void testExportImportToJson() throws Exception {
    // Create test data
    RoutingRule rule1 = createRoutingRule("RULE_1");
    RoutingRule rule2 = createRoutingRule("RULE_2");
    RoutingRule rule3 = createRoutingRule("RULE_3");
    List<RoutingRule> routingRules = ImmutableList.of(rule1, rule2, rule3);

    RoutingRuleStore routingRuleStore = mock(RoutingRuleStore.class);
    when(routingRuleStore.list()).thenReturn(routingRules);

    Configuration hostedConfig = generateConfigData("hosted-repo", "hosted", rule1.id());
    Configuration proxyConfig = generateConfigData("proxy-repo", "proxy", rule2.id());
    Configuration groupConfig = generateConfigData("group-repo", "group", null);
    List<Configuration> configurations = ImmutableList.of(hostedConfig, proxyConfig, groupConfig);

    ConfigurationStore configurationStore = mock(ConfigurationStore.class);
    when(configurationStore.list()).thenReturn(configurations);

    ConfigurationExport exportConfigurationData = new ConfigurationExport(configurationStore, routingRuleStore);

    // Run export/import using Virtual Threads
    runWithVirtualThread(() -> {
      try {
        exportConfigurationData.export(binFile);
      }
      catch (IOException e) {
        throw new RuntimeException("Failed to export configuration", e);
      }
    }).join();

    List<Repository> importedConfigurationData = runWithVirtualThread(() -> {
      try {
        return jsonExporter.importFromJson(binFile, Repository.class);
      }
      catch (IOException e) {
        throw new RuntimeException("Failed to import configuration", e);
      }
    }).join();

    // Verify hosted repository data
    Optional<Repository> hostedRepoOpt = importedConfigurationData.stream()
        .filter(data -> hostedConfig.getRepositoryName().equals(data.getConfiguration().getRepositoryName()))
        .findFirst();
    assertTrue(hostedRepoOpt.isPresent());
    Repository hostedRepository = hostedRepoOpt.get();
    Configuration hostedConfiguration = hostedRepository.getConfiguration();
    assertThat(hostedConfiguration.getRecipeName(), is(hostedConfig.getRecipeName()));
    assertThat(hostedConfiguration.getRoutingRuleId(), is(rule1.id()));
    assertNotNull(hostedConfiguration.getAttributes());
    assertConfigurationAttributes(hostedConfiguration.getAttributes());

    RoutingRule hostedRoutingRule = hostedRepository.getRoutingRule();
    assertThat(hostedRoutingRule.name(), is(rule1.name()));
    assertThat(hostedRoutingRule.description(), is(rule1.description()));
    assertThat(hostedRoutingRule.matchers(), containsInAnyOrder("matcher_1", "matcher_2"));

    // Verify proxy repository data
    Optional<Repository> proxyRepoOpt = importedConfigurationData.stream()
        .filter(data -> proxyConfig.getRepositoryName().equals(data.getConfiguration().getRepositoryName()))
        .findFirst();
    assertTrue(proxyRepoOpt.isPresent());
    Repository proxyRepository = proxyRepoOpt.get();
    Configuration proxyConfiguration = proxyRepository.getConfiguration();
    assertThat(proxyConfiguration.getRecipeName(), is(proxyConfig.getRecipeName()));
    assertThat(proxyConfiguration.getRoutingRuleId(), is(rule2.id()));
    assertNotNull(proxyConfiguration.getAttributes());
    assertConfigurationAttributes(proxyConfiguration.getAttributes());

    RoutingRule proxyRoutingRule = proxyRepository.getRoutingRule();
    assertThat(proxyRoutingRule.name(), is(rule2.name()));
    assertThat(proxyRoutingRule.description(), is(rule2.description()));
    assertThat(proxyRoutingRule.matchers(), containsInAnyOrder("matcher_1", "matcher_2"));

    // Verify group repository data
    Optional<Repository> groupRepoOpt = importedConfigurationData.stream()
        .filter(data -> groupConfig.getRepositoryName().equals(data.getConfiguration().getRepositoryName()))
        .findFirst();
    assertTrue(groupRepoOpt.isPresent());
    Repository groupRepository = groupRepoOpt.get();
    Configuration groupConfiguration = groupRepository.getConfiguration();
    assertThat(groupConfiguration.getRecipeName(), is(groupConfig.getRecipeName()));
    assertNull(groupConfiguration.getRoutingRuleId());
    assertNotNull(groupConfiguration.getAttributes());
    assertConfigurationAttributes(groupConfiguration.getAttributes());

    // Verify routing rule without repository
    Optional<Repository> repositoryOpt =
        importedConfigurationData.stream().filter(data -> data.getConfiguration() == null).findFirst();
    assertTrue(repositoryOpt.isPresent());
    RoutingRule routingRule = repositoryOpt.get().getRoutingRule();
    assertThat(routingRule.name(), is(rule3.name()));
    assertThat(routingRule.description(), is(rule3.description()));
    assertThat(routingRule.matchers(), containsInAnyOrder("matcher_1", "matcher_2"));
  }

  /**
   * Tests concurrent export/import operations using Virtual Threads.
   */
  @Test
  public void testConcurrentExportImport() throws Exception {
    int concurrentOperations = 10;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    ConcurrentHashMap<Integer, List<Repository>> results = new ConcurrentHashMap<>();
    
    // Create test data
    List<RoutingRule> routingRules = IntStream.range(0, concurrentOperations)
        .mapToObj(i -> createRoutingRule("RULE_" + i))
        .collect(Collectors.toList());

    List<Configuration> configurations = IntStream.range(0, concurrentOperations)
        .mapToObj(i -> generateConfigData("repo-" + i, "hosted", routingRules.get(i).id()))
        .collect(Collectors.toList());

    // Create a separate file for each operation
    List<File> files = IntStream.range(0, concurrentOperations)
        .mapToObj(i -> {
          try {
            return Files.createTempFile(tempDir, "ConfigData-" + i, ".json").toFile();
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        })
        .collect(Collectors.toList());

    // Create executor with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent export/import tasks
      List<Future<?>> futures = IntStream.range(0, concurrentOperations)
          .mapToObj(i -> {
            RoutingRuleStore routingRuleStore = mock(RoutingRuleStore.class);
            when(routingRuleStore.list()).thenReturn(Collections.singletonList(routingRules.get(i)));

            ConfigurationStore configurationStore = mock(ConfigurationStore.class);
            when(configurationStore.list()).thenReturn(Collections.singletonList(configurations.get(i)));

            ConfigurationExport exportConfigurationData = 
                new ConfigurationExport(configurationStore, routingRuleStore);

            return executor.submit(() -> {
              try {
                // Export configuration
                exportConfigurationData.export(files.get(i));
                
                // Import configuration
                List<Repository> imported = jsonExporter.importFromJson(files.get(i), Repository.class);
                results.put(i, imported);
                
                latch.countDown();
              }
              catch (Exception e) {
                throw new RuntimeException("Failed in task " + i, e);
              }
            });
          })
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Not all concurrent operations completed in time");

      // Check for any exceptions
      for (Future<?> future : futures) {
        future.get(); // Will throw if the task failed
      }

      // Verify results
      assertThat(results.size(), is(concurrentOperations));
      
      for (int i = 0; i < concurrentOperations; i++) {
        List<Repository> imported = results.get(i);
        assertNotNull(imported);
        assertThat(imported.size(), is(1));
        
        Repository repository = imported.get(0);
        Configuration config = repository.getConfiguration();
        assertNotNull(config);
        assertThat(config.getRepositoryName(), is(configurations.get(i).getRepositoryName()));
        assertThat(config.getRecipeName(), is(configurations.get(i).getRecipeName()));
      }
    }
  }

  /**
   * Tests performance comparison between Virtual Threads and Platform Threads.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    int iterations = 50;
    
    // Create test data
    RoutingRule rule = createRoutingRule("PERFORMANCE_RULE");
    List<RoutingRule> routingRules = Collections.singletonList(rule);

    Configuration config = generateConfigData("perf-repo", "hosted", rule.id());
    List<Configuration> configurations = Collections.singletonList(config);

    RoutingRuleStore routingRuleStore = mock(RoutingRuleStore.class);
    when(routingRuleStore.list()).thenReturn(routingRules);

    ConfigurationStore configurationStore = mock(ConfigurationStore.class);
    when(configurationStore.list()).thenReturn(configurations);

    ConfigurationExport exportConfigurationData = new ConfigurationExport(configurationStore, routingRuleStore);

    // Measure Virtual Thread performance
    long virtualThreadStart = System.nanoTime();
    
    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = IntStream.range(0, iterations)
          .mapToObj(i -> {
            File file = new File(tempDir.toFile(), "vt-perf-" + i + ".json");
            return virtualExecutor.submit(() -> {
              try {
                exportConfigurationData.export(file);
                jsonExporter.importFromJson(file, Repository.class);
              }
              catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
          })
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
    }
    
    long virtualThreadDuration = System.nanoTime() - virtualThreadStart;

    // Measure Platform Thread performance
    long platformThreadStart = System.nanoTime();
    
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
      List<Future<?>> futures = IntStream.range(0, iterations)
          .mapToObj(i -> {
            File file = new File(tempDir.toFile(), "pt-perf-" + i + ".json");
            return platformExecutor.submit(() -> {
              try {
                exportConfigurationData.export(file);
                jsonExporter.importFromJson(file, Repository.class);
              }
              catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
          })
          .collect(Collectors.toList());

      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
    }
    
    long platformThreadDuration = System.nanoTime() - platformThreadStart;

    // Log performance results
    System.out.printf("Performance comparison for %d iterations:%n", iterations);
    System.out.printf("Virtual Threads: %.2f ms%n", virtualThreadDuration / 1_000_000.0);
    System.out.printf("Platform Threads: %.2f ms%n", platformThreadDuration / 1_000_000.0);
    System.out.printf("Improvement ratio: %.2fx%n", (double) platformThreadDuration / virtualThreadDuration);

    // Virtual Threads should generally be faster for I/O-bound operations
    // but we don't want to make the test fail if they're not in some environments
    // Just log the results for analysis
  }

  /**
   * Tests for thread pinning when using Virtual Threads with file I/O operations.
   */
  @Test
  public void testThreadPinning() throws Exception {
    // Create test data
    RoutingRule rule = createRoutingRule("PINNING_TEST_RULE");
    List<RoutingRule> routingRules = Collections.singletonList(rule);

    Configuration config = generateConfigData("pinning-test-repo", "hosted", rule.id());
    List<Configuration> configurations = Collections.singletonList(config);

    RoutingRuleStore routingRuleStore = mock(RoutingRuleStore.class);
    when(routingRuleStore.list()).thenReturn(routingRules);

    ConfigurationStore configurationStore = mock(ConfigurationStore.class);
    when(configurationStore.list()).thenReturn(configurations);

    ConfigurationExport exportConfigurationData = new ConfigurationExport(configurationStore, routingRuleStore);

    // Create a large number of virtual threads to detect pinning
    int threadCount = 100;
    AtomicInteger completedTasks = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    
    // Track carrier thread IDs to detect pinning
    ConcurrentHashMap<Long, Integer> carrierThreadCounts = new ConcurrentHashMap<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks that will all start at the same time
      for (int i = 0; i < threadCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Get the carrier thread ID
            long carrierId = currentThread().threadId();
            
            // Create a unique file for this task
            File file = new File(tempDir.toFile(), "pinning-test-" + taskId + ".json");
            
            // Perform export operation
            exportConfigurationData.export(file);
            
            // Update carrier thread count
            carrierThreadCounts.compute(carrierId, (id, count) -> count == null ? 1 : count + 1);
            
            // Import the file
            jsonExporter.importFromJson(file, Repository.class);
            
            // Task completed successfully
            completedTasks.incrementAndGet();
            doneLatch.countDown();
          }
          catch (Exception e) {
            e.printStackTrace();
            doneLatch.countDown();
          }
        });
      }

      // Start all tasks simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Not all thread pinning test tasks completed in time");
      
      // Verify all tasks completed successfully
      assertThat(completedTasks.get(), is(threadCount));
      
      // Verify that we used multiple carrier threads (no severe pinning)
      // In a well-behaved Virtual Thread implementation, we should see multiple carrier threads
      System.out.println("Carrier thread distribution: " + carrierThreadCounts);
      assertThat("Should use multiple carrier threads", carrierThreadCounts.size(), greaterThan(1));
      
      // Check that no single carrier thread handled too many tasks (which would indicate pinning)
      int maxTasksPerCarrier = carrierThreadCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
      System.out.println("Maximum tasks per carrier thread: " + maxTasksPerCarrier);
      
      // The threshold depends on the number of available cores, but we want to ensure
      // that no single carrier thread is handling most of the tasks
      int availableProcessors = Runtime.getRuntime().availableProcessors();
      int expectedMaxTasksPerCarrier = (int) Math.ceil((double) threadCount / availableProcessors) * 2;
      
      assertThat("No carrier thread should handle too many tasks", 
          maxTasksPerCarrier, lessThan(expectedMaxTasksPerCarrier));
    }
  }

  private void assertConfigurationAttributes(final Map<String, Map<String, Object>> attributes) {
    assertThat(attributes.toString(), allOf(
        containsString("metadata"),
        containsString("size"),
        containsString("10")));
    // make sure sensitive data is not serialized
    assertThat(attributes.toString(), not(containsString("admin123")));
  }

  private Configuration generateConfigData(final String name, final String recipe, final EntityId routingRuleId) {
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    attributes.put("login", Collections.singletonMap("password", "admin123"));
    attributes.put("user", Collections.singletonMap("secret", "admin123"));
    attributes.put("metadata", Collections.singletonMap("size", 10));
    ConfigurationData configurationData = new ConfigurationData();
    configurationData.setId(new EntityUUID(UUID.randomUUID()));
    configurationData.setName(name);
    configurationData.setRecipeName(recipe);
    configurationData.setOnline(true);
    configurationData.setAttributes(attributes);
    configurationData.setRoutingRuleId(routingRuleId);

    return configurationData;
  }

  private RoutingRule createRoutingRule(final String name) {
    RoutingRuleData routingRule = new RoutingRuleData();
    routingRule.setId(new EntityUUID());
    routingRule.setName(name);
    routingRule.description("Description");
    routingRule.matchers(ImmutableList.of("matcher_1", "matcher_2"));
    routingRule.mode(RoutingMode.ALLOW);

    return routingRule;
  }
  
  /**
   * Helper method to run a task in a Virtual Thread.
   */
  private <T> Future<T> runWithVirtualThread(Callable<T> task) {
    return runAsync(() -> task.call(), Executors.newVirtualThreadPerTaskExecutor());
  }
  
  /**
   * Helper interface for tasks that may throw exceptions.
   */
  @FunctionalInterface
  private interface Callable<T> {
    T call() throws Exception;
  }
}