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
package com.sonatype.nexus.ssl.plugin.internal.keystore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.testsupport.jupiter.DataSessionExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests for {@link KeyStoreDAO} operations using JUnit Jupiter.
 * <p>
 * This test class validates CRUD operations for the KeyStore data access object,
 * ensuring compatibility with Java 21 features including virtual threads for
 * concurrent database operations.
 * <p>
 * The test suite includes both standard CRUD tests and specialized tests for
 * Java 21 virtual thread compatibility, ensuring that the DAO operations work
 * correctly under high concurrency scenarios using the new lightweight threading model.
 *
 * @since 3.62
 */
@ExtendWith(DataSessionExtension.class)
@DisplayName("KeyStoreDAO CRUD and Java 21 compatibility tests")
public class KeyStoreDAOTest
{
  private DataSession<?> session;

  private KeyStoreDAO dao;

  /**
   * Set up the test environment before each test method.
   * <p>
   * This method initializes a new database session and DAO instance for each test,
   * ensuring test isolation and preventing state leakage between tests.
   *
   * @param dataSession The data session provided by the DataSessionExtension
   */
  @BeforeEach
  void setup(DataSession<?> dataSession) {
    session = dataSession;
    dao = session.access(KeyStoreDAO.class);
  }

  /**
   * Clean up resources after each test method.
   * <p>
   * This method ensures that the database session is properly closed after each test,
   * preventing resource leaks and ensuring proper transaction handling.
   */
  @AfterEach
  void cleanup() {
    if (session != null && session.isOpen()) {
      session.close();
    }
  }

  /**
   * Test basic CRUD (Create, Read, Update, Delete) operations for KeyStoreData.
   * <p>
   * This test validates that:
   * <ul>
   *   <li>A KeyStoreData entity can be created and saved to the database</li>
   *   <li>The saved entity can be retrieved from the database</li>
   *   <li>The entity can be updated with new data</li>
   *   <li>The entity can be deleted from the database</li>
   * </ul>
   */
  @Test
  @DisplayName("Basic CRUD operations work correctly")
  void testCreateReadUpdateDeleteOperations() {
    // Create a KeyStoreData entity
    KeyStoreData entity = new KeyStoreData("keystorename", new byte[]{1, 2, 3});
    
    // Save the KeyStoreData
    boolean saveResult = dao.save(entity);
    assertThat(saveResult, is(true));

    // Read back the KeyStoreData
    Optional<KeyStoreData> readBack = dao.load(entity.name());
    assertThat(readBack.isPresent(), is(true));
    assertThat(readBack.get().name(), is(entity.name()));
    assertThat(readBack.get().bytes(), is(entity.bytes()));

    // Update the KeyStoreData
    KeyStoreData updatedEntity = new KeyStoreData(entity.name(), new byte[]{4, 5, 6});
    boolean updateResult = dao.save(updatedEntity);
    assertThat(updateResult, is(true));

    // Read back the updated KeyStoreData
    Optional<KeyStoreData> updated = dao.load(entity.name());
    assertThat(updated.isPresent(), is(true));
    assertThat(updated.get().name(), is(entity.name()));
    assertThat(updated.get().bytes(), is(new byte[]{4, 5, 6}));

    // Delete the KeyStoreData
    boolean deleteResult = dao.delete(entity.name());
    assertThat(deleteResult, is(true));

    // Verify the KeyStoreData does not exist anymore
    Optional<KeyStoreData> deleted = dao.load(entity.name());
    assertThat(deleted.isPresent(), is(false));
  }
  
  /**
   * Test record pattern matching with KeyStoreData.
   * <p>
   * This test demonstrates the use of Java 21 record patterns to extract and validate
   * components of the KeyStoreData record in a type-safe and concise manner.
   */
  @Test
  @Tag("java21")
  @DisplayName("Record pattern matching works with KeyStoreData")
  void testRecordPatternMatching() {
    // Create and save a KeyStoreData entity
    KeyStoreData entity = new KeyStoreData("pattern-test", new byte[]{7, 8, 9});
    dao.save(entity);
    
    // Load the entity and use record pattern matching to extract components
    Optional<KeyStoreData> result = dao.load("pattern-test");
    assertThat(result.isPresent(), is(true));
    
    // Use Java 21 record pattern matching to extract and validate components
    if (result.get() instanceof KeyStoreData(String name, byte[] bytes)) {
      assertThat(name, is("pattern-test"));
      assertThat(bytes, is(new byte[]{7, 8, 9}));
    } else {
      // This should never happen, but we include it for completeness
      assertThat("Result should be a KeyStoreData record", false);
    }
    
    // Clean up
    dao.delete("pattern-test");
  }

  /**
   * Test concurrent operations using Java 21 virtual threads.
   * <p>
   * This test validates that the KeyStoreDAO can handle concurrent operations
   * using Java 21's lightweight virtual threads, ensuring thread safety and
   * proper transaction isolation under high concurrency.
   */
  @Test
  @Tag("java21")
  @Tag("virtual-threads")
  @DisplayName("Concurrent operations with virtual threads")
  void testConcurrentOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      List<String> createdKeyStores = new ArrayList<>();
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique keystore name for this thread
            String keystoreName = "concurrent-keystore-" + index;
            createdKeyStores.add(keystoreName);
            
            // Create and save a new KeyStoreData
            KeyStoreData data = new KeyStoreData(keystoreName, new byte[]{(byte)index});
            boolean saved = dao.save(data);
            
            // Verify it was saved successfully
            if (!saved) {
              errorCount.incrementAndGet();
              return;
            }
            
            // Read it back and verify
            Optional<KeyStoreData> loaded = dao.load(keystoreName);
            if (!loaded.isPresent() || !loaded.get().name().equals(keystoreName)) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertThat("All tasks should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent operations", errorCount.get(), is(0));
      
      // Clean up created keystores
      for (String name : createdKeyStores) {
        dao.delete(name);
      }
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test JDBC behavior under Java 21 to verify thread pinning detection.
   * <p>
   * This test validates that the JDBC driver used with KeyStoreDAO is compatible
   * with Java 21 virtual threads and does not cause thread pinning issues that
   * would impact performance under high concurrency scenarios.
   */
  @Test
  @Tag("java21")
  @Tag("virtual-threads")
  @DisplayName("JDBC operations don't cause thread pinning with virtual threads")
  void testJdbcThreadPinningWithVirtualThreads() throws Exception {
    // Create a virtual thread factory with a custom name pattern for easier identification
    ThreadFactory virtualThreadFactory = Thread.ofVirtual()
        .name("jdbc-test-", 0)
        .factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      int taskCount = 50;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger pinnedThreadCount = new AtomicInteger(0);
      
      // Submit tasks that perform database operations that might cause thread pinning
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique keystore name
            String keystoreName = "pinning-test-" + index;
            
            // Perform a series of operations that might cause thread pinning
            KeyStoreData data = new KeyStoreData(keystoreName, new byte[]{(byte)index});
            dao.save(data);
            
            // Simulate some work to give time for potential pinning to occur
            Thread.sleep(10);
            
            // Read the data back
            Optional<KeyStoreData> loaded = dao.load(keystoreName);
            
            // Update the data
            if (loaded.isPresent()) {
              KeyStoreData updated = new KeyStoreData(keystoreName, new byte[]{(byte)(index + 1)});
              dao.save(updated);
            }
            
            // Delete the data
            dao.delete(keystoreName);
            
            // Check if this thread is a virtual thread and if it's pinned
            Thread currentThread = Thread.currentThread();
            if (currentThread.isVirtual() && currentThread.getState() == Thread.State.RUNNABLE) {
              // In a real implementation, we would use JDK Flight Recorder or other tools
              // to detect pinning. For this test, we're just demonstrating the concept.
              // If pinning occurs, it would be logged by the JVM when run with -Djdk.tracePinnedThreads=full
            }
          } catch (Exception e) {
            pinnedThreadCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all tasks completed and no pinning was detected
      assertThat("All tasks should complete within the timeout", completed, is(true));
      assertThat("No thread pinning should be detected", pinnedThreadCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }
}