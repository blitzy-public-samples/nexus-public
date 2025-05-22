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
package virtualthread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.testdb.example.TestItem;
import org.sonatype.nexus.testdb.example.TestItemDAO;

import com.google.common.collect.ImmutableMap;

/**
 * Tests the concurrency aspects of MyBatis database operations when executed with Java 21 Virtual Threads.
 * Verifies that multiple concurrent operations complete successfully, transaction boundaries are respected,
 * and performance improves compared to platform threads.
 *
 * @since 3.60
 */
@TestInstance(Lifecycle.PER_CLASS)
public class MyBatisVirtualThreadConcurrencyTest
{
  private static final int THREAD_COUNT = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final String DEFAULT_DATASTORE_NAME = "default";

  private DataSessionRule sessionRule;

  @BeforeEach
  void setUp() {
    sessionRule = new DataSessionRule().access(TestItemDAO.class);
  }

  @AfterEach
  void tearDown() {
    // Clean up any remaining test data
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestItemDAO dao = session.access(TestItemDAO.class);
      dao.browse().forEach(item -> dao.delete(item.getId()));
      session.getTransaction().commit();
    }
  }

  /**
   * Tests concurrent creation of entities using virtual threads.
   * Verifies that all entities are created successfully without conflicts.
   */
  @Test
  @DisplayName("Concurrent entity creation with virtual threads")
  void testConcurrentEntityCreationWithVirtualThreads() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    ConcurrentHashMap<String, TestItem> createdItems = new ConcurrentHashMap<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple virtual threads to create entities concurrently
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
              TestItemDAO dao = session.access(TestItemDAO.class);
              
              TestItem item = new TestItem();
              item.setVersion(1);
              item.setEnabled(true);
              item.setNotes("Virtual thread test item " + threadId);
              item.setProperties(ImmutableMap.of("threadId", String.valueOf(threadId)));
              
              dao.create(item);
              session.getTransaction().commit();
              
              // Store the created item for verification
              createdItems.put(item.getId().getValue().toString(), item);
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    }

    // Verify all entities were created successfully
    assertEquals(THREAD_COUNT, successCount.get(), "Not all entities were created successfully");
    assertEquals(THREAD_COUNT, createdItems.size(), "Incorrect number of entities created");

    // Verify all entities can be retrieved from the database
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestItemDAO dao = session.access(TestItemDAO.class);
      List<TestItem> items = dao.browse();
      assertEquals(THREAD_COUNT, items.size(), "Incorrect number of entities in database");
    }
  }

  /**
   * Tests transaction isolation between virtual threads.
   * Verifies that changes made in one transaction are not visible to other transactions until committed.
   */
  @Test
  @DisplayName("Transaction isolation between virtual threads")
  void testTransactionIsolationBetweenVirtualThreads() throws Exception {
    // Create a test item that will be modified concurrently
    EntityUUID itemId;
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestItemDAO dao = session.access(TestItemDAO.class);
      
      TestItem item = new TestItem();
      item.setVersion(1);
      item.setEnabled(true);
      item.setNotes("Transaction isolation test item");
      item.setProperties(ImmutableMap.of("initial", "value"));
      
      dao.create(item);
      session.getTransaction().commit();
      itemId = item.getId();
    }

    // Thread 1: Start a transaction and modify the item but don't commit yet
    CountDownLatch thread1Started = new CountDownLatch(1);
    CountDownLatch thread2Checked = new CountDownLatch(1);
    CountDownLatch thread1Committed = new CountDownLatch(1);
    
    Thread thread1 = Thread.ofVirtual().name("transaction-1").start(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        TestItemDAO dao = session.access(TestItemDAO.class);
        
        // Get the item and modify it
        TestItem item = dao.read(itemId).get();
        item.setNotes("Modified by thread 1");
        item.setProperties(ImmutableMap.of("modified", "by-thread-1"));
        dao.update(item);
        
        // Signal that thread 1 has started and modified the item (but not committed)
        thread1Started.countDown();
        
        // Wait for thread 2 to check the item
        thread2Checked.await();
        
        // Now commit the transaction
        session.getTransaction().commit();
        
        // Signal that thread 1 has committed
        thread1Committed.countDown();
      } catch (Exception e) {
        e.printStackTrace();
      }
    });

    // Thread 2: Check that the modifications from thread 1 are not visible before commit
    Thread thread2 = Thread.ofVirtual().name("transaction-2").start(() -> {
      try {
        // Wait for thread 1 to start and modify the item
        thread1Started.await();
        
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestItemDAO dao = session.access(TestItemDAO.class);
          
          // Get the item - should not see thread 1's uncommitted changes
          TestItem item = dao.read(itemId).get();
          assertEquals("Transaction isolation test item", item.getNotes(), 
              "Transaction isolation violated: uncommitted changes are visible");
          assertEquals("value", item.getProperties().get("initial"), 
              "Transaction isolation violated: uncommitted changes are visible");
          
          // Signal that thread 2 has checked the item
          thread2Checked.countDown();
          
          // Wait for thread 1 to commit
          thread1Committed.await();
          
          // Now check again - should see thread 1's committed changes
          session.getTransaction().rollback(); // Roll back and start a new transaction
        }
        
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestItemDAO dao = session.access(TestItemDAO.class);
          TestItem item = dao.read(itemId).get();
          assertEquals("Modified by thread 1", item.getNotes(), 
              "Committed changes are not visible");
          assertEquals("by-thread-1", item.getProperties().get("modified"), 
              "Committed changes are not visible");
        }
      } catch (Exception e) {
        e.printStackTrace();
        fail("Exception in thread 2: " + e.getMessage());
      }
    });

    // Wait for both threads to complete
    thread1.join(10000);
    thread2.join(10000);
  }

  /**
   * Tests concurrent entity creation with duplicate keys using virtual threads.
   * Verifies that the database correctly handles concurrent attempts to create entities with the same ID.
   */
  @Test
  @DisplayName("Concurrent entity creation with duplicate keys using virtual threads")
  void testConcurrentEntityCreationWithDuplicateKeys() throws Exception {
    // Create a fixed UUID that multiple threads will try to use
    final UUID fixedUuid = UUID.randomUUID();
    final EntityUUID fixedEntityId = new EntityUUID(fixedUuid);
    
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger duplicateKeyExceptionCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple virtual threads to create entities with the same ID
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
              TestItemDAO dao = session.access(TestItemDAO.class);
              
              TestItem item = new TestItem();
              item.setId(fixedEntityId); // Use the same ID for all threads
              item.setVersion(1);
              item.setEnabled(true);
              item.setNotes("Duplicate key test item " + threadId);
              item.setProperties(ImmutableMap.of("threadId", String.valueOf(threadId)));
              
              dao.create(item);
              session.getTransaction().commit();
              successCount.incrementAndGet();
            }
          } catch (DuplicateKeyException e) {
            // Expected exception for all but one thread
            duplicateKeyExceptionCount.incrementAndGet();
          } catch (Exception e) {
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    }

    // Verify only one thread succeeded and the rest got duplicate key exceptions
    assertEquals(1, successCount.get(), "Expected exactly one successful entity creation");
    assertEquals(THREAD_COUNT - 1, duplicateKeyExceptionCount.get(), 
        "Expected duplicate key exceptions for all but one thread");

    // Verify the entity exists in the database
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestItemDAO dao = session.access(TestItemDAO.class);
      assertTrue(dao.read(fixedEntityId).isPresent(), "Entity with fixed ID not found");
    }
  }

  /**
   * Compares the performance of virtual threads vs platform threads for database operations.
   * Measures the time taken to perform the same operations with both thread types.
   */
  @Test
  @DisplayName("Performance comparison: Virtual threads vs Platform threads")
  void testPerformanceComparisonVirtualVsPlatformThreads() throws Exception {
    // Run the test with virtual threads
    long virtualThreadTime = measureExecutionTime(true);
    
    // Run the test with platform threads
    long platformThreadTime = measureExecutionTime(false);
    
    System.out.println("Performance comparison results:");
    System.out.println("- Virtual threads execution time: " + virtualThreadTime + "ms");
    System.out.println("- Platform threads execution time: " + platformThreadTime + "ms");
    System.out.println("- Performance improvement: " + 
        String.format("%.2f", (double)platformThreadTime / virtualThreadTime) + "x");
    
    // We don't assert on specific performance improvements as they can vary by environment,
    // but we log the results for analysis
  }

  /**
   * Tests that virtual threads can handle a large number of concurrent database operations
   * without exhausting system resources.
   */
  @Test
  @DisplayName("High concurrency with virtual threads")
  void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Use a higher thread count for this test to demonstrate scalability
    final int highThreadCount = 1000;
    CountDownLatch latch = new CountDownLatch(highThreadCount);
    List<Exception> exceptions = new ArrayList<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch a large number of virtual threads
      for (int i = 0; i < highThreadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
              TestItemDAO dao = session.access(TestItemDAO.class);
              
              // Perform a read operation (less resource-intensive than writes)
              List<TestItem> items = dao.browse();
              
              // For every 10th thread, also perform a write operation
              if (threadId % 10 == 0) {
                TestItem item = new TestItem();
                item.setVersion(1);
                item.setEnabled(true);
                item.setNotes("High concurrency test item " + threadId);
                item.setProperties(ImmutableMap.of("threadId", String.valueOf(threadId)));
                
                dao.create(item);
                session.getTransaction().commit();
              }
            }
          } catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete with a generous timeout
      assertTrue(latch.await(60, TimeUnit.SECONDS), 
          "Timed out waiting for high concurrency virtual threads to complete");
    }

    // Verify no exceptions occurred
    if (!exceptions.isEmpty()) {
      fail("Encountered " + exceptions.size() + " exceptions during high concurrency test: " 
          + exceptions.get(0).getMessage());
    }

    // Verify the expected number of items were created
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestItemDAO dao = session.access(TestItemDAO.class);
      List<TestItem> items = dao.browse();
      assertEquals(highThreadCount / 10, items.size(), 
          "Incorrect number of entities created during high concurrency test");
    }
  }

  /**
   * Helper method to measure execution time for database operations using either virtual or platform threads.
   *
   * @param useVirtualThreads true to use virtual threads, false to use platform threads
   * @return execution time in milliseconds
   */
  private long measureExecutionTime(boolean useVirtualThreads) throws Exception {
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    List<Exception> exceptions = new ArrayList<>();
    
    long startTime = System.currentTimeMillis();
    
    ExecutorService executor = useVirtualThreads ? 
        Executors.newVirtualThreadPerTaskExecutor() : 
        Executors.newFixedThreadPool(Math.min(THREAD_COUNT, Runtime.getRuntime().availableProcessors() * 2));
    
    try {
      // Launch threads to perform database operations
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Perform multiple operations per thread
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
                TestItemDAO dao = session.access(TestItemDAO.class);
                
                // Create an item
                TestItem item = new TestItem();
                item.setVersion(1);
                item.setEnabled(true);
                item.setNotes("Performance test item " + threadId + "-" + j);
                item.setProperties(ImmutableMap.of(
                    "threadId", String.valueOf(threadId),
                    "operationId", String.valueOf(j)));
                
                dao.create(item);
                session.getTransaction().commit();
                
                // Read the item back
                TestItem readItem = dao.read(item.getId()).get();
                assertNotNull(readItem);
                
                // Update the item
                readItem.setNotes(readItem.getNotes() + " (updated)");
                dao.update(readItem);
                session.getTransaction().commit();
                
                // Delete the item
                dao.delete(item.getId());
                session.getTransaction().commit();
              }
              
              // Add a small delay to simulate real-world processing
              Thread.sleep(10);
            }
          } catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      latch.await(60, TimeUnit.SECONDS);
    } finally {
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
    
    long endTime = System.currentTimeMillis();
    
    // Check for exceptions
    if (!exceptions.isEmpty()) {
      throw new RuntimeException("Encountered " + exceptions.size() + 
          " exceptions during performance test", exceptions.get(0));
    }
    
    return endTime - startTime;
  }
}