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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.internal.kv.NexusKeyValueDAO;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.KeyValueEvent;
import org.sonatype.nexus.kv.NexusKeyValue;
import org.sonatype.nexus.transaction.UnitOfWork;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for validating key-value store operations with Java 21 Virtual Threads.
 * 
 * This class tests concurrent read/write operations on the Nexus key-value store,
 * ensuring proper transaction handling, resource management, and thread safety when
 * executed across numerous Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
public class KeyValueStoreVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private DataSessionSupplier dataSessionSupplier;
  
  @Mock
  private NexusKeyValueDAO nexusKeyValueDAO;
  
  @Mock
  private EventManager eventManager;
  
  @Captor
  private ArgumentCaptor<KeyValueEvent> eventCaptor;
  
  private GlobalKeyValueStore underTest;
  
  @BeforeEach
  void setUp() throws Exception {
    // Setup the GlobalKeyValueStore with mocked dependencies
    underTest = new GlobalKeyValueStore(dataSessionSupplier, eventManager);
    
    // Mock the DAO behavior for get operations
    when(nexusKeyValueDAO.get(anyString())).thenAnswer(invocation -> {
      String key = invocation.getArgument(0);
      if (key.startsWith("existing-")) {
        NexusKeyValue value = new NexusKeyValue();
        value.setKey(key);
        value.setValue("value-" + key);
        return Optional.of(value);
      }
      return Optional.empty();
    });
    
    // Mock the DAO behavior for set operations
    doAnswer(invocation -> {
      NexusKeyValue keyValue = invocation.getArgument(0);
      log.debug("Setting key: {} with value: {}", keyValue.getKey(), keyValue.getValue());
      return null;
    }).when(nexusKeyValueDAO).set(any(NexusKeyValue.class));
    
    // Mock the DAO behavior for remove operations
    doAnswer(invocation -> {
      String key = invocation.getArgument(0);
      log.debug("Removing key: {}", key);
      return null;
    }).when(nexusKeyValueDAO).remove(anyString());
    
    // Setup UnitOfWork to use our mocked DAO
    UnitOfWork.begin(() -> nexusKeyValueDAO);
  }
  
  @AfterEach
  void tearDown() {
    UnitOfWork.end();
  }
  
  /**
   * Tests concurrent get operations using Virtual Threads.
   * 
   * This test verifies that multiple Virtual Threads can concurrently retrieve values
   * from the key-value store without conflicts or resource issues.
   */
  @Test
  void testConcurrentGetOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Create a mix of existing and non-existing keys
      List<String> keys = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        if (i % 2 == 0) {
          keys.add("existing-" + i);
        } else {
          keys.add("non-existing-" + i);
        }
      }
      
      // Submit concurrent get operations using virtual threads
      for (String key : keys) {
        executor.submit(() -> {
          try {
            Object result = underTest.get(key);
            if (key.startsWith("existing-")) {
              if (result != null) {
                successCount.incrementAndGet();
              }
            } else {
              if (result == null) {
                successCount.incrementAndGet();
              }
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
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for concurrent operations to complete");
      
      // Verify results
      assertThat(errorCount.get(), is(0));
      assertThat(successCount.get(), is(CONCURRENT_THREADS));
      
      // Verify the DAO was called the expected number of times
      verify(nexusKeyValueDAO, times(CONCURRENT_THREADS)).get(anyString());
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests concurrent set operations using Virtual Threads.
   * 
   * This test verifies that multiple Virtual Threads can concurrently store values
   * in the key-value store without conflicts, maintaining transactional integrity.
   */
  @Test
  void testConcurrentSetOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit concurrent set operations using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final String key = "key-" + i;
        final String value = "value-" + i;
        
        executor.submit(() -> {
          try {
            underTest.set(key, value);
          } catch (Exception e) {
            log.error("Error in virtual thread operation", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for concurrent operations to complete");
      
      // Verify results
      assertThat(errorCount.get(), is(0));
      
      // Verify the DAO was called the expected number of times
      verify(nexusKeyValueDAO, times(CONCURRENT_THREADS)).set(any(NexusKeyValue.class));
      
      // Verify events were published
      verify(eventManager, times(CONCURRENT_THREADS)).post(any(KeyValueEvent.class));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests concurrent remove operations using Virtual Threads.
   * 
   * This test verifies that multiple Virtual Threads can concurrently remove values
   * from the key-value store without conflicts or resource issues.
   */
  @Test
  void testConcurrentRemoveOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit concurrent remove operations using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final String key = "key-to-remove-" + i;
        
        executor.submit(() -> {
          try {
            underTest.removeKey(key);
          } catch (Exception e) {
            log.error("Error in virtual thread operation", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for concurrent operations to complete");
      
      // Verify results
      assertThat(errorCount.get(), is(0));
      
      // Verify the DAO was called the expected number of times
      verify(nexusKeyValueDAO, times(CONCURRENT_THREADS)).remove(anyString());
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests mixed read/write operations using Virtual Threads.
   * 
   * This test verifies that multiple Virtual Threads can concurrently perform
   * a mix of get, set, and remove operations without conflicts or resource issues.
   */
  @Test
  void testMixedOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit a mix of get, set, and remove operations using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final String key = "mixed-key-" + i;
        final String value = "mixed-value-" + i;
        final int operationType = i % 3; // 0 = get, 1 = set, 2 = remove
        
        executor.submit(() -> {
          try {
            switch (operationType) {
              case 0 -> underTest.get(key);
              case 1 -> underTest.set(key, value);
              case 2 -> underTest.removeKey(key);
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
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for concurrent operations to complete");
      
      // Verify results
      assertThat(errorCount.get(), is(0));
      
      // Calculate expected counts for each operation type
      int expectedGetCount = CONCURRENT_THREADS / 3;
      int expectedSetCount = CONCURRENT_THREADS / 3;
      int expectedRemoveCount = CONCURRENT_THREADS / 3;
      // Account for rounding if CONCURRENT_THREADS is not divisible by 3
      if (CONCURRENT_THREADS % 3 >= 1) expectedGetCount++;
      if (CONCURRENT_THREADS % 3 >= 2) expectedSetCount++;
      
      // Verify the DAO was called the expected number of times for each operation type
      verify(nexusKeyValueDAO, times(expectedGetCount)).get(anyString());
      verify(nexusKeyValueDAO, times(expectedSetCount)).set(any(NexusKeyValue.class));
      verify(nexusKeyValueDAO, times(expectedRemoveCount)).remove(anyString());
      
      // Verify events were published for set operations
      verify(eventManager, times(expectedSetCount)).post(any(KeyValueEvent.class));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests transaction integrity during Virtual Thread handoffs.
   * 
   * This test verifies that transaction context is properly maintained when operations
   * span multiple Virtual Thread scheduling points, ensuring atomicity and consistency.
   */
  @Test
  void testTransactionIntegrityWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit operations that will cause Virtual Thread handoffs
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final String key = "tx-key-" + i;
        final String value = "tx-value-" + i;
        
        executor.submit(() -> {
          try {
            // Set a value (first transaction)
            underTest.set(key, value);
            
            // Introduce a potential thread handoff point
            Thread.yield();
            
            // Get the value back (second transaction)
            Object result = underTest.get(key);
            
            // Another potential thread handoff point
            Thread.yield();
            
            // Remove the key (third transaction)
            underTest.removeKey(key);
          } catch (Exception e) {
            log.error("Error in virtual thread operation", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for concurrent operations to complete");
      
      // Verify results
      assertThat(errorCount.get(), is(0));
      
      // Verify the DAO was called the expected number of times for each operation
      verify(nexusKeyValueDAO, times(CONCURRENT_THREADS)).set(any(NexusKeyValue.class));
      verify(nexusKeyValueDAO, times(CONCURRENT_THREADS)).get(anyString());
      verify(nexusKeyValueDAO, times(CONCURRENT_THREADS)).remove(anyString());
      
      // Verify events were published for set operations
      verify(eventManager, times(CONCURRENT_THREADS)).post(any(KeyValueEvent.class));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests typed value retrieval with Virtual Threads.
   * 
   * This test verifies that the key-value store correctly handles type-specific
   * retrieval operations when executed across Virtual Threads.
   */
  @Test
  void testTypedValueRetrievalWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup typed test values
    String stringKey = "string-key-" + UUID.randomUUID();
    String stringValue = "string-value";
    
    String intKey = "int-key-" + UUID.randomUUID();
    Integer intValue = 42;
    
    String boolKey = "bool-key-" + UUID.randomUUID();
    Boolean boolValue = true;
    
    // Mock the DAO behavior for typed values
    when(nexusKeyValueDAO.get(eq(stringKey))).thenAnswer(invocation -> {
      NexusKeyValue value = new NexusKeyValue();
      value.setKey(stringKey);
      value.setValue(stringValue);
      return Optional.of(value);
    });
    
    when(nexusKeyValueDAO.get(eq(intKey))).thenAnswer(invocation -> {
      NexusKeyValue value = new NexusKeyValue();
      value.setKey(intKey);
      value.setValue(intValue);
      return Optional.of(value);
    });
    
    when(nexusKeyValueDAO.get(eq(boolKey))).thenAnswer(invocation -> {
      NexusKeyValue value = new NexusKeyValue();
      value.setKey(boolKey);
      value.setValue(boolValue);
      return Optional.of(value);
    });
    
    CountDownLatch latch = new CountDownLatch(3);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Test string retrieval
      executor.submit(() -> {
        try {
          String result = underTest.getString(stringKey);
          assertThat(result, is(equalTo(stringValue)));
        } catch (Exception e) {
          log.error("Error in string retrieval", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
      
      // Test integer retrieval
      executor.submit(() -> {
        try {
          Integer result = underTest.getInteger(intKey);
          assertThat(result, is(equalTo(intValue)));
        } catch (Exception e) {
          log.error("Error in integer retrieval", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
      
      // Test boolean retrieval
      executor.submit(() -> {
        try {
          Boolean result = underTest.getBoolean(boolKey);
          assertThat(result, is(equalTo(boolValue)));
        } catch (Exception e) {
          log.error("Error in boolean retrieval", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for concurrent operations to complete");
      
      // Verify results
      assertThat(errorCount.get(), is(0));
      
      // Verify the DAO was called the expected number of times
      verify(nexusKeyValueDAO).get(stringKey);
      verify(nexusKeyValueDAO).get(intKey);
      verify(nexusKeyValueDAO).get(boolKey);
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests event publication with Virtual Threads.
   * 
   * This test verifies that key-value events are properly published when operations
   * are executed across Virtual Threads, ensuring event-driven components receive
   * notifications of changes.
   */
  @Test
  void testEventPublicationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    String testKey = "event-key-" + UUID.randomUUID();
    String testValue = "event-value";
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit a set operation that should trigger an event
      executor.submit(() -> {
        try {
          underTest.set(testKey, testValue);
        } catch (Exception e) {
          log.error("Error in event publication test", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for the operation to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for operation to complete");
      
      // Verify results
      assertThat(errorCount.get(), is(0));
      
      // Verify the event was published with the correct key and value
      verify(eventManager).post(eventCaptor.capture());
      KeyValueEvent event = eventCaptor.getValue();
      assertThat(event, is(notNullValue()));
      assertThat(event.getKey(), is(equalTo(testKey)));
      assertThat(event.getValue(), is(equalTo(testValue)));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests null value handling with Virtual Threads.
   * 
   * This test verifies that the key-value store correctly handles null values
   * when operations are executed across Virtual Threads.
   */
  @Test
  void testNullValueHandlingWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    String nullKey = "null-key-" + UUID.randomUUID();
    
    // Mock the DAO behavior for null value
    when(nexusKeyValueDAO.get(eq(nullKey))).thenReturn(Optional.empty());
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Test null value retrieval
      executor.submit(() -> {
        try {
          Object result = underTest.get(nullKey);
          assertThat(result, is(nullValue()));
          
          String stringResult = underTest.getString(nullKey);
          assertThat(stringResult, is(nullValue()));
          
          Integer intResult = underTest.getInteger(nullKey);
          assertThat(intResult, is(nullValue()));
          
          Boolean boolResult = underTest.getBoolean(nullKey);
          assertThat(boolResult, is(nullValue()));
        } catch (Exception e) {
          log.error("Error in null value handling test", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for the operation to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for operation to complete");
      
      // Verify results
      assertThat(errorCount.get(), is(0));
      
      // Verify the DAO was called the expected number of times
      verify(nexusKeyValueDAO, times(4)).get(nullKey);
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that no events are published for remove operations with Virtual Threads.
   * 
   * This test verifies that remove operations do not trigger event publication
   * when executed across Virtual Threads.
   */
  @Test
  void testNoEventsForRemoveOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    String removeKey = "remove-key-" + UUID.randomUUID();
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit a remove operation
      executor.submit(() -> {
        try {
          underTest.removeKey(removeKey);
        } catch (Exception e) {
          log.error("Error in remove operation test", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for the operation to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for operation to complete");
      
      // Verify results
      assertThat(errorCount.get(), is(0));
      
      // Verify the DAO was called
      verify(nexusKeyValueDAO).remove(removeKey);
      
      // Verify no events were published
      verify(eventManager, never()).post(any(KeyValueEvent.class));
    } finally {
      executor.shutdown();
    }
  }
}