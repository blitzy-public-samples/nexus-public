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
package org.sonatype.nexus.virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.testsuite.groups.Java21TestGroup;
import org.sonatype.nexus.content.testsuite.groups.SQLTestGroup;
import org.sonatype.nexus.content.testsuite.groups.VirtualThreadTestGroup;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.plugin.internal.ScriptDAO;
import org.sonatype.nexus.script.plugin.internal.ScriptData;
import org.sonatype.nexus.testdb.DataSessionRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests the {@link ScriptDAO} database operations using Java 21 Virtual Threads to validate compatibility
 * with the new lightweight threading model.
 */
@Category({SQLTestGroup.class, Java21TestGroup.class, VirtualThreadTestGroup.class})
public class ScriptDAOVirtualThreadTest
    extends TestSupport
{
  @Rule
  public DataSessionRule sessionRule = new DataSessionRule().access(ScriptDAO.class);

  private DataSession<?> session;

  private ScriptDAO dao;

  @Before
  public void setUp() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(ScriptDAO.class);
  }

  @After
  public void tearDown() {
    session.close();
  }

  /**
   * Tests basic CRUD operations using a single virtual thread.
   */
  @Test
  public void testCrudOperationsWithVirtualThread() throws Exception {
    // Create a virtual thread to perform CRUD operations
    Thread.ofVirtual().name("crud-test").start(() -> {
      ScriptData script = new ScriptData();
      script.setName("hello-virtual");
      script.setContent("log.info('hello from virtual thread')");

      // Create
      dao.create(script);

      // Read
      Script read = dao.read(script.getName()).orElse(null);

      assertThat(read, is(notNullValue()));
      assertThat(read.getName(), is(script.getName()));
      assertThat(read.getType(), is(script.getType()));
      assertThat(read.getContent(), is(script.getContent()));

      // Update
      script.setContent("log.info('updated from virtual thread')");
      dao.update(script);

      Script update = dao.read(script.getName()).orElse(null);

      assertThat(update, is(notNullValue()));
      assertThat(update.getName(), is(script.getName()));
      assertThat(update.getType(), is(script.getType()));
      assertThat(update.getContent(), is(script.getContent()));

      // Delete
      dao.delete(script.getName());

      assertThat(dao.read(script.getName()).isPresent(), is(false));
    }).join(); // Wait for the virtual thread to complete
  }

  /**
   * Tests concurrent script operations with multiple virtual threads.
   */
  @Test
  public void testConcurrentScriptOperationsWithVirtualThreads() throws Exception {
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit tasks to create, read, update, and delete scripts concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          try {
            String scriptName = "concurrent-script-" + index;
            ScriptData script = new ScriptData();
            script.setName(scriptName);
            script.setContent("log.info('concurrent script " + index + "')");
            
            // Create
            dao.create(script);
            
            // Read
            Optional<Script> readResult = dao.read(scriptName);
            assertThat(readResult.isPresent(), is(true));
            assertThat(readResult.get().getName(), is(scriptName));
            
            // Update
            script.setContent("log.info('updated concurrent script " + index + "')");
            dao.update(script);
            
            // Read again to verify update
            Optional<Script> updateResult = dao.read(scriptName);
            assertThat(updateResult.isPresent(), is(true));
            assertThat(updateResult.get().getContent(), is(script.getContent()));
            
            // Delete
            dao.delete(scriptName);
            assertThat(dao.read(scriptName).isPresent(), is(false));
            
            successCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Check for any exceptions
      for (Future<?> future : futures) {
        try {
          future.get(); // Will throw an exception if the task failed
        } catch (ExecutionException e) {
          throw new AssertionError("Virtual thread execution failed", e.getCause());
        }
      }
      
      // Verify all operations completed successfully
      assertThat(successCount.get(), is(threadCount));
    }
  }

  /**
   * Tests performance under load with many concurrent read operations using virtual threads.
   */
  @Test
  public void testLoadWithVirtualThreads() throws Exception {
    // Create a test script to read
    ScriptData script = new ScriptData();
    script.setName("load-test-script");
    script.setContent("log.info('load test script')");
    dao.create(script);
    
    try {
      int threadCount = 1000; // Simulate 1000 concurrent read operations
      CountDownLatch latch = new CountDownLatch(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create a virtual thread executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit tasks to read the script concurrently
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              Optional<Script> result = dao.read(script.getName());
              if (result.isPresent() && script.getName().equals(result.get().getName())) {
                successCount.incrementAndGet();
              }
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all threads to complete or timeout after 60 seconds
        boolean completed = latch.await(60, TimeUnit.SECONDS);
        assertThat("All virtual threads completed in time", completed, is(true));
        
        // Verify all read operations completed successfully
        assertThat(successCount.get(), is(threadCount));
      }
    } finally {
      // Clean up the test script
      dao.delete(script.getName());
    }
  }
}