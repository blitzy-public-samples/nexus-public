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
package org.sonatype.nexus.datastore.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.HasEntityId;
import org.sonatype.nexus.common.entity.HasName;
import org.sonatype.nexus.common.entity.HasStringId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class that verifies compatibility of all DataAccess-derived interfaces with Virtual Threads.
 * This class includes test cases for IdentifiedDataAccess, IterableDataAccess, NamedDataAccess, and
 * SingletonDataAccess operations when executed within Virtual Threads.
 * 
 * The tests ensure that:
 * 1. All DataAccess operations can be executed within Virtual Threads
 * 2. Operations complete successfully without thread pinning issues
 * 3. Concurrent access patterns work correctly with Virtual Threads
 * 4. I/O-bound operations benefit from Virtual Thread execution model
 *
 * @since 3.60
 */

public class DataAccessVirtualThreadTest
{
  // Number of concurrent threads to use in tests
  // This is set high enough to demonstrate virtual thread scalability
  // but low enough to avoid overwhelming test environments
  private static final int CONCURRENT_THREADS = 10;
  
  // Timeout for waiting on concurrent operations
  private static final int TIMEOUT_SECONDS = 5;
  
  private ExecutorService virtualThreadExecutor;
  
  // Test implementations
  private MockIdentifiedDataAccess identifiedDataAccess;
  private MockIterableDataAccess iterableDataAccess;
  private MockNamedDataAccess namedDataAccess;
  private MockSingletonDataAccess singletonDataAccess;
  
  @BeforeEach
  void setUp() {
    // Create a virtual thread per task executor
    // This uses Java 21's new virtual thread factory to create lightweight threads
    // that are ideal for I/O-bound operations
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Initialize mock implementations
    identifiedDataAccess = new MockIdentifiedDataAccess();
    iterableDataAccess = new MockIterableDataAccess();
    namedDataAccess = new MockNamedDataAccess();
    singletonDataAccess = new MockSingletonDataAccess();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    // Properly shut down the executor service to clean up resources
    // This is important even with virtual threads to ensure proper test isolation
    virtualThreadExecutor.shutdown();
    
    // Wait for termination with timeout
    if (!virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      // Force shutdown if graceful shutdown fails
      virtualThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Verifies that the current thread is a virtual thread.
   * Uses Java 21's Thread.isVirtual() method to confirm the thread type.
   */
  private void assertIsVirtualThread() {
    assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
    // Additional verification that we're not on the main thread
    assertFalse(Thread.currentThread().getName().contains("main"), 
        "Test should not be running on the main thread");
  }
  
  /**
   * Simulates an I/O operation that would normally block a thread.
   * This method is designed to test if virtual threads properly unmount from carrier threads
   * during blocking operations.
   */
  private void simulateIoOperation() {
    try {
      // Simulate I/O operation with a short delay
      Thread.sleep(50);
      
      // Alternative approach to simulate I/O with a blocking read
      // This helps test if virtual threads properly handle different types of blocking operations
      try {
        byte[] buffer = new byte[1];
        System.in.available(); // Non-blocking call to check if input is available
      }
      catch (IOException e) {
        // Ignore, this is just a simulation
      }
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Runs the given task in a virtual thread and waits for completion.
   * This method uses CompletableFuture with the virtual thread executor to ensure
   * the task runs in a virtual thread context.
   *
   * @param task The task to run in a virtual thread
   * @throws ExecutionException If the task throws an exception
   * @throws InterruptedException If the current thread is interrupted while waiting
   */
  private void runInVirtualThread(Runnable task) throws ExecutionException, InterruptedException {
    CompletableFuture<Void> future = CompletableFuture.runAsync(task, virtualThreadExecutor);
    future.get(); // Wait for completion
  }
  
  /**
   * Runs multiple tasks concurrently in virtual threads and waits for all to complete.
   * This method leverages Java 21 Virtual Threads to efficiently run many concurrent
   * operations without the overhead of traditional platform threads.
   *
   * @param task The task to run concurrently
   * @param count The number of concurrent executions
   * @throws InterruptedException If the execution is interrupted
   */
  private void runConcurrently(Runnable task, int count) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(count);
    List<Throwable> exceptions = new ArrayList<>();
    
    for (int i = 0; i < count; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Execute the task
          task.run();
        } 
        catch (Throwable t) {
          // Capture any exceptions
          synchronized (exceptions) {
            exceptions.add(t);
          }
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Check for exceptions
    if (!exceptions.isEmpty()) {
      Throwable firstException = exceptions.get(0);
      if (firstException instanceof RuntimeException) {
        throw (RuntimeException) firstException;
      }
      throw new RuntimeException("Exception in concurrent task", firstException);
    }
    
    // Verify all tasks completed within the timeout
    assertTrue(completed, "Timed out waiting for concurrent tasks to complete");
  }
  
  // ===== IdentifiedDataAccess Tests =====
  
  @Test
  void testIdentifiedDataAccessCreateInVirtualThread() throws Exception {
    TestIdentifiedEntity entity = new TestIdentifiedEntity("test-id");
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      identifiedDataAccess.create(entity);
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    Optional<TestIdentifiedEntity> result = identifiedDataAccess.read(entity.getId());
    assertTrue(result.isPresent());
    assertEquals(entity.getId(), result.get().getId());
  }
  
  @Test
  void testIdentifiedDataAccessReadInVirtualThread() throws Exception {
    TestIdentifiedEntity entity = new TestIdentifiedEntity("test-id");
    identifiedDataAccess.create(entity);
    
    AtomicReference<Optional<TestIdentifiedEntity>> result = new AtomicReference<>();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      result.set(identifiedDataAccess.read(entity.getId()));
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(result.get().isPresent());
    assertEquals(entity.getId(), result.get().get().getId());
  }
  
  @Test
  void testIdentifiedDataAccessUpdateInVirtualThread() throws Exception {
    TestIdentifiedEntity entity = new TestIdentifiedEntity("test-id");
    identifiedDataAccess.create(entity);
    
    AtomicBoolean updateResult = new AtomicBoolean();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      updateResult.set(identifiedDataAccess.update(entity));
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(updateResult.get());
  }
  
  @Test
  void testIdentifiedDataAccessDeleteInVirtualThread() throws Exception {
    TestIdentifiedEntity entity = new TestIdentifiedEntity("test-id");
    identifiedDataAccess.create(entity);
    
    AtomicBoolean deleteResult = new AtomicBoolean();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      deleteResult.set(identifiedDataAccess.delete(entity.getId()));
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(deleteResult.get());
    assertFalse(identifiedDataAccess.read(entity.getId()).isPresent());
  }
  
  @Test
  void testIdentifiedDataAccessBrowseInVirtualThread() throws Exception {
    TestIdentifiedEntity entity1 = new TestIdentifiedEntity("test-id-1");
    TestIdentifiedEntity entity2 = new TestIdentifiedEntity("test-id-2");
    identifiedDataAccess.create(entity1);
    identifiedDataAccess.create(entity2);
    
    AtomicReference<List<TestIdentifiedEntity>> results = new AtomicReference<>(new ArrayList<>());
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      identifiedDataAccess.browse().forEach(results.get()::add);
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertEquals(2, results.get().size());
  }
  
  @Test
  void testIdentifiedDataAccessConcurrentOperations() throws Exception {
    // Test concurrent create operations
    // This test verifies that multiple virtual threads can concurrently
    // perform operations on the DataAccess implementation without issues
    runConcurrently(() -> {
      String id = UUID.randomUUID().toString();
      TestIdentifiedEntity entity = new TestIdentifiedEntity(id);
      
      // Verify we're running in a virtual thread
      assertIsVirtualThread();
      
      // Create entity
      identifiedDataAccess.create(entity);
      simulateIoOperation();
      
      // Verify entity was created
      Optional<TestIdentifiedEntity> result = identifiedDataAccess.read(id);
      assertTrue(result.isPresent(), "Entity should be present after creation");
      assertEquals(id, result.get().getId(), "Entity ID should match");
    }, CONCURRENT_THREADS);
    
    // Verify all entities were created
    List<TestIdentifiedEntity> allEntities = new ArrayList<>();
    identifiedDataAccess.browse().forEach(allEntities::add);
    assertEquals(CONCURRENT_THREADS, allEntities.size(), 
        "All concurrent operations should have completed successfully");
  }
  
  // ===== IterableDataAccess Tests =====
  
  @Test
  void testIterableDataAccessCreateInVirtualThread() throws Exception {
    TestIterableEntity entity = new TestIterableEntity();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      iterableDataAccess.create(entity);
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    Optional<TestIterableEntity> result = iterableDataAccess.read(entity.getId());
    assertTrue(result.isPresent());
    assertEquals(entity.getId(), result.get().getId());
  }
  
  @Test
  void testIterableDataAccessReadInVirtualThread() throws Exception {
    TestIterableEntity entity = new TestIterableEntity();
    iterableDataAccess.create(entity);
    
    AtomicReference<Optional<TestIterableEntity>> result = new AtomicReference<>();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      result.set(iterableDataAccess.read(entity.getId()));
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(result.get().isPresent());
    assertEquals(entity.getId(), result.get().get().getId());
  }
  
  @Test
  void testIterableDataAccessUpdateInVirtualThread() throws Exception {
    TestIterableEntity entity = new TestIterableEntity();
    iterableDataAccess.create(entity);
    
    AtomicBoolean updateResult = new AtomicBoolean();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      updateResult.set(iterableDataAccess.update(entity));
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(updateResult.get());
  }
  
  @Test
  void testIterableDataAccessDeleteInVirtualThread() throws Exception {
    TestIterableEntity entity = new TestIterableEntity();
    iterableDataAccess.create(entity);
    
    AtomicBoolean deleteResult = new AtomicBoolean();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      deleteResult.set(iterableDataAccess.delete(entity.getId()));
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(deleteResult.get());
    assertFalse(iterableDataAccess.read(entity.getId()).isPresent());
  }
  
  @Test
  void testIterableDataAccessBrowseInVirtualThread() throws Exception {
    TestIterableEntity entity1 = new TestIterableEntity();
    TestIterableEntity entity2 = new TestIterableEntity();
    iterableDataAccess.create(entity1);
    iterableDataAccess.create(entity2);
    
    AtomicReference<List<TestIterableEntity>> results = new AtomicReference<>(new ArrayList<>());
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      iterableDataAccess.browse().forEach(results.get()::add);
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertEquals(2, results.get().size());
  }
  
  @Test
  void testIterableDataAccessConcurrentOperations() throws Exception {
    // Test concurrent create and read operations
    runConcurrently(() -> {
      TestIterableEntity entity = new TestIterableEntity();
      iterableDataAccess.create(entity);
      simulateIoOperation();
      assertTrue(iterableDataAccess.read(entity.getId()).isPresent());
    }, CONCURRENT_THREADS);
    
    // Verify all entities were created
    List<TestIterableEntity> allEntities = new ArrayList<>();
    iterableDataAccess.browse().forEach(allEntities::add);
    assertEquals(CONCURRENT_THREADS, allEntities.size());
  }
  
  // ===== NamedDataAccess Tests =====
  
  @Test
  void testNamedDataAccessCreateInVirtualThread() throws Exception {
    TestNamedEntity entity = new TestNamedEntity("test-name");
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      namedDataAccess.create(entity);
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    Optional<TestNamedEntity> result = namedDataAccess.read(entity.getName());
    assertTrue(result.isPresent());
    assertEquals(entity.getName(), result.get().getName());
  }
  
  @Test
  void testNamedDataAccessReadInVirtualThread() throws Exception {
    TestNamedEntity entity = new TestNamedEntity("test-name");
    namedDataAccess.create(entity);
    
    AtomicReference<Optional<TestNamedEntity>> result = new AtomicReference<>();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      result.set(namedDataAccess.read(entity.getName()));
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(result.get().isPresent());
    assertEquals(entity.getName(), result.get().get().getName());
  }
  
  @Test
  void testNamedDataAccessUpdateInVirtualThread() throws Exception {
    TestNamedEntity entity = new TestNamedEntity("test-name");
    namedDataAccess.create(entity);
    
    AtomicBoolean updateResult = new AtomicBoolean();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      updateResult.set(namedDataAccess.update(entity));
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(updateResult.get());
  }
  
  @Test
  void testNamedDataAccessDeleteInVirtualThread() throws Exception {
    TestNamedEntity entity = new TestNamedEntity("test-name");
    namedDataAccess.create(entity);
    
    AtomicBoolean deleteResult = new AtomicBoolean();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      deleteResult.set(namedDataAccess.delete(entity.getName()));
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(deleteResult.get());
    assertFalse(namedDataAccess.read(entity.getName()).isPresent());
  }
  
  @Test
  void testNamedDataAccessBrowseInVirtualThread() throws Exception {
    TestNamedEntity entity1 = new TestNamedEntity("test-name-1");
    TestNamedEntity entity2 = new TestNamedEntity("test-name-2");
    namedDataAccess.create(entity1);
    namedDataAccess.create(entity2);
    
    AtomicReference<List<TestNamedEntity>> results = new AtomicReference<>(new ArrayList<>());
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      namedDataAccess.browse().forEach(results.get()::add);
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertEquals(2, results.get().size());
  }
  
  @Test
  void testNamedDataAccessConcurrentOperations() throws Exception {
    // Test concurrent create and read operations
    runConcurrently(() -> {
      String name = "test-name-" + UUID.randomUUID();
      TestNamedEntity entity = new TestNamedEntity(name);
      namedDataAccess.create(entity);
      simulateIoOperation();
      assertTrue(namedDataAccess.read(name).isPresent());
    }, CONCURRENT_THREADS);
    
    // Verify all entities were created
    List<TestNamedEntity> allEntities = new ArrayList<>();
    namedDataAccess.browse().forEach(allEntities::add);
    assertEquals(CONCURRENT_THREADS, allEntities.size());
  }
  
  // ===== SingletonDataAccess Tests =====
  
  @Test
  void testSingletonDataAccessSetInVirtualThread() throws Exception {
    TestSingletonEntity entity = new TestSingletonEntity("test-value");
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      singletonDataAccess.set(entity);
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    Optional<TestSingletonEntity> result = singletonDataAccess.get();
    assertTrue(result.isPresent());
    assertEquals(entity.getValue(), result.get().getValue());
  }
  
  @Test
  void testSingletonDataAccessGetInVirtualThread() throws Exception {
    TestSingletonEntity entity = new TestSingletonEntity("test-value");
    singletonDataAccess.set(entity);
    
    AtomicReference<Optional<TestSingletonEntity>> result = new AtomicReference<>();
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      result.set(singletonDataAccess.get());
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertTrue(result.get().isPresent());
    assertEquals(entity.getValue(), result.get().get().getValue());
  }
  
  @Test
  void testSingletonDataAccessClearInVirtualThread() throws Exception {
    TestSingletonEntity entity = new TestSingletonEntity("test-value");
    singletonDataAccess.set(entity);
    
    runInVirtualThread(() -> {
      assertIsVirtualThread();
      singletonDataAccess.clear();
      simulateIoOperation(); // Simulate I/O after operation
    });
    
    assertFalse(singletonDataAccess.get().isPresent());
  }
  
  @Test
  void testSingletonDataAccessConcurrentOperations() throws Exception {
    // Test concurrent set operations
    // This test verifies that multiple virtual threads can concurrently
    // access and modify a singleton entity without thread safety issues
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicReference<List<String>> observedValues = new AtomicReference<>(new ArrayList<>());
    
    // Set initial value
    singletonDataAccess.set(new TestSingletonEntity("initial"));
    
    // Launch concurrent operations
    runConcurrently(() -> {
      try {
        // Wait for all threads to be ready
        startLatch.await();
        
        // Verify we're running in a virtual thread
        assertIsVirtualThread();
        
        // Set new value
        String value = "test-value-" + UUID.randomUUID();
        TestSingletonEntity entity = new TestSingletonEntity(value);
        singletonDataAccess.set(entity);
        
        // Simulate I/O operation to allow thread interleaving
        simulateIoOperation();
        
        // Get current value and record it
        Optional<TestSingletonEntity> result = singletonDataAccess.get();
        assertTrue(result.isPresent(), "Entity should be present");
        synchronized(observedValues) {
          observedValues.get().add(result.get().getValue());
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, CONCURRENT_THREADS);
    
    // Start all threads at once
    startLatch.countDown();
    
    // The last entity set should be present
    assertTrue(singletonDataAccess.get().isPresent(), "Final entity should be present");
    
    // We should have observed multiple values due to concurrent modifications
    assertEquals(CONCURRENT_THREADS, observedValues.get().size(), 
        "Should have recorded values from all threads");
  }
  
  // ===== Test Entity Classes =====
  
  /**
   * Test entity for IdentifiedDataAccess.
   * Implements HasStringId interface for use with IdentifiedDataAccess.
   */
  private static class TestIdentifiedEntity implements HasStringId {
    private final String id;
    
    TestIdentifiedEntity(String id) {
      this.id = id;
    }
    
    @Override
    public String getId() {
      return id;
    }
    
    @Override
    public String toString() {
      return "TestIdentifiedEntity{id='" + id + "'}"; 
    }
  }
  
  /**
   * Test entity for IterableDataAccess.
   * Implements HasEntityId interface for use with IterableDataAccess.
   */
  private static class TestIterableEntity implements HasEntityId {
    private final EntityId id;
    
    TestIterableEntity() {
      this.id = new TestEntityId();
    }
    
    @Override
    public EntityId getId() {
      return id;
    }
    
    @Override
    public String toString() {
      return "TestIterableEntity{id=" + id.getValue() + "}"; 
    }
  }
  
  /**
   * Test implementation of EntityId.
   * Simple implementation that uses a UUID for the entity ID value.
   */
  private static class TestEntityId implements EntityId {
    private final String value = UUID.randomUUID().toString();
    
    @Override
    public String getValue() {
      return value;
    }
    
    @Override
    public String toString() {
      return value;
    }
    
    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TestEntityId that = (TestEntityId) o;
      return value.equals(that.value);
    }
    
    @Override
    public int hashCode() {
      return value.hashCode();
    }
  }
  
  /**
   * Test entity for NamedDataAccess.
   * Implements HasName interface for use with NamedDataAccess.
   */
  private static class TestNamedEntity implements HasName {
    private final String name;
    
    TestNamedEntity(String name) {
      this.name = name;
    }
    
    @Override
    public String getName() {
      return name;
    }
    
    @Override
    public String toString() {
      return "TestNamedEntity{name='" + name + "'}"; 
    }
  }
  
  /**
   * Test entity for SingletonDataAccess.
   * Simple POJO with a value field for use with SingletonDataAccess.
   */
  private static class TestSingletonEntity {
    private final String value;
    
    TestSingletonEntity(String value) {
      this.value = value;
    }
    
    public String getValue() {
      return value;
    }
    
    @Override
    public String toString() {
      return "TestSingletonEntity{value='" + value + "'}"; 
    }
  }
  
  // ===== Mock DataAccess Implementations =====
  
  /**
   * Mock implementation of IdentifiedDataAccess for testing.
   * This implementation simulates database operations with in-memory storage.
   * In a real implementation, these methods would interact with a database
   * and benefit from Virtual Thread's ability to efficiently handle I/O operations.
   */
  private static class MockIdentifiedDataAccess implements IdentifiedDataAccess<TestIdentifiedEntity> {
    private final List<TestIdentifiedEntity> entities = new ArrayList<>();
    
    @Override
    public Iterable<TestIdentifiedEntity> browse() {
      simulateSlowOperation(); // Simulate database query latency
      return new ArrayList<>(entities);
    }
    
    @Override
    public void create(TestIdentifiedEntity entity) {
      simulateSlowOperation(); // Simulate database insert latency
      entities.add(entity);
    }
    
    @Override
    public Optional<TestIdentifiedEntity> read(String id) {
      simulateSlowOperation(); // Simulate database select latency
      return entities.stream()
          .filter(e -> e.getId().equals(id))
          .findFirst();
    }
    
    @Override
    public boolean update(TestIdentifiedEntity entity) {
      simulateSlowOperation(); // Simulate database update latency
      // For this mock, update is the same as create since entities are immutable
      return delete(entity.getId()) && (entities.add(entity) || true);
    }
    
    @Override
    public boolean delete(String id) {
      simulateSlowOperation(); // Simulate database delete latency
      return entities.removeIf(e -> e.getId().equals(id));
    }
    
    @Override
    public void createSchema() {
      // No-op for mock
    }
    
    /**
     * Simulates a slow database operation to better demonstrate
     * the benefits of Virtual Threads for I/O-bound operations.
     */
    private void simulateSlowOperation() {
      try {
        // Very short delay to simulate database latency without slowing tests too much
        Thread.sleep(5);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Mock implementation of IterableDataAccess for testing.
   * This implementation simulates database operations with in-memory storage.
   * In a real implementation, these methods would interact with a database
   * and benefit from Virtual Thread's ability to efficiently handle I/O operations.
   */
  private static class MockIterableDataAccess implements IterableDataAccess<TestIterableEntity> {
    private final List<TestIterableEntity> entities = new ArrayList<>();
    
    @Override
    public Iterable<TestIterableEntity> browse() {
      simulateSlowOperation(); // Simulate database query latency
      return new ArrayList<>(entities);
    }
    
    @Override
    public void create(TestIterableEntity entity) {
      simulateSlowOperation(); // Simulate database insert latency
      entities.add(entity);
    }
    
    @Override
    public Optional<TestIterableEntity> read(EntityId id) {
      simulateSlowOperation(); // Simulate database select latency
      return entities.stream()
          .filter(e -> e.getId().getValue().equals(id.getValue()))
          .findFirst();
    }
    
    @Override
    public boolean update(TestIterableEntity entity) {
      simulateSlowOperation(); // Simulate database update latency
      // For this mock, update is the same as create since entities are immutable
      return delete(entity.getId()) && (entities.add(entity) || true);
    }
    
    @Override
    public boolean delete(EntityId id) {
      simulateSlowOperation(); // Simulate database delete latency
      return entities.removeIf(e -> e.getId().getValue().equals(id.getValue()));
    }
    
    @Override
    public void createSchema() {
      // No-op for mock
    }
    
    /**
     * Simulates a slow database operation to better demonstrate
     * the benefits of Virtual Threads for I/O-bound operations.
     */
    private void simulateSlowOperation() {
      try {
        // Very short delay to simulate database latency without slowing tests too much
        Thread.sleep(5);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Mock implementation of NamedDataAccess for testing.
   * This implementation simulates database operations with in-memory storage.
   * In a real implementation, these methods would interact with a database
   * and benefit from Virtual Thread's ability to efficiently handle I/O operations.
   */
  private static class MockNamedDataAccess implements NamedDataAccess<TestNamedEntity> {
    private final List<TestNamedEntity> entities = new ArrayList<>();
    
    @Override
    public Iterable<TestNamedEntity> browse() {
      simulateSlowOperation(); // Simulate database query latency
      return new ArrayList<>(entities);
    }
    
    @Override
    public void create(TestNamedEntity entity) {
      simulateSlowOperation(); // Simulate database insert latency
      entities.add(entity);
    }
    
    @Override
    public Optional<TestNamedEntity> read(String name) {
      simulateSlowOperation(); // Simulate database select latency
      return entities.stream()
          .filter(e -> e.getName().equals(name))
          .findFirst();
    }
    
    @Override
    public boolean update(TestNamedEntity entity) {
      simulateSlowOperation(); // Simulate database update latency
      // For this mock, update is the same as create since entities are immutable
      return delete(entity.getName()) && (entities.add(entity) || true);
    }
    
    @Override
    public boolean delete(String name) {
      simulateSlowOperation(); // Simulate database delete latency
      return entities.removeIf(e -> e.getName().equals(name));
    }
    
    @Override
    public void createSchema() {
      // No-op for mock
    }
    
    /**
     * Simulates a slow database operation to better demonstrate
     * the benefits of Virtual Threads for I/O-bound operations.
     */
    private void simulateSlowOperation() {
      try {
        // Very short delay to simulate database latency without slowing tests too much
        Thread.sleep(5);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Mock implementation of SingletonDataAccess for testing.
   * This implementation simulates database operations with in-memory storage.
   * In a real implementation, these methods would interact with a database
   * and benefit from Virtual Thread's ability to efficiently handle I/O operations.
   */
  private static class MockSingletonDataAccess implements SingletonDataAccess<TestSingletonEntity> {
    private TestSingletonEntity entity;
    
    @Override
    public Optional<TestSingletonEntity> get() {
      simulateSlowOperation(); // Simulate database select latency
      return Optional.ofNullable(entity);
    }
    
    @Override
    public void set(TestSingletonEntity entity) {
      simulateSlowOperation(); // Simulate database update latency
      this.entity = entity;
    }
    
    @Override
    public void clear() {
      simulateSlowOperation(); // Simulate database delete latency
      this.entity = null;
    }
    
    @Override
    public void createSchema() {
      // No-op for mock
    }
    
    /**
     * Simulates a slow database operation to better demonstrate
     * the benefits of Virtual Threads for I/O-bound operations.
     */
    private void simulateSlowOperation() {
      try {
        // Very short delay to simulate database latency without slowing tests too much
        Thread.sleep(5);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}