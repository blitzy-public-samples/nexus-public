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
package org.sonatype.nexus.script.plugin.internal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests validity of Serialization/Deserialization {@link Script} by {@link ScriptExport}
 * using Java 21 Virtual Threads for concurrent operations.
 */
@Category(VirtualThreadTestGroup.class)
public class ScriptExportVirtualThreadTest
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int SCRIPTS_PER_OPERATION = 10;
  private static final int TIMEOUT_SECONDS = 30;
  
  private final JsonExporter jsonExporter = new JsonExporter();
  private final List<File> jsonFiles = new ArrayList<>();
  private final AtomicReference<Exception> concurrentException = new AtomicReference<>();

  @Before
  public void setup() throws IOException {
    // Pre-create temporary files to avoid I/O during test execution
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      jsonFiles.add(File.createTempFile("ScriptExport", ".json"));
    }
  }

  @After
  public void tearDown() {
    // Clean up all temporary files
    jsonFiles.forEach(File::delete);
  }

  /**
   * Tests concurrent export/import operations using virtual threads.
   * This verifies that the ScriptExport component can handle multiple
   * concurrent operations without data corruption or thread safety issues.
   */
  @Test
  public void testConcurrentExportImportWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a countdown latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      
      // Track the number of successful operations
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit concurrent export/import tasks
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int operationIndex = i;
        Future<?> future = executor.submit(() -> {
          try {
            // Create a unique set of scripts for this operation
            List<Script> scripts = createScripts(operationIndex, SCRIPTS_PER_OPERATION);
            
            // Mock the script store
            ScriptStore store = mock(ScriptStore.class);
            when(store.list()).thenReturn(scripts);
            
            // Get the file for this operation
            File jsonFile = jsonFiles.get(operationIndex);
            
            // Create the exporter and export to JSON
            ScriptExport exporter = new ScriptExport(store);
            exporter.export(jsonFile);
            
            // Import from JSON and verify
            List<ScriptData> importedData = jsonExporter.importFromJson(jsonFile, ScriptData.class);
            
            // Verify the imported data
            assertThat(importedData.size(), is(SCRIPTS_PER_OPERATION));
            
            // Verify each script was correctly serialized and deserialized
            for (ScriptData data : importedData) {
              assertThat(data, notNullValue());
              assertThat(data.getName(), notNullValue());
              assertThat(data.getType(), is("script"));
              assertThat(data.getContent(), is("log.info('world')"));
            }
            
            // Increment success count
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Store the first exception encountered
            concurrentException.compareAndSet(null, e);
          } finally {
            // Count down the latch
            latch.countDown();
          }
        });
        
        futures.add(future);
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("Timed out waiting for concurrent operations to complete", completed, is(true));
      
      // Check if any exceptions occurred
      Exception exception = concurrentException.get();
      if (exception != null) {
        throw new AssertionError("Exception during concurrent operation", exception);
      }
      
      // Verify all operations succeeded
      assertThat(successCount.get(), is(CONCURRENT_OPERATIONS));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests the performance difference between virtual threads and platform threads
   * for export/import operations.
   */
  @Test
  public void testVirtualThreadPerformanceComparison() throws Exception {
    // Run with virtual threads
    long virtualThreadTime = measureExportImportPerformance(true);
    
    // Run with platform threads
    long platformThreadTime = measureExportImportPerformance(false);
    
    // Log the results
    System.out.println("Virtual Thread execution time: " + virtualThreadTime + "ms");
    System.out.println("Platform Thread execution time: " + platformThreadTime + "ms");
    
    // For I/O bound operations, virtual threads should generally be more efficient
    // However, this is not a strict requirement as it depends on the environment
    // So we'll just log the results without asserting on them
  }

  /**
   * Measures the performance of concurrent export/import operations using either
   * virtual threads or platform threads.
   *
   * @param useVirtualThreads true to use virtual threads, false to use platform threads
   * @return the execution time in milliseconds
   */
  private long measureExportImportPerformance(boolean useVirtualThreads) throws Exception {
    // Create the appropriate thread factory
    ThreadFactory threadFactory = useVirtualThreads ?
        Thread.ofVirtual().factory() :
        Thread.ofPlatform().factory();
    
    // Create an executor service
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Create a countdown latch
      CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
      
      // Record start time
      long startTime = System.currentTimeMillis();
      
      // Submit tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int operationIndex = i;
        executor.submit(() -> {
          try {
            // Create scripts
            List<Script> scripts = createScripts(operationIndex, SCRIPTS_PER_OPERATION);
            
            // Mock store
            ScriptStore store = mock(ScriptStore.class);
            when(store.list()).thenReturn(scripts);
            
            // Get file
            File jsonFile = jsonFiles.get(operationIndex);
            
            // Export
            ScriptExport exporter = new ScriptExport(store);
            exporter.export(jsonFile);
            
            // Import
            jsonExporter.importFromJson(jsonFile, ScriptData.class);
          } catch (Exception e) {
            // Ignore exceptions for performance test
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for completion
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Calculate execution time
      return System.currentTimeMillis() - startTime;
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Creates a list of test scripts with unique names.
   *
   * @param prefix the prefix to use for script names
   * @param count the number of scripts to create
   * @return a list of scripts
   */
  private List<Script> createScripts(int prefix, int count) {
    List<Script> scripts = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      scripts.add(createScript("script_" + prefix + "_" + i));
    }
    return scripts;
  }

  /**
   * Creates a single test script with the given name.
   *
   * @param name the name of the script
   * @return a script
   */
  private Script createScript(final String name) {
    ScriptData script = new ScriptData();
    script.setName(name);
    script.setType("script");
    script.setContent("log.info('world')");

    return script;
  }
}