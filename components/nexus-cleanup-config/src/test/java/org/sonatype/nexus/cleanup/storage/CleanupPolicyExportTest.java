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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.cleanup.internal.storage.CleanupPolicyData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests validity of Serialization/Deserialization {@link CleanupPolicy}
 * by {@link CleanupPolicyExport}
 */
@ExtendWith(MockitoExtension.class)
class CleanupPolicyExportTest
{
  private final JsonExporter jsonExporter = new JsonExporter();

  private File jsonFile;
  
  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  @BeforeEach
  void setup() throws IOException {
    jsonFile = File.createTempFile("CleanupPolicy", ".json");
  }

  @AfterEach
  void tearDown() {
    jsonFile.delete();
  }

  @Test
  void testExportImportToJson() throws Exception {
    Map<String, String> criteria = ImmutableMap.of(
        "regex", "*.json",
        "lastDownloaded", "100",
        "lastBlobUpdated", "200");
    List<CleanupPolicy> configurationData = Arrays.asList(
        createCleanupPolicy("test_1", "format_1", "delete", "notes 1", criteria),
        createCleanupPolicy("test_2", "format_2", "clean", "notes 2", criteria));

    when(cleanupPolicyStorage.getAll()).thenReturn(configurationData);

    CleanupPolicyExport exporter = new CleanupPolicyExport(cleanupPolicyStorage);
    exporter.export(jsonFile);
    List<CleanupPolicyData> importedData = jsonExporter.importFromJson(jsonFile, CleanupPolicyData.class);

    assertThat(importedData.size(), is(2));
    importedData.forEach(data -> assertThat(data.getName(), anyOf(is("test_1"), is("test_2"))));
    importedData.forEach(data -> assertThat(data.getFormat(), anyOf(is("format_1"), is("format_2"))));
    importedData.forEach(data -> assertThat(data.getMode(), anyOf(is("delete"), is("clean"))));
    importedData.forEach(data -> assertThat(data.getNotes(), anyOf(is("notes 1"), is("notes 2"))));
    importedData.forEach(data -> assertThat(data.getCriteria(), is(criteria)));
  }
  
  @Test
  void testConcurrentExportImportWithVirtualThreads() throws Exception {
    Map<String, String> criteria = ImmutableMap.of(
        "regex", "*.json",
        "lastDownloaded", "100",
        "lastBlobUpdated", "200");
    List<CleanupPolicy> configurationData = Arrays.asList(
        createCleanupPolicy("test_1", "format_1", "delete", "notes 1", criteria),
        createCleanupPolicy("test_2", "format_2", "clean", "notes 2", criteria));

    when(cleanupPolicyStorage.getAll()).thenReturn(configurationData);

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int concurrentTasks = 10;
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent export/import operations using virtual threads
      for (int i = 0; i < concurrentTasks; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Create a unique file for each task
            File taskFile = File.createTempFile("CleanupPolicy_" + taskId, ".json");
            try {
              // Create exporter and perform export
              CleanupPolicyExport exporter = new CleanupPolicyExport(cleanupPolicyStorage);
              exporter.export(taskFile);
              
              // Import and validate
              List<CleanupPolicyData> importedData = jsonExporter.importFromJson(taskFile, CleanupPolicyData.class);
              
              if (importedData.size() == 2 && 
                  (importedData.get(0).getName().equals("test_1") || importedData.get(0).getName().equals("test_2")) &&
                  (importedData.get(1).getName().equals("test_1") || importedData.get(1).getName().equals("test_2"))) {
                successCount.incrementAndGet();
              }
            } 
            finally {
              // Clean up the file
              taskFile.delete();
            }
          } 
          catch (Exception e) {
            // Log exception but don't fail the test - we'll check success count
            System.err.println("Error in virtual thread task " + taskId + ": " + e.getMessage());
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All concurrent tasks should complete", completed, is(true));
      assertThat("All export/import operations should succeed", successCount.get(), is(concurrentTasks));
    } 
    finally {
      executor.shutdown();
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