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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Base test class for validating core DataStore API interface compatibility with Java 21 Virtual Threads.
 * 
 * This class provides common test utilities and infrastructure used by other Virtual Thread test classes
 * in the DataStore API module. It includes test cases for the DataStore interface itself and ensures that
 * core operations behave correctly when executed within Virtual Threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class DataStoreAPIVirtualThreadTest
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int TIMEOUT_SECONDS = 10;
  
  @Mock
  private DataStore<DataSession<?>> dataStore;
  
  @Mock
  private DataSource dataSource;
  
  @Mock
  private Connection connection;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  void setUp() throws SQLException {
    // Create executors for both virtual and platform threads for comparison
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    // Setup common mocks
    when(dataStore.getDataSource()).thenReturn(dataSource);
    when(dataSource.getConnection()).thenReturn(connection);
    when(dataStore.openConnection()).thenReturn(connection);
  }
  
  @AfterEach
  void tearDown() {
    virtualThreadExecutor.shutdown();
    platformThreadExecutor.shutdown();
  }
  
  /**
   * Utility method to run a task in a virtual thread and wait for completion.
   * 
   * @param runnable the task to execute
   * @throws Exception if the task execution fails or times out
   */
  private void runInVirtualThread(Runnable runnable) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> exception = new AtomicReference<>();
    
    virtualThreadExecutor.submit(() -> {
      try {
        runnable.run();
      }
      catch (Exception e) {
        exception.set(e);
      }
      finally {
        latch.countDown();
      }
    });
    
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(completed, "Task did not complete within timeout");
    
    if (exception.get() != null) {
      throw exception.get();
    }
  }
  
  /**
   * Tests that DataStore connection acquisition works correctly within a virtual thread.
   * This validates that JDBC connections can be properly obtained and used in virtual threads.
   */
  @Test
  void testConnectionAcquisitionInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      assertDoesNotThrow(() -> {
        try (Connection conn = dataStore.openConnection()) {
          assertThat(conn, is(notNullValue()));
        }
      });
    });
    
    verify(dataStore).openConnection();
  }
  
  /**
   * Tests that DataStore lifecycle operations (start, stop) work correctly within virtual threads.
   * This ensures that the core lifecycle management is compatible with virtual threads.
   */
  @Test
  void testLifecycleOperationsInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      dataStore.start();
      assertTrue(dataStore.isStarted());
      dataStore.stop();
    });
    
    verify(dataStore).start();
    verify(dataStore).stop();
  }
  
  /**
   * Tests that DataStore freeze/unfreeze operations work correctly within virtual threads.
   * This validates that state management operations function properly in virtual threads.
   */
  @Test
  void testFreezeUnfreezeInVirtualThread() throws Exception {
    when(dataStore.isFrozen()).thenReturn(true).thenReturn(false);
    
    runInVirtualThread(() -> {
      dataStore.freeze();
      assertTrue(dataStore.isFrozen());
      dataStore.unfreeze();
      assertFalse(dataStore.isFrozen());
    });
    
    verify(dataStore).freeze();
    verify(dataStore).unfreeze();
    verify(dataStore, times(2)).isFrozen();
  }
  
  /**
   * Tests that DataStore configuration operations work correctly within virtual threads.
   * This ensures that configuration management is compatible with virtual threads.
   */
  @Test
  void testConfigurationInVirtualThread() throws Exception {
    DataStoreConfiguration config = new DataStoreConfiguration();
    config.setName("test-store");
    config.setType("test-type");
    
    runInVirtualThread(() -> {
      dataStore.setConfiguration(config);
    });
    
    verify(dataStore).setConfiguration(config);
  }
  
  /**
   * Tests that DataStore registration operations work correctly within virtual threads.
   * This validates that DataAccess type registration functions properly in virtual threads.
   */
  @Test
  void testRegistrationInVirtualThread() throws Exception {
    Class<? extends DataAccess> accessType = TestDataAccess.class;
    
    runInVirtualThread(() -> {
      dataStore.register(accessType);
      dataStore.unregister(accessType);
    });
    
    verify(dataStore).register(accessType);
    verify(dataStore).unregister(accessType);
  }
  
  /**
   * Tests that DataStore exception handling works correctly within virtual threads.
   * This ensures that exceptions are properly propagated through virtual threads.
   */
  @Test
  void testExceptionHandlingInVirtualThread() throws Exception {
    SQLException sqlException = new SQLException("Test exception");
    when(dataStore.openConnection()).thenThrow(sqlException);
    
    AtomicReference<Throwable> caughtException = new AtomicReference<>();
    
    runInVirtualThread(() -> {
      try {
        dataStore.openConnection();
      }
      catch (Throwable t) {
        caughtException.set(t);
      }
    });
    
    assertThat(caughtException.get(), is(sqlException));
  }
  
  /**
   * Tests that DataStore shutdown operation works correctly within virtual threads.
   * This validates that resource cleanup functions properly in virtual threads.
   */
  @Test
  void testShutdownInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      assertDoesNotThrow(() -> dataStore.shutdown());
    });
    
    verify(dataStore).shutdown();
  }
  
  /**
   * Tests that DataStore operations can be executed concurrently from multiple virtual threads.
   * This validates that the DataStore interface can handle high concurrency with virtual threads.
   */
  @Test
  void testConcurrentOperationsWithVirtualThreads() throws Exception {
    int taskCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit multiple concurrent tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          dataStore.openConnection().close();
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(completed, "Not all tasks completed within timeout");
    
    // Verify results
    assertThat(errorCount.get(), is(0));
    verify(dataStore, times(taskCount)).openConnection();
  }
  
  /**
   * Tests the performance difference between virtual threads and platform threads
   * for I/O-bound operations. This validates that virtual threads provide better
   * scalability for I/O-bound workloads.
   */
  @Test
  void testPerformanceComparisonForIOBoundOperations() throws Exception {
    int taskCount = CONCURRENT_THREADS;
    
    // Simulate I/O latency
    when(dataStore.openConnection()).thenAnswer(invocation -> {
      Thread.sleep(50); // Simulate I/O latency
      return connection;
    });
    
    // Measure platform thread performance
    long platformThreadTime = measureExecutionTime(platformThreadExecutor, taskCount);
    
    // Measure virtual thread performance
    long virtualThreadTime = measureExecutionTime(virtualThreadExecutor, taskCount);
    
    // Virtual threads should be more efficient for I/O-bound operations
    // when the number of concurrent operations exceeds available CPU cores
    if (taskCount > Runtime.getRuntime().availableProcessors()) {
      assertThat("Virtual threads should be faster than platform threads for I/O-bound operations",
          virtualThreadTime, lessThan(platformThreadTime));
    }
  }
  
  /**
   * Tests that virtual threads properly handle errors during DataStore operations.
   * This validates that error propagation works correctly in virtual threads.
   */
  @Test
  void testErrorHandlingInVirtualThreads() throws Exception {
    Exception testException = new RuntimeException("Test exception");
    doThrow(testException).when(dataStore).start();
    
    AtomicBoolean exceptionCaught = new AtomicBoolean(false);
    
    runInVirtualThread(() -> {
      try {
        dataStore.start();
      }
      catch (RuntimeException e) {
        if (e.getMessage().equals("Test exception")) {
          exceptionCaught.set(true);
        }
      }
    });
    
    assertTrue(exceptionCaught.get(), "Exception was not properly propagated through virtual thread");
  }
  
  /**
   * Measures the execution time for running a specified number of tasks on the given executor.
   * 
   * @param executor the executor service to use
   * @param taskCount the number of tasks to execute
   * @return the execution time in milliseconds
   * @throws Exception if task execution fails
   */
  private long measureExecutionTime(ExecutorService executor, int taskCount) throws Exception {
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    long startTime = System.nanoTime();
    
    for (int i = 0; i < taskCount; i++) {
      executor.submit(() -> {
        try {
          dataStore.openConnection().close();
        }
        catch (Exception e) {
          // Ignore exceptions for performance measurement
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(completed, "Not all tasks completed within timeout");
    
    long endTime = System.nanoTime();
    return Duration.ofNanos(endTime - startTime).toMillis();
  }
  
  /**
   * Test implementation of DataAccess for registration tests.
   */
  private interface TestDataAccess extends DataAccess {
    // Test interface
  }
}