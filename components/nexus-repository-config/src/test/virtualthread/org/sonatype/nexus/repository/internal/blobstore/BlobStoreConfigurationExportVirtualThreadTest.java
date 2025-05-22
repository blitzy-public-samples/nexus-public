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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests validity of Serialization/Deserialization {@link BlobStoreConfiguration}
 * by {@link BlobStoreConfigurationExport} when executed with Java 21 Virtual Threads.
 * 
 * This test focuses on concurrent export and import operations, testing the serialization
 * and deserialization of BlobStoreConfigurationData objects to and from JSON when executed
 * with a large number of virtual threads.
 */
@Category(VirtualThreadTestGroup.class)
public class BlobStoreConfigurationExportVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int CONFIGURATIONS_PER_OPERATION = 10;
  
  private final JsonExporter jsonExporter = new JsonExporter();
  private final ThreadPinningDetector pinningDetector = new ThreadPinningDetector();

  private File jsonFile;

  @Before
  public void setup() throws IOException {
    jsonFile = File.createTempFile("BlobStoreConfiguration", ".json");
  }

  @After
  public void tearDown() {
    jsonFile.delete();
  }

  /**
   * Tests basic export/import functionality with a single virtual thread to ensure
   * the operation works correctly in the virtual thread environment.
   */
  @Test
  public void testBasicExportImportWithVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      executor.submit(() -> {
        try {
          List<BlobStoreConfiguration> configurationData = Arrays.asList(
              generateConfigData("test1", "TEST_1"),
              generateConfigData("test2", "TEST_2"));

          BlobStoreConfigurationStore configurationStore = mock(BlobStoreConfigurationStore.class);
          when(configurationStore.list()).thenReturn(configurationData);

          BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
          exporter.export(jsonFile);
          List<BlobStoreConfigurationData> importedData =
              jsonExporter.importFromJson(jsonFile, BlobStoreConfigurationData.class);

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
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }).get(); // Wait for completion
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent export operations with virtual threads.
   * This test creates multiple virtual threads that simultaneously export
   * BlobStoreConfiguration data to different files.
   */
  @Test
  public void testConcurrentExportWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    List<File> tempFiles = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Start multiple concurrent export operations
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique temp file for each operation
            File tempFile = File.createTempFile("BlobStoreConfiguration-" + index, ".json");
            tempFiles.add(tempFile);
            
            // Generate unique configuration data
            List<BlobStoreConfiguration> configurationData = IntStream.range(0, CONFIGURATIONS_PER_OPERATION)
                .mapToObj(j -> generateConfigData("test-" + index + "-" + j, "TYPE-" + j))
                .collect(Collectors.toList());

            BlobStoreConfigurationStore configurationStore = mock(BlobStoreConfigurationStore.class);
            when(configurationStore.list()).thenReturn(configurationData);

            // Export the configuration
            BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
            exporter.export(tempFile);
            
            // Verify the export was successful by importing and checking
            List<BlobStoreConfigurationData> importedData =
                jsonExporter.importFromJson(tempFile, BlobStoreConfigurationData.class);
            
            assertThat(importedData.size(), is(CONFIGURATIONS_PER_OPERATION));
          } 
          catch (Exception e) {
            log.error("Error in concurrent export", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All export operations should complete within timeout", completed, is(true));
      assertThat("No errors should occur during concurrent exports", errorCount.get(), is(0));
      
      // Check for thread pinning issues
      assertThat("No thread pinning should occur during exports", 
          pinningDetector.getPinnedThreadCount(), is(0L));
    } 
    finally {
      executor.shutdown();
      // Clean up temp files
      for (File file : tempFiles) {
        file.delete();
      }
    }
  }

  /**
   * Tests concurrent import operations with virtual threads.
   * This test creates multiple virtual threads that simultaneously import
   * BlobStoreConfiguration data from the same file.
   */
  @Test
  public void testConcurrentImportWithVirtualThreads() throws Exception {
    // First create and export a configuration file
    List<BlobStoreConfiguration> configurationData = IntStream.range(0, CONFIGURATIONS_PER_OPERATION)
        .mapToObj(i -> generateConfigData("test-" + i, "TYPE-" + i))
        .collect(Collectors.toList());

    BlobStoreConfigurationStore configurationStore = mock(BlobStoreConfigurationStore.class);
    when(configurationStore.list()).thenReturn(configurationData);

    BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
    exporter.export(jsonFile);
    
    // Now test concurrent imports
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Start multiple concurrent import operations
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            List<BlobStoreConfigurationData> importedData =
                jsonExporter.importFromJson(jsonFile, BlobStoreConfigurationData.class);
            
            assertThat(importedData.size(), is(CONFIGURATIONS_PER_OPERATION));
          } 
          catch (Exception e) {
            log.error("Error in concurrent import", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All import operations should complete within timeout", completed, is(true));
      assertThat("No errors should occur during concurrent imports", errorCount.get(), is(0));
      
      // Check for thread pinning issues
      assertThat("No thread pinning should occur during imports", 
          pinningDetector.getPinnedThreadCount(), is(0L));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent export-import cycles with virtual threads.
   * This test creates multiple virtual threads that simultaneously perform
   * export followed by import operations.
   */
  @Test
  public void testConcurrentExportImportCyclesWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    List<File> tempFiles = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Start multiple concurrent export-import cycles
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique temp file for each operation
            File tempFile = File.createTempFile("BlobStoreConfiguration-" + index, ".json");
            tempFiles.add(tempFile);
            
            // Generate unique configuration data
            List<BlobStoreConfiguration> configurationData = IntStream.range(0, CONFIGURATIONS_PER_OPERATION)
                .mapToObj(j -> generateConfigData("test-" + index + "-" + j, "TYPE-" + j))
                .collect(Collectors.toList());

            BlobStoreConfigurationStore configurationStore = mock(BlobStoreConfigurationStore.class);
            when(configurationStore.list()).thenReturn(configurationData);

            // Export the configuration
            BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
            exporter.export(tempFile);
            
            // Import the configuration
            List<BlobStoreConfigurationData> importedData =
                jsonExporter.importFromJson(tempFile, BlobStoreConfigurationData.class);
            
            // Verify the export-import cycle was successful
            assertThat(importedData.size(), is(CONFIGURATIONS_PER_OPERATION));
            
            List<String> expectedNames = configurationData.stream()
                .map(BlobStoreConfiguration::getName)
                .collect(Collectors.toList());
            
            List<String> actualNames = importedData.stream()
                .map(BlobStoreConfiguration::getName)
                .collect(Collectors.toList());
            
            assertThat(actualNames, containsInAnyOrder(expectedNames.toArray()));
          } 
          catch (Exception e) {
            log.error("Error in concurrent export-import cycle", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All export-import cycles should complete within timeout", completed, is(true));
      assertThat("No errors should occur during concurrent export-import cycles", errorCount.get(), is(0));
      
      // Check for thread pinning issues
      assertThat("No thread pinning should occur during export-import cycles", 
          pinningDetector.getPinnedThreadCount(), is(0L));
    } 
    finally {
      executor.shutdown();
      // Clean up temp files
      for (File file : tempFiles) {
        file.delete();
      }
    }
  }

  /**
   * Compares performance between virtual threads and platform threads for I/O operations.
   * This test measures the time taken to perform export-import operations using both
   * virtual threads and platform threads, and verifies that virtual threads provide
   * better performance at high concurrency.
   */
  @Test
  public void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Run with platform threads
    long platformThreadTime = measureExportImportPerformance(
        Thread.ofPlatform().factory(), 
        CONCURRENT_OPERATIONS);
    
    // Run with virtual threads
    long virtualThreadTime = measureExportImportPerformance(
        Thread.ofVirtual().factory(), 
        CONCURRENT_OPERATIONS);
    
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be faster or at least not significantly slower
    // We're using a relaxed assertion here as the performance benefit may vary
    // depending on the test environment
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 1.5));
    
    // Run with higher concurrency to demonstrate virtual thread scalability
    int highConcurrency = CONCURRENT_OPERATIONS * 10;
    
    // Platform threads might struggle with very high concurrency
    long highConcurrencyPlatformTime = measureExportImportPerformance(
        Thread.ofPlatform().factory(), 
        highConcurrency);
    
    // Virtual threads should handle high concurrency well
    long highConcurrencyVirtualTime = measureExportImportPerformance(
        Thread.ofVirtual().factory(), 
        highConcurrency);
    
    log.info("High concurrency platform thread execution time: {} ms", highConcurrencyPlatformTime);
    log.info("High concurrency virtual thread execution time: {} ms", highConcurrencyVirtualTime);
    
    // At high concurrency, virtual threads should show a more significant advantage
    assertThat("Virtual threads should scale better at high concurrency",
        highConcurrencyVirtualTime, lessThan(highConcurrencyPlatformTime * 0.8));
  }

  /**
   * Measures the time taken to perform concurrent export-import operations using the specified
   * thread factory and concurrency level.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param concurrency The number of concurrent operations to perform
   * @return The time taken in milliseconds
   */
  private long measureExportImportPerformance(ThreadFactory threadFactory, int concurrency) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    List<File> tempFiles = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    try {
      // Start multiple concurrent export-import cycles
      for (int i = 0; i < concurrency; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique temp file for each operation
            File tempFile = File.createTempFile("BlobStoreConfiguration-" + index, ".json");
            tempFiles.add(tempFile);
            
            // Generate unique configuration data
            List<BlobStoreConfiguration> configurationData = IntStream.range(0, CONFIGURATIONS_PER_OPERATION)
                .mapToObj(j -> generateConfigData("test-" + index + "-" + j, "TYPE-" + j))
                .collect(Collectors.toList());

            BlobStoreConfigurationStore configurationStore = mock(BlobStoreConfigurationStore.class);
            when(configurationStore.list()).thenReturn(configurationData);

            // Export the configuration
            BlobStoreConfigurationExport exporter = new BlobStoreConfigurationExport(configurationStore);
            exporter.export(tempFile);
            
            // Import the configuration
            List<BlobStoreConfigurationData> importedData =
                jsonExporter.importFromJson(tempFile, BlobStoreConfigurationData.class);
            
            // Verify the export-import cycle was successful
            assertThat(importedData.size(), is(CONFIGURATIONS_PER_OPERATION));
          } 
          catch (Exception e) {
            log.error("Error in performance test", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      assertThat("All operations should complete within timeout", completed, is(true));
      assertThat("No errors should occur during performance test", errorCount.get(), is(0));
    } 
    finally {
      executor.shutdown();
      // Clean up temp files
      for (File file : tempFiles) {
        file.delete();
      }
    }
    
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Generates test BlobStoreConfiguration data with the specified name and type.
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