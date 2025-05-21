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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.internal.kv.NexusKeyValueDAO;
import org.sonatype.nexus.kv.NexusKeyValue;
import org.sonatype.nexus.kv.ValueType;
import org.sonatype.nexus.testdb.DataSessionRule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Test class for validating key-value store operations with Java 21 Virtual Threads.
 * 
 * <p>This class tests concurrent read/write operations on the Nexus key-value store,
 * ensuring proper transaction handling, resource management, and thread safety when
 * executed across numerous Virtual Threads.</p>
 */
public class KeyValueStoreVirtualThreadTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(KeyValueStoreVirtualThreadTest.class);
  
  private static final int THREAD_COUNT = 100;
  private static final int OPERATIONS_PER_THREAD = 50;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Rule
  public DataSessionRule sessionRule = new DataSessionRule()
      .access(NexusKeyValueDAO.class);
  
  private ExecutorService virtualThreadExecutor;
  private ObjectMapper objectMapper;
  
  @Before
  public void setUp() {
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    objectMapper = new ObjectMapper();
  }
  
  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      if (!virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.warn("Virtual thread executor did not terminate in {} seconds", TIMEOUT_SECONDS);
        virtualThreadExecutor.shutdownNow();
      }
    }
  }
  
  /**
   * Test concurrent read/write operations using Virtual Threads.
   * 
   * <p>This test creates multiple Virtual Threads that perform read and write operations
   * on the key-value store concurrently, verifying that all operations complete successfully
   * and data integrity is maintained.</p>
   */
  @Test
  public void testConcurrentReadWriteWithVirtualThreads() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    ConcurrentHashMap<String, TestObject> expectedValues = new ConcurrentHashMap<>();
    
    // Create and submit tasks
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            String key = "vt-test-" + threadId + "-" + j;
            TestObject testObject = new TestObject(
                "name-" + UUID.randomUUID().toString().substring(0, 8),
                threadId * 100 + j,
                j % 2 == 0);
            
            // Store the expected value
            expectedValues.put(key, testObject);
            
            // Write the value
            writeObject(key, testObject);
            
            // Read and verify the value
            Optional<TestObject> readObject = readObject(key);
            if (readObject.isPresent() && readObject.get().equals(testObject)) {
              successCount.incrementAndGet();
            }
            else {
              log.error("Read verification failed for key: {}", key);
            }
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread execution", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", 
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all operations were successful
    assertThat(successCount.get(), is(THREAD_COUNT * OPERATIONS_PER_THREAD));
    
    // Verify all values can be read correctly after concurrent operations
    AtomicInteger verificationCount = new AtomicInteger(0);
    List<String> failedKeys = new ArrayList<>();
    
    expectedValues.forEach((key, expectedObject) -> {
      Optional<TestObject> actualObject = readObject(key);
      if (actualObject.isPresent() && actualObject.get().equals(expectedObject)) {
        verificationCount.incrementAndGet();
      }
      else {
        failedKeys.add(key);
      }
    });
    
    if (!failedKeys.isEmpty()) {
      log.error("Failed verification for keys: {}", failedKeys);
    }
    
    assertThat("All values should be readable after concurrent operations",
        verificationCount.get(), is(expectedValues.size()));
    
    // Clean up - remove all test keys
    expectedValues.keySet().forEach(this::removeKey);
  }
  
  /**
   * Test transaction isolation with concurrent Virtual Threads.
   * 
   * <p>This test verifies that transactions are properly isolated when multiple
   * Virtual Threads are performing operations concurrently.</p>
   */
  @Test
  public void testTransactionIsolationWithVirtualThreads() throws Exception {
    final String sharedKey = "vt-shared-key";
    final TestObject initialObject = new TestObject("initial", 0, true);
    
    // Write initial value
    writeObject(sharedKey, initialObject);
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and submit tasks that will try to update the same key concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Try to read, modify, and write the shared key
          try (DataSession<?> dataSession = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
            NexusKeyValueDAO dao = dataSession.access(NexusKeyValueDAO.class);
            
            // Read current value
            Optional<NexusKeyValue> current = dao.get(sharedKey);
            if (current.isPresent()) {
              // Modify the value
              TestObject currentObject = current.get().getAsObject(objectMapper, TestObject.class);
              TestObject newObject = new TestObject(
                  currentObject.getName() + "-" + threadId,
                  currentObject.getAge() + 1,
                  !currentObject.isExclusive());
              
              // Write the new value
              NexusKeyValue kv = new NexusKeyValue();
              kv.setKey(sharedKey);
              kv.setType(ValueType.OBJECT);
              kv.setValue(newObject);
              dao.set(kv);
              
              // Commit the transaction
              dataSession.getTransaction().commit();
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in transaction for thread {}", threadId, e);
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread execution", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", 
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify that exactly THREAD_COUNT transactions succeeded
    assertThat("All transactions should succeed", successCount.get(), is(THREAD_COUNT));
    
    // Verify the final value has been modified exactly THREAD_COUNT times
    Optional<TestObject> finalObject = readObject(sharedKey);
    assertTrue("Final object should exist", finalObject.isPresent());
    assertThat("Age should be incremented exactly THREAD_COUNT times", 
        finalObject.get().getAge(), is(initialObject.getAge() + THREAD_COUNT));
    
    // Clean up
    removeKey(sharedKey);
  }
  
  /**
   * Test resource management during Virtual Thread handoffs.
   * 
   * <p>This test verifies that database connections and other resources are properly
   * managed when Virtual Threads are yielded during I/O operations.</p>
   */
  @Test
  public void testResourceManagementDuringThreadHandoffs() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and submit tasks
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform a sequence of operations with deliberate yields
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            String key = "vt-resource-" + threadId + "-" + j;
            TestObject testObject = new TestObject(
                "resource-" + UUID.randomUUID().toString().substring(0, 8),
                threadId * 100 + j,
                j % 2 == 0);
            
            // Write the value
            writeObject(key, testObject);
            
            // Simulate a yield point (e.g., during I/O)
            Thread.yield();
            
            // Read the value
            Optional<TestObject> readObject = readObject(key);
            
            // Simulate another yield point
            Thread.yield();
            
            // Verify and remove
            if (readObject.isPresent() && readObject.get().equals(testObject)) {
              if (removeKey(key)) {
                successCount.incrementAndGet();
              }
            }
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread execution", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", 
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all operations were successful
    assertThat(successCount.get(), is(THREAD_COUNT * OPERATIONS_PER_THREAD));
  }
  
  /**
   * Test high-concurrency read operations with Virtual Threads.
   * 
   * <p>This test creates a small set of key-value pairs and then performs a large number
   * of concurrent read operations using Virtual Threads to verify that the system can
   * handle high read concurrency efficiently.</p>
   */
  @Test
  public void testHighConcurrencyReads() throws Exception {
    // Create a small set of test data
    final int keyCount = 10;
    List<String> testKeys = new ArrayList<>();
    
    for (int i = 0; i < keyCount; i++) {
      String key = "vt-read-" + i;
      TestObject testObject = new TestObject("read-test-" + i, i, i % 2 == 0);
      writeObject(key, testObject);
      testKeys.add(key);
    }
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and submit tasks that will perform read operations
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform multiple read operations on the test keys
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            // Select a key randomly
            String key = testKeys.get(j % keyCount);
            
            // Read the value
            Optional<TestObject> readObject = readObject(key);
            
            // Verify the read was successful
            if (readObject.isPresent()) {
              successCount.incrementAndGet();
            }
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread execution", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", 
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all read operations were successful
    assertThat(successCount.get(), is(THREAD_COUNT * OPERATIONS_PER_THREAD));
    
    // Clean up
    testKeys.forEach(this::removeKey);
  }
  
  /**
   * Helper method to write a TestObject to the key-value store.
   */
  private void writeObject(String key, TestObject object) {
    try (DataSession<?> dataSession = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      NexusKeyValueDAO dao = dataSession.access(NexusKeyValueDAO.class);
      
      NexusKeyValue kv = new NexusKeyValue();
      kv.setKey(key);
      kv.setType(ValueType.OBJECT);
      kv.setValue(object);
      
      dao.set(kv);
      dataSession.getTransaction().commit();
    }
  }
  
  /**
   * Helper method to read a TestObject from the key-value store.
   */
  private Optional<TestObject> readObject(String key) {
    try (DataSession<?> dataSession = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      NexusKeyValueDAO dao = dataSession.access(NexusKeyValueDAO.class);
      
      Optional<NexusKeyValue> kv = dao.get(key);
      dataSession.getTransaction().commit();
      
      if (kv.isPresent()) {
        return Optional.of(kv.get().getAsObject(objectMapper, TestObject.class));
      }
      
      return Optional.empty();
    }
  }
  
  /**
   * Helper method to remove a key from the key-value store.
   */
  private boolean removeKey(String key) {
    try (DataSession<?> dataSession = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      NexusKeyValueDAO dao = dataSession.access(NexusKeyValueDAO.class);
      
      boolean result = dao.remove(key);
      dataSession.getTransaction().commit();
      
      return result;
    }
  }
  
  /**
   * Test object class for serialization/deserialization tests.
   */
  static class TestObject
  {
    private String name;
    
    private int age;
    
    private boolean exclusive;
    
    public TestObject(final String name, final int age, final boolean exclusive) {
      this.name = name;
      this.age = age;
      this.exclusive = exclusive;
    }
    
    public TestObject() {
      // Required for Jackson deserialization
    }
    
    public String getName() {
      return name;
    }
    
    public void setName(final String name) {
      this.name = name;
    }
    
    public int getAge() {
      return age;
    }
    
    public void setAge(final int age) {
      this.age = age;
    }
    
    public boolean isExclusive() {
      return exclusive;
    }
    
    public void setExclusive(final boolean exclusive) {
      this.exclusive = exclusive;
    }
    
    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TestObject that = (TestObject) o;
      return age == that.age && exclusive == that.exclusive && Objects.equals(name, that.name);
    }
    
    @Override
    public int hashCode() {
      return Objects.hash(name, age, exclusive);
    }
    
    @Override
    public String toString() {
      return "TestObject{" +
          "name='" + name + '\'' +
          ", age=" + age +
          ", exclusive=" + exclusive +
          '}';
    }
  }
}