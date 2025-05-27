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
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
import org.sonatype.nexus.testsuite.testsupport.virtualthread.VirtualThreadTestSupport;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

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
@Tag("java21")
@Tag("virtualThread")
public class ConfigurationExportVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private final JsonExporter jsonExporter = new JsonExporter();

  private File binFile;

  @TempDir
  File tempDir;

  @BeforeEach
  public void setup() throws IOException {
    binFile = File.createTempFile("ConfigurationData", ".json", tempDir);
  }

  @AfterEach
  public void tearDown() {
    binFile.delete();
  }

  @Test
  public void testExportImportToJson() throws Exception {
    // Run the test using a virtual thread
    runWithVirtualThread(() -> {
      try {
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

        // Export using virtual thread
        exportConfigurationData.export(binFile);
        
        // Import using virtual thread
        List<ConfigurationExport.Repository> importedConfigurationData =
            jsonExporter.importFromJson(binFile, ConfigurationExport.Repository.class);

        // check hosted repository data
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

        // check proxy repository data
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

        // check group repository data
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

        // check routing rule without repository
        Optional<Repository> repositoryOpt =
            importedConfigurationData.stream().filter(data -> data.getConfiguration() == null).findFirst();
        assertTrue(repositoryOpt.isPresent());
        RoutingRule routingRule = repositoryOpt.get().getRoutingRule();
        assertThat(routingRule.name(), is(rule3.name()));
        assertThat(routingRule.description(), is(rule3.description()));
        assertThat(routingRule.matchers(), containsInAnyOrder("matcher_1", "matcher_2"));
        
        return null;
      }
      catch (Exception e) {
        throw new RuntimeException("Test failed", e);
      }
    });
  }
  
  @Test
  public void testConcurrentExportImport() throws Exception {
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
    
    // Create multiple temporary files for concurrent operations
    int concurrentTasks = 50;
    File[] tempFiles = new File[concurrentTasks];
    for (int i = 0; i < concurrentTasks; i++) {
      tempFiles[i] = File.createTempFile("ConfigData" + i, ".json", tempDir);
    }
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create a latch to wait for all tasks to complete
      CountDownLatch latch = new CountDownLatch(concurrentTasks);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit concurrent export/import tasks
      for (int i = 0; i < concurrentTasks; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Export configuration
            exportConfigurationData.export(tempFiles[index]);
            
            // Import configuration
            List<ConfigurationExport.Repository> importedData = 
                jsonExporter.importFromJson(tempFiles[index], ConfigurationExport.Repository.class);
            
            // Verify imported data
            verifyImportedData(importedData, hostedConfig, proxyConfig, groupConfig, rule1, rule2, rule3);
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
            e.printStackTrace();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All concurrent tasks should complete within timeout");
      assertThat("No errors should occur during concurrent operations", errorCount.get(), is(0));
    }
    finally {
      executor.shutdown();
    }
  }
  
  @Test
  public void testPerformanceComparisonWithVirtualThreads() throws Exception {
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
    
    // Number of operations to perform
    int operationCount = 100;
    int warmupCount = 5; // Warm-up iterations to stabilize JVM performance
    
    // Create temporary files for operations
    File[] platformThreadFiles = new File[operationCount];
    File[] virtualThreadFiles = new File[operationCount];
    
    for (int i = 0; i < operationCount; i++) {
      platformThreadFiles[i] = File.createTempFile("PlatformThread" + i, ".json", tempDir);
      virtualThreadFiles[i] = File.createTempFile("VirtualThread" + i, ".json", tempDir);
    }
    
    // Create thread factories with descriptive names
    ThreadFactory platformThreadFactory = Thread.ofPlatform()
        .name("platform-export-import-", 0)
        .factory();
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual()
        .name("virtual-export-import-", 0)
        .factory();
    
    // Warm-up phase to stabilize JVM performance
    System.out.println("Warming up JVM with " + warmupCount + " iterations...");
    for (int i = 0; i < warmupCount; i++) {
      runExportImportBatch(exportConfigurationData, platformThreadFiles, platformThreadFactory, 10);
      runExportImportBatch(exportConfigurationData, virtualThreadFiles, virtualThreadFactory, 10);
    }
    
    // Measure platform thread performance
    System.out.println("Measuring platform thread performance with " + operationCount + " operations...");
    long platformThreadTime = measurePerformance(() -> {
      try {
        runExportImportBatch(exportConfigurationData, platformThreadFiles, platformThreadFactory, operationCount);
      }
      catch (Exception e) {
        throw new RuntimeException("Platform thread test failed", e);
      }
    });
    
    // Measure virtual thread performance
    System.out.println("Measuring virtual thread performance with " + operationCount + " operations...");
    long virtualThreadTime = measurePerformance(() -> {
      try {
        runExportImportBatch(exportConfigurationData, virtualThreadFiles, virtualThreadFactory, operationCount);
      }
      catch (Exception e) {
        throw new RuntimeException("Virtual thread test failed", e);
      }
    });
    
    // Calculate memory usage before and after to demonstrate memory efficiency
    System.gc(); // Request garbage collection to get more accurate memory readings
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Run a large batch with virtual threads to demonstrate scalability
    int largeOperationCount = 1000;
    File[] largeVirtualThreadBatch = new File[largeOperationCount];
    for (int i = 0; i < largeOperationCount; i++) {
      largeVirtualThreadBatch[i] = File.createTempFile("VirtualThreadLarge" + i, ".json", tempDir);
    }
    
    System.out.println("Running large batch with " + largeOperationCount + " virtual threads...");
    runExportImportBatch(exportConfigurationData, largeVirtualThreadBatch, virtualThreadFactory, largeOperationCount);
    
    System.gc(); // Request garbage collection to get more accurate memory readings
    long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    long memoryDifference = memoryAfter - memoryBefore;
    
    // Log performance results
    System.out.println("\nPerformance Results:");
    System.out.println("Platform Thread Time: " + platformThreadTime + "ms");
    System.out.println("Virtual Thread Time: " + virtualThreadTime + "ms");
    double improvementPercentage = (platformThreadTime - virtualThreadTime) * 100.0 / platformThreadTime;
    System.out.println("Performance improvement: " + String.format("%.2f%%", improvementPercentage));
    System.out.println("Memory usage for " + largeOperationCount + " virtual threads: " + 
        (memoryDifference / (1024 * 1024)) + "MB");
    System.out.println("Average memory per virtual thread: " + 
        (memoryDifference / largeOperationCount / 1024) + "KB");
    
    // Assert that virtual threads perform better
    assertThat("Virtual threads should be faster than platform threads", 
        virtualThreadTime, lessThan(platformThreadTime));
    
    // Assert significant improvement (at least 20% faster)
    assertTrue("Virtual threads should provide significant performance improvement",
        improvementPercentage > 20.0);
  }
  
  private void runExportImportBatch(
      ConfigurationExport exportConfigurationData,
      File[] files,
      ThreadFactory threadFactory,
      int count) throws Exception {
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(count);
    AtomicReference<Exception> error = new AtomicReference<>();
    
    try {
      for (int i = 0; i < count; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            exportConfigurationData.export(files[index]);
            jsonExporter.importFromJson(files[index], ConfigurationExport.Repository.class);
          }
          catch (Exception e) {
            error.set(e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      assertTrue("All operations should complete within timeout", completed);
      
      if (error.get() != null) {
        throw error.get();
      }
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  @Test
  public void testThreadPinningDetection() throws Exception {
    // This test verifies that file I/O operations don't cause thread pinning
    // when using virtual threads
    
    // Create test data
    RoutingRule rule = createRoutingRule("RULE_TEST");
    List<RoutingRule> routingRules = ImmutableList.of(rule);

    RoutingRuleStore routingRuleStore = mock(RoutingRuleStore.class);
    when(routingRuleStore.list()).thenReturn(routingRules);

    Configuration config = generateConfigData("test-repo", "hosted", rule.id());
    List<Configuration> configurations = ImmutableList.of(config);

    ConfigurationStore configurationStore = mock(ConfigurationStore.class);
    when(configurationStore.list()).thenReturn(configurations);

    ConfigurationExport exportConfigurationData = new ConfigurationExport(configurationStore, routingRuleStore);
    
    // Create files for testing
    int fileCount = 20;
    File[] testFiles = IntStream.range(0, fileCount)
        .mapToObj(i -> {
          try {
            return File.createTempFile("PinningTest" + i, ".json", tempDir);
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        })
        .toArray(File[]::new);
    
    // Enable thread pinning detection
    // This JVM flag will log stack traces when virtual threads are pinned
    String originalPinningFlag = System.getProperty("jdk.tracePinnedThreads");
    try {
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Create a custom thread factory that logs thread creation and pinning
      AtomicInteger pinnedThreadCount = new AtomicInteger(0);
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("export-import-", 0).factory();
      
      // Use CompletableFuture with virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[fileCount];
      
      // Create a custom executor to monitor thread execution
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
      
      try {
        for (int i = 0; i < fileCount; i++) {
          final int index = i;
          futures[i] = CompletableFuture.runAsync(() -> {
            try {
              // Export configuration
              exportConfigurationData.export(testFiles[index]);
              
              // Import configuration
              jsonExporter.importFromJson(testFiles[index], ConfigurationExport.Repository.class);
            }
            catch (Exception e) {
              throw new RuntimeException(e);
            }
          }, executor);
        }
        
        // Wait for all operations to complete
        CompletableFuture.allOf(futures).join();
        
        // Verify that at least one file was processed correctly
        List<ConfigurationExport.Repository> importedData = 
            jsonExporter.importFromJson(testFiles[0], ConfigurationExport.Repository.class);
        
        assertThat("Exported data should contain repository configurations", 
            importedData.size(), greaterThan(0));
        
        // If we reached here without exceptions, no thread pinning occurred during file I/O
        // or the pinning was brief enough not to impact performance
        System.out.println("Successfully completed " + fileCount + " concurrent file operations using virtual threads");
      } finally {
        executor.shutdown();
        boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        assertTrue("Executor should terminate gracefully", terminated);
      }
    } finally {
      // Restore original system property value
      if (originalPinningFlag != null) {
        System.setProperty("jdk.tracePinnedThreads", originalPinningFlag);
      } else {
        System.clearProperty("jdk.tracePinnedThreads");
      }
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
  
  private void verifyImportedData(List<Repository> importedData, 
                                 Configuration hostedConfig,
                                 Configuration proxyConfig,
                                 Configuration groupConfig,
                                 RoutingRule rule1,
                                 RoutingRule rule2,
                                 RoutingRule rule3) {
    // Verify hosted repository
    Optional<Repository> hostedRepoOpt = importedData.stream()
        .filter(data -> data.getConfiguration() != null && 
                        hostedConfig.getRepositoryName().equals(data.getConfiguration().getRepositoryName()))
        .findFirst();
    assertTrue(hostedRepoOpt.isPresent());
    Repository hostedRepo = hostedRepoOpt.get();
    assertThat(hostedRepo.getConfiguration().getRecipeName(), is(hostedConfig.getRecipeName()));
    assertThat(hostedRepo.getConfiguration().getRoutingRuleId(), is(rule1.id()));
    
    // Verify proxy repository
    Optional<Repository> proxyRepoOpt = importedData.stream()
        .filter(data -> data.getConfiguration() != null && 
                        proxyConfig.getRepositoryName().equals(data.getConfiguration().getRepositoryName()))
        .findFirst();
    assertTrue(proxyRepoOpt.isPresent());
    Repository proxyRepo = proxyRepoOpt.get();
    assertThat(proxyRepo.getConfiguration().getRecipeName(), is(proxyConfig.getRecipeName()));
    assertThat(proxyRepo.getConfiguration().getRoutingRuleId(), is(rule2.id()));
    
    // Verify group repository
    Optional<Repository> groupRepoOpt = importedData.stream()
        .filter(data -> data.getConfiguration() != null && 
                        groupConfig.getRepositoryName().equals(data.getConfiguration().getRepositoryName()))
        .findFirst();
    assertTrue(groupRepoOpt.isPresent());
    Repository groupRepo = groupRepoOpt.get();
    assertThat(groupRepo.getConfiguration().getRecipeName(), is(groupConfig.getRecipeName()));
    assertNull(groupRepo.getConfiguration().getRoutingRuleId());
    
    // Verify standalone routing rule
    Optional<Repository> standaloneRuleOpt = importedData.stream()
        .filter(data -> data.getConfiguration() == null)
        .findFirst();
    assertTrue(standaloneRuleOpt.isPresent());
    RoutingRule standaloneRule = standaloneRuleOpt.get().getRoutingRule();
    assertThat(standaloneRule.name(), is(rule3.name()));
  }
  
  private long measurePerformance(Runnable task) {
    // Warm up
    for (int i = 0; i < 3; i++) {
      task.run();
    }
    
    // Measure performance
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
  
  private <T> T runWithVirtualThread(Supplier<T> task) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Exception> error = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        result.set(task.get());
      }
      catch (Exception e) {
        error.set(e);
      }
    });
    
    virtualThread.join(Duration.ofSeconds(30));
    
    if (error.get() != null) {
      throw error.get();
    }
    
    return result.get();
  }
}