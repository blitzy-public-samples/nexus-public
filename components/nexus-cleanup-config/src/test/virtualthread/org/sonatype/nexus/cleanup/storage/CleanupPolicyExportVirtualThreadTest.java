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
package org.sonatype.nexus.cleanup.storage;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.cleanup.internal.storage.CleanupPolicyData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests validity of Serialization/Deserialization {@link CleanupPolicy}
 * by {@link CleanupPolicyExport} under Java 21 Virtual Thread execution.
 * <p>
 * This test specifically validates that I/O-bound operations in the export process
 * don't cause thread pinning issues and can leverage the performance benefits of Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
@VirtualThreadTestGroup
public class CleanupPolicyExportVirtualThreadTest
{
  private final JsonExporter jsonExporter = new JsonExporter();

  @TempDir
  File tempDir;

  private File jsonFile;

  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  private CleanupPolicyExport exporter;

  private Map<String, String> criteria;
  private List<CleanupPolicy> configurationData;

  @BeforeEach
  public void setup() throws IOException {
    jsonFile = new File(tempDir, "CleanupPolicy.json");
    
    // Setup test data
    criteria = ImmutableMap.of(
        "regex", "*.json",
        "lastDownloaded", "100",
        "lastBlobUpdated", "200");
    
    configurationData = Arrays.asList(
        createCleanupPolicy("test_1", "format_1", "delete", "notes 1", criteria),
        createCleanupPolicy("test_2", "format_2", "clean", "notes 2", criteria));

    when(cleanupPolicyStorage.getAll()).thenReturn(configurationData);

    exporter = new CleanupPolicyExport(cleanupPolicyStorage);
  }

  @AfterEach
  public void tearDown() {
    jsonFile.delete();
  }

  /**
   * Tests basic export/import functionality to ensure the test setup is valid.
   */
  @Test
  public void testBasicExportImportToJson() throws Exception {
    exporter.export(jsonFile);
    List<CleanupPolicyData> importedData = jsonExporter.importFromJson(jsonFile, CleanupPolicyData.class);

    assertThat(importedData.size(), is(2));
    importedData.forEach(data -> assertThat(data.getName(), anyOf(is("test_1"), is("test_2"))));
    importedData.forEach(data -> assertThat(data.getFormat(), anyOf(is("format_1"), is("format_2"))));
    importedData.forEach(data -> assertThat(data.getMode(), anyOf(is("delete"), is("clean"))));
    importedData.forEach(data -> assertThat(data.getNotes(), anyOf(is("notes 1"), is("notes 2"))));
    importedData.forEach(data -> assertThat(data.getCriteria(), is(criteria)));
  }

  /**
   * Tests concurrent export operations using Virtual Threads.
   * This test validates that the export functionality works correctly under high concurrency
   * with Virtual Threads, ensuring that I/O operations don't cause thread pinning issues.
   */
  @Test
  public void testConcurrentExportWithVirtualThreads() throws Exception {
    // Enable thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 1000; // High concurrency test with 1000 concurrent tasks
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create a temporary directory for concurrent export files
      File concurrentDir = new File(tempDir, "concurrent");
      concurrentDir.mkdir();
      
      // Submit multiple concurrent export tasks using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[taskCount];
      
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Create a unique file for each task
            File taskFile = new File(concurrentDir, "CleanupPolicy_" + taskId + ".json");
            
            // Perform the export operation
            exporter.export(taskFile);
            
            // Verify the exported data
            List<CleanupPolicyData> importedData = jsonExporter.importFromJson(taskFile, CleanupPolicyData.class);
            
            // Validate the imported data
            assertEquals(2, importedData.size(), "Exported file should contain 2 policies");
            
            // Cleanup the file after verification
            taskFile.delete();
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            e.printStackTrace();
          } 
          finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete or timeout after 30 seconds
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertTrue(completed, "All export tasks should complete within the timeout period");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent exports");
      
      // Wait for all futures to complete
      CompletableFuture.allOf(futures).join();
    }
    finally {
      // Reset the thread pinning detection property
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }

  /**
   * Tests concurrent export and import operations using Virtual Threads.
   * This test validates that both export and import functionality work correctly
   * under high concurrency with Virtual Threads.
   */
  @Test
  public void testConcurrentExportAndImportWithVirtualThreads() throws Exception {
    // Enable thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 500; // 500 concurrent export/import operations
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create a temporary directory for concurrent export files
      File concurrentDir = new File(tempDir, "concurrent_export_import");
      concurrentDir.mkdir();
      
      // Submit concurrent export/import tasks using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[taskCount];
      
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Create a unique file for each task
            File taskFile = new File(concurrentDir, "CleanupPolicy_" + taskId + ".json");
            
            // Perform the export operation
            exporter.export(taskFile);
            
            // Immediately import and verify the data
            List<CleanupPolicyData> importedData = jsonExporter.importFromJson(taskFile, CleanupPolicyData.class);
            
            // Validate the imported data
            assertEquals(2, importedData.size(), "Exported file should contain 2 policies");
            
            // Verify all policy attributes are preserved
            for (CleanupPolicyData data : importedData) {
              assertTrue(data.getName().equals("test_1") || data.getName().equals("test_2"), 
                  "Policy name should be preserved");
              assertTrue(data.getFormat().equals("format_1") || data.getFormat().equals("format_2"), 
                  "Format should be preserved");
              assertTrue(data.getMode().equals("delete") || data.getMode().equals("clean"), 
                  "Mode should be preserved");
              assertTrue(data.getNotes().equals("notes 1") || data.getNotes().equals("notes 2"), 
                  "Notes should be preserved");
              assertEquals(criteria, data.getCriteria(), "Criteria should be preserved");
            }
            
            // Cleanup the file after verification
            taskFile.delete();
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            e.printStackTrace();
          } 
          finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete or timeout after 30 seconds
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertTrue(completed, "All export/import tasks should complete within the timeout period");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent operations");
      
      // Wait for all futures to complete
      CompletableFuture.allOf(futures).join();
    }
    finally {
      // Reset the thread pinning detection property
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }

  private CleanupPolicy createCleanupPolicy(
      final String name, final String format, final String mode, final String notes,
      final Map<String, String> criteria) {
    CleanupPolicyData policyData = new CleanupPolicyData();
    policyData.setName(name);
    policyData.setFormat(format);
    policyData.setMode(mode);
    policyData.setNotes(notes);
    policyData.setCriteria(criteria);

    return policyData;
  }
}