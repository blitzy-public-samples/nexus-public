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
package org.sonatype.nexus.script.plugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.plugin.internal.ScriptData;
import org.sonatype.nexus.script.plugin.internal.ScriptExport;
import org.sonatype.nexus.script.plugin.internal.ScriptStore;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the JSON export/import pipeline for Script objects using Java 21 Virtual Threads.
 * This test validates that exporting and importing scripts to/from JSON files functions correctly
 * when executed with Virtual Threads, and demonstrates performance improvements compared to
 * platform threads for concurrent file operations.
 */
public class ScriptExportVirtualThreadTest
{
  private final JsonExporter jsonExporter = new JsonExporter();

  private File jsonFile;

  @TempDir
  File tempDir;

  @BeforeEach
  public void setup() throws IOException {
    jsonFile = File.createTempFile("Script", ".json", tempDir);
  }

  @AfterEach
  public void tearDown() {
    jsonFile.delete();
  }

  /**
   * Basic test to verify that script export/import works correctly.
   * This is a baseline test adapted from the original ScriptExportTest.
   */
  @Test
  public void testExportImportToJson() throws Exception {
    List<Script> scripts = Arrays.asList(
        createScript("script_1"),
        createScript("script_2"));

    ScriptStore store = mock(ScriptStore.class);
    when(store.list()).thenReturn(scripts);

    ScriptExport exporter = new ScriptExport(store);
    exporter.export(jsonFile);
    List<ScriptData> importedData = jsonExporter.importFromJson(jsonFile, ScriptData.class);

    assertThat(importedData.size(), is(2));
    importedData.forEach(data -> assertThat(data.getName(), anyOf(
        is(scripts.get(0).getName()),
        is(scripts.get(1).getName()))));
    importedData.forEach(data -> assertThat(data.getType(), is("script")));
    importedData.forEach(data -> assertThat(data.getContent(), is("log.info('world')")));
  }

  /**
   * Tests concurrent export/import operations using Virtual Threads.
   * This test validates that multiple Virtual Threads can simultaneously export and import
   * script data without conflicts or errors.
   */
  @Test
  public void testConcurrentExportImportWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      int concurrentTasks = 50;
      CountDownLatch latch = new CountDownLatch(concurrentTasks);
      AtomicInteger errorCount = new AtomicInteger(0);

      // Create multiple script stores with different data
      List<ScriptStore> stores = new ArrayList<>();
      for (int i = 0; i < concurrentTasks; i++) {
        List<Script> scripts = Arrays.asList(
            createScript("script_" + i + "_1"),
            createScript("script_" + i + "_2"));

        ScriptStore store = mock(ScriptStore.class);
        when(store.list()).thenReturn(scripts);
        stores.add(store);
      }

      // Submit concurrent export/import tasks
      for (int i = 0; i < concurrentTasks; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique file for each task
            File taskFile = new File(tempDir, "script_" + index + ".json");
            
            // Export scripts
            ScriptExport exporter = new ScriptExport(stores.get(index));
            exporter.export(taskFile);
            
            // Import and verify
            List<ScriptData> importedData = jsonExporter.importFromJson(taskFile, ScriptData.class);
            
            // Verify imported data
            if (importedData.size() != 2) {
              errorCount.incrementAndGet();
            }
            
            // Clean up
            taskFile.delete();
          } catch (Exception e) {
            e.printStackTrace();
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent tasks");
      
      // Verify no errors occurred
      assertEquals(0, errorCount.get(), "Errors occurred during concurrent export/import operations");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for script export/import operations.
   * This test demonstrates the performance benefits of using Virtual Threads for I/O-bound operations.
   */
  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    // Create thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    int taskCount = 100;
    int scriptCount = 5;
    
    // Prepare test data
    List<ScriptStore> stores = new ArrayList<>();
    for (int i = 0; i < taskCount; i++) {
      List<Script> scripts = new ArrayList<>();
      for (int j = 0; j < scriptCount; j++) {
        scripts.add(createScript("script_" + i + "_" + j));
      }
      
      ScriptStore store = mock(ScriptStore.class);
      when(store.list()).thenReturn(scripts);
      stores.add(store);
    }
    
    // Measure platform thread performance
    long platformThreadTime = measurePerformance(platformThreadFactory, stores, taskCount);
    System.out.println("Platform thread execution time: " + platformThreadTime + "ms");
    
    // Measure virtual thread performance
    long virtualThreadTime = measurePerformance(virtualThreadFactory, stores, taskCount);
    System.out.println("Virtual thread execution time: " + virtualThreadTime + "ms");
    
    // Virtual threads should generally be faster for I/O-bound operations
    // but we don't make this a hard assertion as it might depend on the test environment
    System.out.println("Performance improvement: " + 
        String.format("%.2f%%", (platformThreadTime - virtualThreadTime) * 100.0 / platformThreadTime));
    
    // In most cases, virtual threads should be faster, but we'll use a soft assertion
    // to avoid test flakiness on different environments
    assertTrue(virtualThreadTime <= platformThreadTime * 1.2, 
        "Virtual threads should not be significantly slower than platform threads");
  }

  /**
   * Tests for thread pinning during file I/O operations.
   * Thread pinning occurs when a virtual thread is forced to execute on its carrier thread
   * without yielding, which can reduce the benefits of virtual threads.
   */
  @Test
  public void testThreadPinningDuringFileOperations() throws Exception {
    // Enable thread pinning detection
    // Note: In a real environment, you would use -Djdk.tracePinnedThreads=full JVM flag
    // For this test, we'll use a simple approach to detect potential pinning
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("export-vthread-").factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int concurrentTasks = 200; // High concurrency to increase chances of detecting pinning
      CountDownLatch latch = new CountDownLatch(concurrentTasks);
      AtomicInteger completedTasks = new AtomicInteger(0);
      AtomicLong maxTaskDuration = new AtomicLong(0);
      
      // Create script stores
      List<ScriptStore> stores = new ArrayList<>();
      for (int i = 0; i < concurrentTasks; i++) {
        List<Script> scripts = Arrays.asList(
            createScript("script_" + i + "_1"),
            createScript("script_" + i + "_2"));

        ScriptStore store = mock(ScriptStore.class);
        when(store.list()).thenReturn(scripts);
        stores.add(store);
      }
      
      // Submit tasks
      for (int i = 0; i < concurrentTasks; i++) {
        final int index = i;
        CompletableFuture.runAsync(() -> {
          long startTime = System.nanoTime();
          try {
            // Create a unique file for each task
            File taskFile = new File(tempDir, "script_pinning_" + index + ".json");
            
            // Export scripts
            ScriptExport exporter = new ScriptExport(stores.get(index));
            exporter.export(taskFile);
            
            // Import scripts
            jsonExporter.importFromJson(taskFile, ScriptData.class);
            
            // Clean up
            taskFile.delete();
            
            completedTasks.incrementAndGet();
          } catch (Exception e) {
            e.printStackTrace();
          } finally {
            long duration = System.nanoTime() - startTime;
            updateMaxDuration(maxTaskDuration, duration);
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent tasks");
      
      // All tasks should complete successfully
      assertEquals(concurrentTasks, completedTasks.get(), "Not all tasks completed successfully");
      
      // Calculate statistics
      long maxDurationMs = TimeUnit.NANOSECONDS.toMillis(maxTaskDuration.get());
      System.out.println("Max task duration: " + maxDurationMs + "ms");
      
      // If max duration is significantly higher than expected, it might indicate thread pinning
      // This is a heuristic and not a definitive test for pinning
      assertFalse(maxDurationMs > 5000, "Possible thread pinning detected: max task duration exceeds 5 seconds");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to measure performance of export/import operations using the specified thread factory.
   */
  private long measurePerformance(ThreadFactory threadFactory, List<ScriptStore> stores, int taskCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      long startTime = System.nanoTime();
      
      // Submit tasks
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique file for each task
            File taskFile = new File(tempDir, "perf_" + index + ".json");
            
            // Export scripts
            ScriptExport exporter = new ScriptExport(stores.get(index));
            exporter.export(taskFile);
            
            // Import scripts
            jsonExporter.importFromJson(taskFile, ScriptData.class);
            
            // Clean up
            taskFile.delete();
          } catch (Exception e) {
            e.printStackTrace();
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(60, TimeUnit.SECONDS);
      
      long endTime = System.nanoTime();
      
      // Verify no errors
      assertEquals(0, errorCount.get(), "Errors occurred during performance test");
      
      return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to atomically update the maximum task duration.
   */
  private void updateMaxDuration(AtomicLong maxDuration, long duration) {
    long currentMax = maxDuration.get();
    while (duration > currentMax) {
      if (maxDuration.compareAndSet(currentMax, duration)) {
        break;
      }
      currentMax = maxDuration.get();
    }
  }

  /**
   * Helper method to create a test script.
   */
  private Script createScript(final String name) {
    ScriptData script = new ScriptData();
    script.setName(name);
    script.setType("script");
    script.setContent("log.info('world')");

    return script;
  }
}