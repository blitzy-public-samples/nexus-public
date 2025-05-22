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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.testsuite.groups.SQLTestGroup;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.testdb.DataSessionRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Integration test for {@link ScriptDAO} using Java 21 Virtual Threads.
 * 
 * This test validates that ScriptDAO operations (create, read, update, delete) function correctly
 * when executed concurrently using virtual threads. It extends the functionality of the standard
 * ScriptDAOTest by adding concurrent operation testing with a high number of virtual threads,
 * ensuring that database operations remain thread-safe and efficient under high concurrency.
 */
@Category(SQLTestGroup.class)
public class ScriptDAOVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Rule
  public DataSessionRule sessionRule = new DataSessionRule().access(ScriptDAO.class);

  private DataSession<?> session;

  private ScriptDAO dao;
  
  private final ConcurrentHashMap<String, String> createdScripts = new ConcurrentHashMap<>();

  @Before
  public void setUp() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(ScriptDAO.class);
  }

  @After
  public void tearDown() {
    // Clean up any scripts created during tests
    createdScripts.keySet().forEach(name -> {
      try {
        dao.delete(name);
      } catch (Exception e) {
        log.warn("Failed to delete script {}: {}", name, e.getMessage());
      }
    });
    createdScripts.clear();
    
    session.close();
  }

  /**
   * Tests basic CRUD operations with a single virtual thread to ensure compatibility.
   */
  @Test
  public void testBasicCrudWithVirtualThread() throws Exception {
    AtomicBoolean success = new AtomicBoolean(false);
    
    Thread vt = Thread.ofVirtual().name("basic-crud-test").start(() -> {
      try {
        String scriptName = "vt-test-" + UUID.randomUUID().toString().substring(0, 8);
        createdScripts.put(scriptName, scriptName);
        
        // Create
        ScriptData script = new ScriptData();
        script.setName(scriptName);
        script.setContent("log.info('hello from virtual thread')");
        dao.create(script);
        
        // Read
        Script read = dao.read(script.getName()).orElse(null);
        assertThat(read, is(notNullValue()));
        assertThat(read.getName(), is(script.getName()));
        assertThat(read.getContent(), is(script.getContent()));
        
        // Update
        script.setContent("log.info('updated from virtual thread')");
        dao.update(script);
        
        Script updated = dao.read(script.getName()).orElse(null);
        assertThat(updated, is(notNullValue()));
        assertThat(updated.getName(), is(script.getName()));
        assertThat(updated.getContent(), is(script.getContent()));
        
        // Delete
        dao.delete(script.getName());
        assertThat(dao.read(script.getName()).isPresent(), is(false));
        createdScripts.remove(scriptName);
        
        success.set(true);
      } catch (Exception e) {
        log.error("Error in virtual thread test", e);
      }
    });
    
    vt.join();
    assertThat("Basic CRUD operations should succeed with virtual thread", success.get(), is(true));
  }

  /**
   * Tests concurrent creation, reading, updating, and deletion of scripts using virtual threads.
   * This test creates multiple virtual threads that each perform multiple CRUD operations,
   * verifying thread safety and proper database interaction under high concurrency.
   */
  @Test
  public void testConcurrentCrudWithVirtualThreads() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<Thread> threads = new ArrayList<>();
    
    // Create multiple virtual threads to perform concurrent operations
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadNum = i;
      Thread vt = Thread.ofVirtual().name("vt-crud-" + i).start(() -> {
        try {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            String scriptName = "vt-" + threadNum + "-" + j + "-" + UUID.randomUUID().toString().substring(0, 4);
            createdScripts.put(scriptName, scriptName);
            
            // Create
            ScriptData script = new ScriptData();
            script.setName(scriptName);
            script.setContent("log.info('thread " + threadNum + " operation " + j + "')");
            dao.create(script);
            
            // Read
            Optional<Script> readResult = dao.read(scriptName);
            if (readResult.isPresent()) {
              Script read = readResult.get();
              assertThat(read.getName(), is(scriptName));
              assertThat(read.getContent(), is(script.getContent()));
              
              // Update
              script.setContent("log.info('updated by thread " + threadNum + " operation " + j + "')");
              dao.update(script);
              
              // Read again to verify update
              Script updated = dao.read(scriptName).orElse(null);
              assertThat(updated, is(notNullValue()));
              assertThat(updated.getContent(), is(script.getContent()));
              
              // Delete
              dao.delete(scriptName);
              assertThat(dao.read(scriptName).isPresent(), is(false));
              createdScripts.remove(scriptName);
              
              successCount.incrementAndGet();
            } else {
              log.error("Failed to read script after creation: {}", scriptName);
              errorCount.incrementAndGet();
            }
          }
        } catch (Exception e) {
          log.error("Error in virtual thread {}: {}", threadNum, e.getMessage(), e);
          errorCount.incrementAndGet();
        } finally {
          completionLatch.countDown();
        }
      });
      
      threads.add(vt);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete or timeout
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("All threads should complete within timeout", completed, is(true));
    assertThat("All operations should succeed", errorCount.get(), is(0));
    assertThat("Expected successful operations", successCount.get(), is(CONCURRENT_THREADS * OPERATIONS_PER_THREAD));
  }

  /**
   * Tests the behavior of virtual threads with database connections under high load.
   * This test creates a large number of virtual threads that all perform database operations
   * simultaneously, verifying that the database connection pool and virtual thread scheduler
   * handle the load appropriately.
   */
  @Test
  public void testDatabaseConnectionWithVirtualThreads() throws Exception {
    // Use a virtual thread per task executor for maximum concurrency
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit tasks to the executor
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int threadNum = i;
        executor.submit(() -> {
          try {
            String scriptName = "db-vt-" + threadNum + "-" + UUID.randomUUID().toString().substring(0, 4);
            createdScripts.put(scriptName, scriptName);
            
            // Create a script
            ScriptData script = new ScriptData();
            script.setName(scriptName);
            script.setContent("log.info('database connection test from thread " + threadNum + "')");
            dao.create(script);
            
            // Perform a read operation to verify database connection
            Script read = dao.read(scriptName).orElse(null);
            assertThat(read, is(notNullValue()));
            assertThat(read.getName(), is(scriptName));
            
            // Delete the script
            dao.delete(scriptName);
            createdScripts.remove(scriptName);
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error in database connection test thread {}: {}", threadNum, e.getMessage(), e);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All database operations should complete within timeout", completed, is(true));
      assertThat("All database operations should succeed", successCount.get(), is(CONCURRENT_THREADS));
    }
  }

  /**
   * Tests for thread pinning issues when using virtual threads with database operations.
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
   * which can happen with synchronized blocks or native methods. This test attempts to detect
   * such issues by monitoring thread execution times.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    int numThreads = 20;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(numThreads);
    List<Long> executionTimes = new ArrayList<>();
    
    // Create threads that will perform operations that might cause pinning
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      final int threadNum = i;
      Thread vt = Thread.ofVirtual().name("pinning-test-" + i).start(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          long startTime = System.nanoTime();
          
          // Perform database operations that might cause pinning
          String scriptName = "pin-" + threadNum + "-" + UUID.randomUUID().toString().substring(0, 4);
          createdScripts.put(scriptName, scriptName);
          
          ScriptData script = new ScriptData();
          script.setName(scriptName);
          script.setContent("log.info('pinning test')");
          
          // Use synchronized block which might cause pinning
          synchronized (this) {
            dao.create(script);
            Thread.sleep(50); // Simulate some work while holding the lock
            
            Script read = dao.read(scriptName).orElse(null);
            assertThat(read, is(notNullValue()));
            
            dao.delete(scriptName);
            createdScripts.remove(scriptName);
          }
          
          long endTime = System.nanoTime();
          long executionTime = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
          
          synchronized (executionTimes) {
            executionTimes.add(executionTime);
          }
          
        } catch (Exception e) {
          log.error("Error in pinning test thread {}: {}", threadNum, e.getMessage(), e);
        } finally {
          completionLatch.countDown();
        }
      });
      
      threads.add(vt);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All pinning test threads should complete", completed, is(true));
    
    // Analyze execution times to detect potential pinning
    // If threads are pinned, we'd expect to see execution times that are multiples of each other
    // since pinned threads would execute sequentially rather than concurrently
    if (!executionTimes.isEmpty()) {
      long totalTime = executionTimes.stream().mapToLong(Long::longValue).sum();
      double avgTime = totalTime / (double) executionTimes.size();
      
      log.info("Thread pinning test results:");
      log.info("  Average execution time: {} ms", avgTime);
      log.info("  Execution times: {}", executionTimes);
      
      // Log a warning if we detect potential pinning
      long maxTime = executionTimes.stream().mapToLong(Long::longValue).max().orElse(0);
      if (maxTime > avgTime * 3) {
        log.warn("Potential thread pinning detected: max execution time ({} ms) is significantly higher than average ({} ms)", 
            maxTime, avgTime);
      }
    }
  }
}