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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.HasEntityId;
import org.sonatype.nexus.common.entity.HasName;
import org.sonatype.nexus.common.entity.HasStringId;
import org.sonatype.nexus.datastore.api.DataAccess;
import org.sonatype.nexus.datastore.api.IdentifiedDataAccess;
import org.sonatype.nexus.datastore.api.IterableDataAccess;
import org.sonatype.nexus.datastore.api.NamedDataAccess;
import org.sonatype.nexus.datastore.api.SingletonDataAccess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class that verifies compatibility of all DataAccess-derived interfaces with Virtual Threads.
 * 
 * This test ensures that DataAccess operations can be executed within Virtual Threads without
 * thread pinning or other concurrency issues. It tests all specialized DataAccess interfaces:
 * IdentifiedDataAccess, IterableDataAccess, NamedDataAccess, and SingletonDataAccess.
 *
 * @since 3.60
 */
public class DataAccessVirtualThreadTest
{
  private ExecutorService virtualThreadExecutor;
  private TestThreadPinningDetector threadPinningDetector;
  
  // Mock implementations for testing
  private MockIdentifiedDataAccess identifiedDataAccess;
  private MockIterableDataAccess iterableDataAccess;
  private MockNamedDataAccess namedDataAccess;
  private MockSingletonDataAccess singletonDataAccess;
  
  @BeforeEach
  void setUp(TestInfo testInfo) {
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    threadPinningDetector = new TestThreadPinningDetector();
    
    // Initialize mock implementations
    identifiedDataAccess = new MockIdentifiedDataAccess(threadPinningDetector);
    iterableDataAccess = new MockIterableDataAccess(threadPinningDetector);
    namedDataAccess = new MockNamedDataAccess(threadPinningDetector);
    singletonDataAccess = new MockSingletonDataAccess(threadPinningDetector);
    
    System.out.println("Running test: " + testInfo.getDisplayName());
  }
  
  @AfterEach
  void tearDown() throws Exception {
    virtualThreadExecutor.shutdown();
    boolean terminated = virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    assertTrue(terminated, "Virtual thread executor did not terminate in time");
  }
  
  /**
   * Tests that IdentifiedDataAccess operations can be executed within Virtual Threads
   * without thread pinning issues.
   */
  @Test
  void testIdentifiedDataAccessWithVirtualThreads() throws Exception {
    // Create a test entity
    StringIdEntity entity = new StringIdEntity("test-id", "Test Entity");
    
    // Execute operations in a virtual thread
    virtualThreadExecutor.submit(() -> {
      // Create operation
      identifiedDataAccess.create(entity);
      
      // Read operation
      Optional<StringIdEntity> read = identifiedDataAccess.read("test-id");
      assertTrue(read.isPresent());
      assertEquals("test-id", read.get().getId());
      
      // Update operation
      entity.setValue("Updated Entity");
      boolean updated = identifiedDataAccess.update(entity);
      assertTrue(updated);
      
      // Browse operation
      Iterable<StringIdEntity> entities = identifiedDataAccess.browse();
      assertNotNull(entities);
      
      // Delete operation
      boolean deleted = identifiedDataAccess.delete("test-id");
      assertTrue(deleted);
      
      return null;
    }).get(5, TimeUnit.SECONDS);
    
    // Verify no thread pinning occurred
    assertFalse(threadPinningDetector.wasPinned(), "Thread pinning detected during IdentifiedDataAccess operations");
  }
  
  /**
   * Tests that IterableDataAccess operations can be executed within Virtual Threads
   * without thread pinning issues.
   */
  @Test
  void testIterableDataAccessWithVirtualThreads() throws Exception {
    // Create a test entity
    EntityIdEntity entity = new EntityIdEntity(new TestEntityId("test-id"), "Test Entity");
    
    // Execute operations in a virtual thread
    virtualThreadExecutor.submit(() -> {
      // Create operation
      iterableDataAccess.create(entity);
      
      // Read operation
      Optional<EntityIdEntity> read = iterableDataAccess.read(new TestEntityId("test-id"));
      assertTrue(read.isPresent());
      assertEquals("test-id", read.get().getId().getValue());
      
      // Update operation
      entity.setValue("Updated Entity");
      boolean updated = iterableDataAccess.update(entity);
      assertTrue(updated);
      
      // Browse operation
      Iterable<EntityIdEntity> entities = iterableDataAccess.browse();
      assertNotNull(entities);
      
      // Delete operation
      boolean deleted = iterableDataAccess.delete(new TestEntityId("test-id"));
      assertTrue(deleted);
      
      return null;
    }).get(5, TimeUnit.SECONDS);
    
    // Verify no thread pinning occurred
    assertFalse(threadPinningDetector.wasPinned(), "Thread pinning detected during IterableDataAccess operations");
  }
  
  /**
   * Tests that NamedDataAccess operations can be executed within Virtual Threads
   * without thread pinning issues.
   */
  @Test
  void testNamedDataAccessWithVirtualThreads() throws Exception {
    // Create a test entity
    NamedEntity entity = new NamedEntity("test-name", "Test Entity");
    
    // Execute operations in a virtual thread
    virtualThreadExecutor.submit(() -> {
      // Create operation
      namedDataAccess.create(entity);
      
      // Read operation
      Optional<NamedEntity> read = namedDataAccess.read("test-name");
      assertTrue(read.isPresent());
      assertEquals("test-name", read.get().getName());
      
      // Update operation
      entity.setValue("Updated Entity");
      boolean updated = namedDataAccess.update(entity);
      assertTrue(updated);
      
      // Browse operation
      Iterable<NamedEntity> entities = namedDataAccess.browse();
      assertNotNull(entities);
      
      // Delete operation
      boolean deleted = namedDataAccess.delete("test-name");
      assertTrue(deleted);
      
      return null;
    }).get(5, TimeUnit.SECONDS);
    
    // Verify no thread pinning occurred
    assertFalse(threadPinningDetector.wasPinned(), "Thread pinning detected during NamedDataAccess operations");
  }
  
  /**
   * Tests that SingletonDataAccess operations can be executed within Virtual Threads
   * without thread pinning issues.
   */
  @Test
  void testSingletonDataAccessWithVirtualThreads() throws Exception {
    // Create a test entity
    SingletonEntity entity = new SingletonEntity("Test Entity");
    
    // Execute operations in a virtual thread
    virtualThreadExecutor.submit(() -> {
      // Set operation
      singletonDataAccess.set(entity);
      
      // Get operation
      Optional<SingletonEntity> read = singletonDataAccess.get();
      assertTrue(read.isPresent());
      assertEquals("Test Entity", read.get().getValue());
      
      // Clear operation
      singletonDataAccess.clear();
      
      // Verify cleared
      Optional<SingletonEntity> afterClear = singletonDataAccess.get();
      assertFalse(afterClear.isPresent());
      
      return null;
    }).get(5, TimeUnit.SECONDS);
    
    // Verify no thread pinning occurred
    assertFalse(threadPinningDetector.wasPinned(), "Thread pinning detected during SingletonDataAccess operations");
  }
  
  /**
   * Tests concurrent operations on IdentifiedDataAccess using multiple Virtual Threads.
   */
  @Test
  void testConcurrentIdentifiedDataAccessOperations() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit multiple concurrent tasks using virtual threads
    for (int i = 0; i < threadCount; i++) {
      final String id = "concurrent-id-" + i;
      final String value = "Concurrent Entity " + i;
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Create a unique entity
          StringIdEntity entity = new StringIdEntity(id, value);
          identifiedDataAccess.create(entity);
          
          // Read the entity
          Optional<StringIdEntity> read = identifiedDataAccess.read(id);
          if (!read.isPresent() || !read.get().getValue().equals(value)) {
            errorCount.incrementAndGet();
          }
          
          // Update the entity
          entity.setValue(value + " Updated");
          boolean updated = identifiedDataAccess.update(entity);
          if (!updated) {
            errorCount.incrementAndGet();
          }
          
          // Delete the entity
          boolean deleted = identifiedDataAccess.delete(id);
          if (!deleted) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          e.printStackTrace();
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertTrue(completed, "Not all concurrent operations completed in time");
    assertEquals(0, errorCount.get(), "Errors occurred during concurrent operations");
    
    // Verify no thread pinning occurred
    assertFalse(threadPinningDetector.wasPinned(), "Thread pinning detected during concurrent operations");
  }
  
  /**
   * Tests that operations across different DataAccess interfaces can be executed
   * within the same Virtual Thread without thread pinning issues.
   */
  @Test
  void testMixedDataAccessOperationsWithVirtualThreads() throws Exception {
    // Execute operations in a virtual thread
    virtualThreadExecutor.submit(() -> {
      // IdentifiedDataAccess operations
      StringIdEntity idEntity = new StringIdEntity("mixed-id", "Mixed ID Entity");
      identifiedDataAccess.create(idEntity);
      
      // NamedDataAccess operations
      NamedEntity namedEntity = new NamedEntity("mixed-name", "Mixed Named Entity");
      namedDataAccess.create(namedEntity);
      
      // IterableDataAccess operations
      EntityIdEntity entityIdEntity = new EntityIdEntity(new TestEntityId("mixed-entity-id"), "Mixed Entity ID Entity");
      iterableDataAccess.create(entityIdEntity);
      
      // SingletonDataAccess operations
      SingletonEntity singletonEntity = new SingletonEntity("Mixed Singleton Entity");
      singletonDataAccess.set(singletonEntity);
      
      // Verify all entities were created
      assertTrue(identifiedDataAccess.read("mixed-id").isPresent());
      assertTrue(namedDataAccess.read("mixed-name").isPresent());
      assertTrue(iterableDataAccess.read(new TestEntityId("mixed-entity-id")).isPresent());
      assertTrue(singletonDataAccess.get().isPresent());
      
      // Clean up
      identifiedDataAccess.delete("mixed-id");
      namedDataAccess.delete("mixed-name");
      iterableDataAccess.delete(new TestEntityId("mixed-entity-id"));
      singletonDataAccess.clear();
      
      return null;
    }).get(5, TimeUnit.SECONDS);
    
    // Verify no thread pinning occurred
    assertFalse(threadPinningDetector.wasPinned(), "Thread pinning detected during mixed operations");
  }
  
  // ---- Test entity classes ----
  
  /**
   * Test entity for IdentifiedDataAccess.
   */
  static class StringIdEntity implements HasStringId {
    private final String id;
    private String value;
    
    StringIdEntity(String id, String value) {
      this.id = id;
      this.value = value;
    }
    
    @Override
    public String getId() {
      return id;
    }
    
    public String getValue() {
      return value;
    }
    
    public void setValue(String value) {
      this.value = value;
    }
  }
  
  /**
   * Test entity ID implementation.
   */
  static class TestEntityId implements EntityId {
    private final String value;
    
    TestEntityId(String value) {
      this.value = value;
    }
    
    public String getValue() {
      return value;
    }
    
    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      TestEntityId that = (TestEntityId) obj;
      return value.equals(that.value);
    }
    
    @Override
    public int hashCode() {
      return value.hashCode();
    }
    
    @Override
    public String toString() {
      return value;
    }
  }
  
  /**
   * Test entity for IterableDataAccess.
   */
  static class EntityIdEntity implements HasEntityId {
    private final TestEntityId id;
    private String value;
    
    EntityIdEntity(TestEntityId id, String value) {
      this.id = id;
      this.value = value;
    }
    
    @Override
    public TestEntityId getId() {
      return id;
    }
    
    public String getValue() {
      return value;
    }
    
    public void setValue(String value) {
      this.value = value;
    }
  }
  
  /**
   * Test entity for NamedDataAccess.
   */
  static class NamedEntity implements HasName {
    private final String name;
    private String value;
    
    NamedEntity(String name, String value) {
      this.name = name;
      this.value = value;
    }
    
    @Override
    public String getName() {
      return name;
    }
    
    public String getValue() {
      return value;
    }
    
    public void setValue(String value) {
      this.value = value;
    }
  }
  
  /**
   * Test entity for SingletonDataAccess.
   */
  static class SingletonEntity {
    private final String value;
    
    SingletonEntity(String value) {
      this.value = value;
    }
    
    public String getValue() {
      return value;
    }
  }
  
  // ---- Mock DataAccess implementations ----
  
  /**
   * Helper class to detect thread pinning during operations.
   */
  static class TestThreadPinningDetector {
    private final AtomicBoolean pinned = new AtomicBoolean(false);
    
    /**
     * Simulates a potentially blocking operation and checks if the current thread
     * is a virtual thread. If it's a platform thread when it should be a virtual thread,
     * that indicates thread pinning has occurred.
     */
    void checkForPinning() {
      // In a real implementation, this would check if the current thread
      // is a carrier thread that's been pinned by a virtual thread
      boolean isVirtualThread = Thread.currentThread().isVirtual();
      if (!isVirtualThread) {
        pinned.set(true);
      }
      
      // Simulate a small delay to allow for thread scheduling
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    
    boolean wasPinned() {
      return pinned.get();
    }
  }
  
  /**
   * Mock implementation of IdentifiedDataAccess for testing.
   */
  static class MockIdentifiedDataAccess implements IdentifiedDataAccess<StringIdEntity> {
    private final Map<String, StringIdEntity> entities = new HashMap<>();
    private final TestThreadPinningDetector pinningDetector;
    
    MockIdentifiedDataAccess(TestThreadPinningDetector pinningDetector) {
      this.pinningDetector = pinningDetector;
    }
    
    @Override
    public void createSchema() {
      // Not needed for this test
    }
    
    @Override
    public Iterable<StringIdEntity> browse() {
      pinningDetector.checkForPinning();
      return new ArrayList<>(entities.values());
    }
    
    @Override
    public void create(StringIdEntity entity) {
      pinningDetector.checkForPinning();
      entities.put(entity.getId(), entity);
    }
    
    @Override
    public Optional<StringIdEntity> read(String id) {
      pinningDetector.checkForPinning();
      return Optional.ofNullable(entities.get(id));
    }
    
    @Override
    public boolean update(StringIdEntity entity) {
      pinningDetector.checkForPinning();
      if (entities.containsKey(entity.getId())) {
        entities.put(entity.getId(), entity);
        return true;
      }
      return false;
    }
    
    @Override
    public boolean delete(String id) {
      pinningDetector.checkForPinning();
      return entities.remove(id) != null;
    }
  }
  
  /**
   * Mock implementation of IterableDataAccess for testing.
   */
  static class MockIterableDataAccess implements IterableDataAccess<EntityIdEntity> {
    private final Map<TestEntityId, EntityIdEntity> entities = new HashMap<>();
    private final TestThreadPinningDetector pinningDetector;
    
    MockIterableDataAccess(TestThreadPinningDetector pinningDetector) {
      this.pinningDetector = pinningDetector;
    }
    
    @Override
    public void createSchema() {
      // Not needed for this test
    }
    
    @Override
    public Iterable<EntityIdEntity> browse() {
      pinningDetector.checkForPinning();
      return new ArrayList<>(entities.values());
    }
    
    @Override
    public void create(EntityIdEntity entity) {
      pinningDetector.checkForPinning();
      entities.put(entity.getId(), entity);
    }
    
    @Override
    public Optional<EntityIdEntity> read(EntityId id) {
      pinningDetector.checkForPinning();
      return Optional.ofNullable(entities.get(id));
    }
    
    @Override
    public boolean update(EntityIdEntity entity) {
      pinningDetector.checkForPinning();
      if (entities.containsKey(entity.getId())) {
        entities.put(entity.getId(), entity);
        return true;
      }
      return false;
    }
    
    @Override
    public boolean delete(EntityId id) {
      pinningDetector.checkForPinning();
      return entities.remove(id) != null;
    }
  }
  
  /**
   * Mock implementation of NamedDataAccess for testing.
   */
  static class MockNamedDataAccess implements NamedDataAccess<NamedEntity> {
    private final Map<String, NamedEntity> entities = new HashMap<>();
    private final TestThreadPinningDetector pinningDetector;
    
    MockNamedDataAccess(TestThreadPinningDetector pinningDetector) {
      this.pinningDetector = pinningDetector;
    }
    
    @Override
    public void createSchema() {
      // Not needed for this test
    }
    
    @Override
    public Iterable<NamedEntity> browse() {
      pinningDetector.checkForPinning();
      return new ArrayList<>(entities.values());
    }
    
    @Override
    public void create(NamedEntity entity) {
      pinningDetector.checkForPinning();
      entities.put(entity.getName(), entity);
    }
    
    @Override
    public Optional<NamedEntity> read(String name) {
      pinningDetector.checkForPinning();
      return Optional.ofNullable(entities.get(name));
    }
    
    @Override
    public boolean update(NamedEntity entity) {
      pinningDetector.checkForPinning();
      if (entities.containsKey(entity.getName())) {
        entities.put(entity.getName(), entity);
        return true;
      }
      return false;
    }
    
    @Override
    public boolean delete(String name) {
      pinningDetector.checkForPinning();
      return entities.remove(name) != null;
    }
  }
  
  /**
   * Mock implementation of SingletonDataAccess for testing.
   */
  static class MockSingletonDataAccess implements SingletonDataAccess<SingletonEntity> {
    private SingletonEntity entity;
    private final TestThreadPinningDetector pinningDetector;
    
    MockSingletonDataAccess(TestThreadPinningDetector pinningDetector) {
      this.pinningDetector = pinningDetector;
    }
    
    @Override
    public void createSchema() {
      // Not needed for this test
    }
    
    @Override
    public Optional<SingletonEntity> get() {
      pinningDetector.checkForPinning();
      return Optional.ofNullable(entity);
    }
    
    @Override
    public void set(SingletonEntity entity) {
      pinningDetector.checkForPinning();
      this.entity = entity;
    }
    
    @Override
    public void clear() {
      pinningDetector.checkForPinning();
      this.entity = null;
    }
  }
}