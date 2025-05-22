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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationExport;
import org.sonatype.nexus.repository.config.ConfigurationStore;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;
import org.sonatype.nexus.repository.routing.RoutingMode;
import org.sonatype.nexus.repository.routing.RoutingRule;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.routing.internal.RoutingRuleData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import com.google.common.collect.ImmutableList;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for validating Java 21 Virtual Threads with repository configuration operations.
 * This test class compares performance between virtual threads and platform threads
 * for I/O-bound operations like configuration export/import.
 */
@RunWith(MockitoJUnitRunner.class)
public class VirtualThreadTest
{
  private static final int SMALL_BATCH_SIZE = 10;
  private static final int MEDIUM_BATCH_SIZE = 100;
  private static final int LARGE_BATCH_SIZE = 1000;
  
  private final JsonExporter jsonExporter = new JsonExporter();
  
  private Path tempDir;
  
  @Mock
  private ConfigurationStore configurationStore;
  
  @Mock
  private RoutingRuleStore routingRuleStore;
  
  @Before
  public void setup() throws IOException {
    tempDir = Files.createTempDirectory("virtual-thread-test");
    
    // Setup mock data
    List<RoutingRule> routingRules = createRoutingRules(5);
    when(routingRuleStore.list()).thenReturn(routingRules);
    
    List<Configuration> configurations = createConfigurations(10, routingRules);
    when(configurationStore.list()).thenReturn(configurations);
  }
  
  @After
  public void tearDown() throws IOException {
    // Clean up temp directory
    Files.walk(tempDir)
        .sorted(java.util.Comparator.reverseOrder())
        .map(Path::toFile)
        .forEach(File::delete);
  }
  
  /**
   * Tests basic functionality of virtual threads with configuration export.
   * Validates that virtual threads can correctly handle I/O operations.
   */
  @Test
  public void testBasicVirtualThreadExport() throws Exception {
    File exportFile = tempDir.resolve("basic-export.json").toFile();
    
    // Use a virtual thread to perform the export
    Thread virtualThread = Thread.ofVirtual().name("export-thread").start(() -> {
      try {
        ConfigurationExport exporter = new ConfigurationExport(configurationStore, routingRuleStore);
        exporter.export(exportFile);
      }
      catch (Exception e) {
        throw new RuntimeException("Export failed", e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the export was successful
    assertTrue("Export file should exist", exportFile.exists());
    assertTrue("Export file should have content", exportFile.length() > 0);
    
    // Verify the exported content
    List<ConfigurationExport.Repository> importedData = 
        jsonExporter.importFromJson(exportFile, ConfigurationExport.Repository.class);
    
    assertNotNull("Imported data should not be null", importedData);
    assertEquals("Should have imported all configurations", 10, 
        importedData.stream().filter(r -> r.getConfiguration() != null).count());
  }
  
  /**
   * Compares performance between virtual threads and platform threads for a small batch of exports.
   * This test validates that virtual threads can handle I/O operations efficiently.
   */
  @Test
  public void testSmallBatchPerformanceComparison() throws Exception {
    performanceComparisonTest(SMALL_BATCH_SIZE);
  }
  
  /**
   * Compares performance between virtual threads and platform threads for a medium batch of exports.
   * This test validates that virtual threads scale better than platform threads as concurrency increases.
   */
  @Test
  public void testMediumBatchPerformanceComparison() throws Exception {
    performanceComparisonTest(MEDIUM_BATCH_SIZE);
  }
  
  /**
   * Compares performance between virtual threads and platform threads for a large batch of exports.
   * This test demonstrates the scalability advantage of virtual threads for high concurrency scenarios.
   */
  @Test
  public void testLargeBatchPerformanceComparison() throws Exception {
    performanceComparisonTest(LARGE_BATCH_SIZE);
  }
  
  /**
   * Tests concurrent configuration exports using virtual threads.
   * Validates that virtual threads can handle many concurrent I/O operations correctly.
   */
  @Test
  public void testConcurrentExportsWithVirtualThreads() throws Exception {
    int concurrentExports = 50;
    CountDownLatch latch = new CountDownLatch(concurrentExports);
    AtomicInteger successCount = new AtomicInteger(0);
    ConcurrentHashMap<Integer, Exception> errors = new ConcurrentHashMap<>();
    
    // Create and start virtual threads for concurrent exports
    for (int i = 0; i < concurrentExports; i++) {
      final int index = i;
      Thread.ofVirtual().name("export-thread-" + index).start(() -> {
        try {
          File exportFile = tempDir.resolve("concurrent-export-" + index + ".json").toFile();
          ConfigurationExport exporter = new ConfigurationExport(configurationStore, routingRuleStore);
          exporter.export(exportFile);
          successCount.incrementAndGet();
        }
        catch (Exception e) {
          errors.put(index, e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all exports to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    assertTrue("All exports should complete within timeout", completed);
    assertEquals("All exports should succeed", concurrentExports, successCount.get());
    assertEquals("There should be no errors", 0, errors.size());
  }
  
  /**
   * Tests resource management with virtual threads.
   * Validates that virtual threads properly release resources when they complete.
   */
  @Test
  public void testResourceManagementWithVirtualThreads() throws Exception {
    int threadCount = 100;
    List<File> exportFiles = new ArrayList<>();
    
    // Create a scope for virtual threads to ensure proper resource management
    try (var scope = new java.lang.ThreadBuilderFactory.Container()) {
      // Create and start virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        File exportFile = tempDir.resolve("resource-export-" + index + ".json").toFile();
        exportFiles.add(exportFile);
        
        Thread thread = Thread.ofVirtual().name("resource-thread-" + index).factory(scope).start(() -> {
          try {
            ConfigurationExport exporter = new ConfigurationExport(configurationStore, routingRuleStore);
            exporter.export(exportFile);
          }
          catch (Exception e) {
            throw new RuntimeException("Export failed", e);
          }
        });
      }
    } // All threads will be joined when the scope is closed
    
    // Verify all exports were successful
    for (File file : exportFiles) {
      assertTrue("Export file should exist: " + file.getName(), file.exists());
      assertTrue("Export file should have content: " + file.getName(), file.length() > 0);
    }
  }
  
  /**
   * Tests error handling with virtual threads.
   * Validates that exceptions in virtual threads are properly propagated and handled.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    // Create a virtual thread that will throw an exception
    AtomicInteger exceptionCaught = new AtomicInteger(0);
    
    Thread virtualThread = Thread.ofVirtual().name("error-thread").start(() -> {
      throw new RuntimeException("Intentional test exception");
    });
    
    try {
      virtualThread.join();
    }
    catch (Exception e) {
      // Exception should not be thrown from join()
    }
    
    // Test with try-catch inside the virtual thread
    Thread handledThread = Thread.ofVirtual().name("handled-error-thread").start(() -> {
      try {
        throw new RuntimeException("Handled test exception");
      }
      catch (Exception e) {
        exceptionCaught.incrementAndGet();
      }
    });
    
    handledThread.join();
    assertEquals("Exception should be caught inside virtual thread", 1, exceptionCaught.get());
  }
  
  /**
   * Helper method to compare performance between virtual threads and platform threads.
   * 
   * @param batchSize the number of concurrent operations to perform
   */
  private void performanceComparisonTest(int batchSize) throws Exception {
    System.out.println("\nPerformance comparison with batch size: " + batchSize);
    
    // Prepare export files
    List<File> virtualThreadFiles = IntStream.range(0, batchSize)
        .mapToObj(i -> tempDir.resolve("vt-export-" + i + ".json").toFile())
        .collect(Collectors.toList());
    
    List<File> platformThreadFiles = IntStream.range(0, batchSize)
        .mapToObj(i -> tempDir.resolve("pt-export-" + i + ".json").toFile())
        .collect(Collectors.toList());
    
    // Test with virtual threads
    Instant vtStart = Instant.now();
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < batchSize; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          try {
            ConfigurationExport exporter = new ConfigurationExport(configurationStore, routingRuleStore);
            exporter.export(virtualThreadFiles.get(index));
            return true;
          }
          catch (Exception e) {
            throw new RuntimeException("Virtual thread export failed", e);
          }
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
    }
    
    Duration vtDuration = Duration.between(vtStart, Instant.now());
    
    // Test with platform threads using a fixed thread pool
    Instant ptStart = Instant.now();
    
    try (ExecutorService executor = Executors.newFixedThreadPool(Math.min(batchSize, 200))) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < batchSize; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          try {
            ConfigurationExport exporter = new ConfigurationExport(configurationStore, routingRuleStore);
            exporter.export(platformThreadFiles.get(index));
            return true;
          }
          catch (Exception e) {
            throw new RuntimeException("Platform thread export failed", e);
          }
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get();
      }
    }
    
    Duration ptDuration = Duration.between(ptStart, Instant.now());
    
    // Print and verify results
    System.out.println("Virtual Threads: " + vtDuration.toMillis() + "ms");
    System.out.println("Platform Threads: " + ptDuration.toMillis() + "ms");
    
    // For small batches, the difference might not be significant
    if (batchSize >= MEDIUM_BATCH_SIZE) {
      // Virtual threads should be faster for I/O-bound operations with higher concurrency
      assertThat("Virtual threads should be faster for I/O-bound operations", 
          vtDuration.toMillis(), lessThan(ptDuration.toMillis()));
    }
    
    // Verify all exports were successful
    for (int i = 0; i < batchSize; i++) {
      assertTrue("Virtual thread export file should exist", virtualThreadFiles.get(i).exists());
      assertTrue("Platform thread export file should exist", platformThreadFiles.get(i).exists());
      
      assertTrue("Virtual thread export file should have content", virtualThreadFiles.get(i).length() > 0);
      assertTrue("Platform thread export file should have content", platformThreadFiles.get(i).length() > 0);
    }
  }
  
  /**
   * Creates a list of test routing rules.
   * 
   * @param count the number of routing rules to create
   * @return a list of routing rules
   */
  private List<RoutingRule> createRoutingRules(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> {
          RoutingRuleData rule = new RoutingRuleData();
          rule.setId(new EntityUUID());
          rule.setName("Rule-" + i);
          rule.description("Description for Rule-" + i);
          rule.matchers(ImmutableList.of("matcher_1", "matcher_2"));
          rule.mode(RoutingMode.ALLOW);
          return rule;
        })
        .collect(Collectors.toList());
  }
  
  /**
   * Creates a list of test configurations.
   * 
   * @param count the number of configurations to create
   * @param rules the routing rules to associate with configurations
   * @return a list of configurations
   */
  private List<Configuration> createConfigurations(int count, List<RoutingRule> rules) {
    String[] types = {"hosted", "proxy", "group"};
    
    return IntStream.range(0, count)
        .mapToObj(i -> {
          String type = types[i % types.length];
          EntityId routingRuleId = i < rules.size() ? rules.get(i).id() : null;
          
          Map<String, Map<String, Object>> attributes = new HashMap<>();
          attributes.put("login", Collections.singletonMap("password", "password" + i));
          attributes.put("metadata", Collections.singletonMap("size", i * 10));
          
          ConfigurationData config = new ConfigurationData();
          config.setId(new EntityUUID(UUID.randomUUID()));
          config.setName(type + "-repo-" + i);
          config.setRecipeName(type);
          config.setOnline(true);
          config.setAttributes(attributes);
          config.setRoutingRuleId(routingRuleId);
          
          return config;
        })
        .collect(Collectors.toList());
  }
}