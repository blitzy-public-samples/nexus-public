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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.testsuite.groups.SQLTestGroup;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.plugin.internal.ScriptDAO;
import org.sonatype.nexus.script.plugin.internal.ScriptData;
import org.sonatype.nexus.testdb.DataSessionRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests the ScriptDAO database operations using Java 21 Virtual Threads to validate improved concurrency
 * for I/O-bound database operations.
 */
@SQLTestGroup
public class ScriptDAOVirtualThreadTest
    extends TestSupport
{
  private DataSessionRule sessionRule = new DataSessionRule().access(ScriptDAO.class);

  private DataSession<?> session;

  private ScriptDAO dao;

  @BeforeEach
  public void setUp() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(ScriptDAO.class);
  }

  @AfterEach
  public void tearDown() {
    session.close();
  }

  /**
   * Tests basic CRUD operations using a virtual thread.
   */
  @Test
  public void testCrudOperationsWithVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      executor.submit(() -> {
        // Create a script
        ScriptData script = new ScriptData();
        script.setName("hello-virtual");
        script.setContent("log.info('hello from virtual thread')");
        
        dao.create(script);
        
        // Read the script
        Script read = dao.read(script.getName()).orElse(null);
        
        assertNotNull(read);
        assertEquals(script.getName(), read.getName());
        assertEquals(script.getType(), read.getType());
        assertEquals(script.getContent(), read.getContent());
        
        // Update the script
        script.setContent("log.info('updated from virtual thread')");
        dao.update(script);
        
        Script updated = dao.read(script.getName()).orElse(null);
        
        assertNotNull(updated);
        assertEquals(script.getName(), updated.getName());
        assertEquals(script.getType(), updated.getType());
        assertEquals(script.getContent(), updated.getContent());
        
        // Delete the script
        dao.delete(script.getName());
        
        Optional<Script> deleted = dao.read(script.getName());
        assertFalse(deleted.isPresent());
      }).get(); // Wait for completion
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent operations using multiple virtual threads.
   */
  @Test
  public void testConcurrentOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Create scripts concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String scriptName = "concurrent-script-" + index;
            String scriptContent = "log.info('concurrent script " + index + "')";
            
            // Create script
            ScriptData script = new ScriptData();
            script.setName(scriptName);
            script.setContent(scriptContent);
            
            dao.create(script);
            
            // Read script
            Script read = dao.read(scriptName).orElse(null);
            if (read == null || !read.getContent().equals(scriptContent)) {
              errorCount.incrementAndGet();
            }
            
            // Update script
            script.setContent(scriptContent + " - updated");
            dao.update(script);
            
            // Read updated script
            Script updated = dao.read(scriptName).orElse(null);
            if (updated == null || !updated.getContent().equals(script.getContent())) {
              errorCount.incrementAndGet();
            }
            
            // Delete script
            dao.delete(scriptName);
            
            // Verify deletion
            if (dao.read(scriptName).isPresent()) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in virtual thread operation", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All virtual thread operations should complete within timeout");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent operations");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for database operations.
   */
  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    int operationCount = 100;
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      executeWithThreads(operationCount, Thread.ofPlatform().factory());
      return null;
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      executeWithThreads(operationCount, Thread.ofVirtual().factory());
      return null;
    });
    
    log.info("Performance comparison for {} operations:", operationCount);
    log.info("Platform threads: {} ms", platformThreadTime);
    log.info("Virtual threads: {} ms", virtualThreadTime);
    log.info("Improvement ratio: {}", (double) platformThreadTime / virtualThreadTime);
    
    // We don't assert on specific performance improvements as they can vary by environment,
    // but we log the results for analysis
  }

  /**
   * Tests for thread pinning detection during database operations.
   * Thread pinning occurs when a virtual thread is forced to stay on a carrier thread,
   * which can happen with synchronized blocks or native methods.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection via system property
    // Note: This would typically be set via JVM arg: -Djdk.tracePinnedThreads=full
    String originalPinningProperty = System.getProperty("jdk.tracePinnedThreads");
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    try {
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
      
      try {
        executor.submit(() -> {
          // Create a script with a synchronized block that could cause pinning
          synchronized (this) {
            ScriptData script = new ScriptData();
            script.setName("pinning-test");
            script.setContent("log.info('testing pinning')");
            
            dao.create(script);
            
            // Read the script
            Script read = dao.read(script.getName()).orElse(null);
            assertNotNull(read);
            
            // Delete the script
            dao.delete(script.getName());
          }
        }).get(); // Wait for completion
        
        // If pinning occurs, it would be logged to stderr by the JVM
        // We don't assert on this as it's environment-dependent
        log.info("Check logs for thread pinning warnings");
      } finally {
        executor.shutdown();
      }
    } finally {
      // Restore original property value
      if (originalPinningProperty != null) {
        System.setProperty("jdk.tracePinnedThreads", originalPinningProperty);
      } else {
        System.clearProperty("jdk.tracePinnedThreads");
      }
    }
  }

  /**
   * Helper method to execute database operations with the specified thread factory.
   */
  private void executeWithThreads(int count, ThreadFactory threadFactory) {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(count);
    List<String> scriptNames = new ArrayList<>();
    
    try {
      // Create scripts
      for (int i = 0; i < count; i++) {
        final int index = i;
        final String scriptName = "perf-script-" + index;
        scriptNames.add(scriptName);
        
        executor.submit(() -> {
          try {
            // Create
            ScriptData script = new ScriptData();
            script.setName(scriptName);
            script.setContent("log.info('performance test " + index + "')");
            dao.create(script);
            
            // Read
            dao.read(scriptName);
            
            // Update
            script.setContent(script.getContent() + " - updated");
            dao.update(script);
            
            // Read again
            dao.read(scriptName);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Test interrupted", e);
    } finally {
      // Clean up created scripts
      for (String scriptName : scriptNames) {
        try {
          if (dao.read(scriptName).isPresent()) {
            dao.delete(scriptName);
          }
        } catch (Exception e) {
          log.error("Error cleaning up script: {}", scriptName, e);
        }
      }
      
      executor.shutdown();
    }
  }

  /**
   * Measures execution time of a supplier function.
   */
  private <T> long measureExecutionTime(Supplier<T> supplier) {
    long startTime = System.currentTimeMillis();
    supplier.get();
    return System.currentTimeMillis() - startTime;
  }
}