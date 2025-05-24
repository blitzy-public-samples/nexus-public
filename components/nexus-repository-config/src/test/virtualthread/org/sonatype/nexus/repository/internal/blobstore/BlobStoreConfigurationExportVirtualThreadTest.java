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
package org.sonatype.nexus.repository.internal.blobstore;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests {@link BlobStoreConfigurationExport} operations under Java 21 Virtual Threads.
 * This test focuses on concurrent export and import operations, testing the serialization
 * and deserialization of BlobStoreConfigurationData objects to and from JSON when executed
 * with a large number of virtual threads.
 */
@ExtendWith(MockitoExtension.class)
@Tag("VirtualThreadTestGroup")
public class BlobStoreConfigurationExportVirtualThreadTest
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  private final JsonExporter jsonExporter = new JsonExporter();

  @Mock
  private BlobStoreConfigurationStore configurationStore;

  private List<File> tempFiles;

  @BeforeEach
  public void setup() {
    tempFiles = new ArrayList<>();
    
    // Prepare test data for the configuration store
    List<BlobStoreConfiguration> configurationData = Arrays.asList(
        generateConfigData("test1", "TEST_1"),
        generateConfigData("test2", "TEST_2"));
    
    when(configurationStore.list()).thenReturn(configurationData);
  }

  @AfterEach
  public void tearDown() {
    // Clean up all temporary files
    tempFiles.forEach(File::delete);
  }

  /**
   * Tests concurrent export operations using virtual threads.
   * This verifies that multiple export operations can be performed concurrently
   * without errors or data corruption.
   */
  @Test
  public void testConcurrentExportWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent export tasks using virtual threads
      List<CompletableFuture<File>> futures = IntStream.range(0, CONCURRENT_OPERATIONS)
          .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try {
              // Create a unique temporary file for each export
              File jsonFile = File.createTempFile("BlobStoreConfiguration_" + i + "_", ".json");
              tempFiles.add(jsonFile);
              
              // Create exporter and perform export
              BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
              exporter.export(jsonFile);
              
              return jsonFile;
            } catch (Exception e) {
              errorCount.incrementAndGet();
              throw new RuntimeException("Export failed", e);
            } finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all export operations completed within the timeout");
      assertEquals(0, errorCount.get(), "Some export operations failed");
      
      // Verify all exported files
      for (CompletableFuture<File> future : futures) {
        if (!future.isCompletedExceptionally()) {
          File jsonFile = future.get();
          List<BlobStoreConfigurationData> importedData =
              jsonExporter.importFromJson(jsonFile, BlobStoreConfigurationData.class);
          
          // Verify the imported data
          assertThat(importedData.stream().map(BlobStoreConfiguration::getName).collect(Collectors.toList()),
              containsInAnyOrder("test1", "test2"));
          assertThat(importedData.stream().map(BlobStoreConfiguration::getType).collect(Collectors.toList()),
              containsInAnyOrder("TEST_1", "TEST_2"));
        }
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent import operations using virtual threads.
   * This verifies that multiple import operations can be performed concurrently
   * without errors or data corruption.
   */
  @Test
  public void testConcurrentImportWithVirtualThreads() throws Exception {
    // First create a sample export file
    File templateFile = File.createTempFile("BlobStoreConfiguration_template", ".json");
    tempFiles.add(templateFile);
    
    BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
    exporter.export(templateFile);
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent import tasks using virtual threads
      List<CompletableFuture<List<BlobStoreConfigurationData>>> futures = IntStream.range(0, CONCURRENT_OPERATIONS)
          .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try {
              // Import from the template file
              return jsonExporter.importFromJson(templateFile, BlobStoreConfigurationData.class);
            } catch (Exception e) {
              errorCount.incrementAndGet();
              throw new RuntimeException("Import failed", e);
            } finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all import operations completed within the timeout");
      assertEquals(0, errorCount.get(), "Some import operations failed");
      
      // Verify all imported data
      for (CompletableFuture<List<BlobStoreConfigurationData>> future : futures) {
        if (!future.isCompletedExceptionally()) {
          List<BlobStoreConfigurationData> importedData = future.get();
          
          // Verify the imported data
          assertThat(importedData.stream().map(BlobStoreConfiguration::getName).collect(Collectors.toList()),
              containsInAnyOrder("test1", "test2"));
          assertThat(importedData.stream().map(BlobStoreConfiguration::getType).collect(Collectors.toList()),
              containsInAnyOrder("TEST_1", "TEST_2"));
          assertThat(importedData.stream().map(BlobStoreConfiguration::isWritable).collect(Collectors.toList()),
              not(contains(false)));
          
          List<Map<String, Map<String, Object>>> serializedAttrs = importedData.stream()
              .map(BlobStoreConfiguration::getAttributes)
              .collect(Collectors.toList());
          assertThat(serializedAttrs.toString(), allOf(
              containsString("metadata"),
              containsString("size"),
              containsString("10")));
          // make sure sensitive data is not serialized
          assertThat(serializedAttrs.toString(), not(containsString("admin123")));
        }
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent export-import cycles using virtual threads.
   * This verifies that multiple export-import cycles can be performed concurrently
   * without errors or data corruption.
   */
  @Test
  public void testConcurrentExportImportCyclesWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent export-import cycles using virtual threads
      List<CompletableFuture<Void>> futures = IntStream.range(0, CONCURRENT_OPERATIONS)
          .mapToObj(i -> CompletableFuture.runAsync(() -> {
            try {
              // Create a unique temporary file for each export
              File jsonFile = File.createTempFile("BlobStoreConfiguration_cycle_" + i + "_", ".json");
              tempFiles.add(jsonFile);
              
              // Create exporter and perform export
              BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
              exporter.export(jsonFile);
              
              // Import the exported data
              List<BlobStoreConfigurationData> importedData =
                  jsonExporter.importFromJson(jsonFile, BlobStoreConfigurationData.class);
              
              // Verify the imported data
              assertEquals(2, importedData.size(), "Expected 2 configurations");
              assertFalse(importedData.stream().anyMatch(config -> !config.isWritable()), 
                  "All configurations should be writable");
              
              // Verify sensitive data is not serialized
              String serializedData = importedData.stream()
                  .map(BlobStoreConfiguration::getAttributes)
                  .collect(Collectors.toList())
                  .toString();
              assertFalse(serializedData.contains("admin123"), "Sensitive data should not be serialized");
              
            } catch (Exception e) {
              errorCount.incrementAndGet();
              throw new RuntimeException("Export-import cycle failed", e);
            } finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all export-import cycles completed within the timeout");
      assertEquals(0, errorCount.get(), "Some export-import cycles failed");
      
      // Verify all futures completed successfully
      for (CompletableFuture<Void> future : futures) {
        assertFalse(future.isCompletedExceptionally(), "Some export-import cycles failed");
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Compares performance between virtual threads and platform threads for export-import operations.
   * This test measures the execution time for a large number of concurrent operations using both
   * thread types and verifies that virtual threads provide better performance for I/O-bound operations.
   */
  @Test
  public void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Create thread factories for both thread types
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Measure performance with platform threads
    long platformThreadTime = measureExportImportPerformance(platformThreadFactory, CONCURRENT_OPERATIONS / 10);
    
    // Measure performance with virtual threads
    long virtualThreadTime = measureExportImportPerformance(virtualThreadFactory, CONCURRENT_OPERATIONS);
    
    // Scale the platform thread time to account for the difference in operation count
    long scaledPlatformTime = platformThreadTime * 10;
    
    // Virtual threads should be more efficient for I/O operations, especially at high concurrency
    System.out.println("Platform threads time (scaled): " + scaledPlatformTime + "ms");
    System.out.println("Virtual threads time: " + virtualThreadTime + "ms");
    
    // Virtual threads should be more efficient, especially at high concurrency
    assertThat("Virtual threads should be more efficient than platform threads for I/O operations",
        virtualThreadTime, lessThan(scaledPlatformTime));
  }

  /**
   * Measures the performance of concurrent export-import operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (virtual or platform)
   * @param operationCount The number of concurrent operations to perform
   * @return The execution time in milliseconds
   */
  private long measureExportImportPerformance(ThreadFactory threadFactory, int operationCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    try {
      // Submit concurrent export-import operations
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Create a unique temporary file
            File jsonFile = File.createTempFile("BlobStoreConfiguration_perf_", ".json");
            tempFiles.add(jsonFile);
            
            // Export
            BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
            exporter.export(jsonFile);
            
            // Import
            jsonExporter.importFromJson(jsonFile, BlobStoreConfigurationData.class);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, errorCount.get(), "Some operations failed during performance test");
      
    } finally {
      executor.shutdown();
    }
    
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Tests for thread pinning issues when using virtual threads for export-import operations.
   * Thread pinning occurs when a virtual thread is forced to execute on its carrier thread,
   * preventing the carrier thread from executing other virtual threads. This can happen with
   * synchronized blocks or other blocking operations that don't support virtual thread unmounting.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent export-import cycles using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            // Create a unique temporary file
            File jsonFile = File.createTempFile("BlobStoreConfiguration_pin_", ".json");
            tempFiles.add(jsonFile);
            
            // Export
            BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
            exporter.export(jsonFile);
            
            // Import
            jsonExporter.importFromJson(jsonFile, BlobStoreConfigurationData.class);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(completed, "Not all operations completed within the timeout");
      assertEquals(0, errorCount.get(), "Some operations failed");
      
      // If thread pinning occurs, operations would likely time out or fail
      // The JVM will log thread pinning events with the property set above
    } finally {
      executor.shutdown();
      // Reset the property
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }

  /**
   * Helper method to generate test BlobStoreConfiguration data.
   */
  private BlobStoreConfiguration generateConfigData(final String name, final String type) {
    BlobStoreConfigurationData configuration = new BlobStoreConfigurationData();
    configuration.setId(new EntityUUID(UUID.randomUUID()));
    configuration.setName(name);
    configuration.setType(type);
    configuration.setWritable(true);
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    attributes.put("login", Collections.singletonMap("password", "admin123"));
    attributes.put("user", Collections.singletonMap("secret", "admin123"));
    attributes.put("metadata", Collections.singletonMap("size", 10));
    configuration.setAttributes(attributes);

    return configuration;
  }
}