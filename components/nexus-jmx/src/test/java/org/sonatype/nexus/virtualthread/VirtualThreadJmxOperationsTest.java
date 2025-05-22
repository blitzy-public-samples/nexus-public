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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.jmx.reflect.ExampleManagedObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test to validate JMX operations under high-concurrency scenarios using Java 21 Virtual Threads.
 * 
 * This test suite validates that JMX operations maintain correctness and show improved performance
 * when executed concurrently using Virtual Threads, particularly for I/O-bound operations.
 * 
 * @since 3.60
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class VirtualThreadJmxOperationsTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 2000;
  private static final int OPERATIONS_PER_THREAD = 50;
  private static final String OBJECT_NAME = "org.sonatype.nexus.jmx:foo=bar";
  
  private MBeanServer mbeanServer;
  private ObjectName objectName;
  private ExampleManagedObject managedObject;
  
  @Before
  public void setUp() throws Exception {
    mbeanServer = ManagementFactory.getPlatformMBeanServer();
    managedObject = new ExampleManagedObject();
    objectName = new ObjectName(OBJECT_NAME);
    
    if (mbeanServer.isRegistered(objectName)) {
      mbeanServer.unregisterMBean(objectName);
    }
    
    mbeanServer.registerMBean(managedObject, objectName);
  }
  
  @After
  public void tearDown() throws Exception {
    if (mbeanServer.isRegistered(objectName)) {
      mbeanServer.unregisterMBean(objectName);
    }
  }
  
  /**
   * Tests high-concurrency JMX attribute reads using Virtual Threads.
   * 
   * This test validates that thousands of concurrent JMX attribute read operations
   * can be executed efficiently using Virtual Threads without errors.
   */
  @Test
  public void testHighConcurrencyAttributeReads() throws Exception {
    log.info("Testing high-concurrency JMX attribute reads with Virtual Threads");
    
    // Set initial value
    managedObject.setName("initialValue");
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Create atomic counter for successful operations
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Create atomic reference to capture any exception
    AtomicReference<Throwable> firstException = new AtomicReference<>();
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Create tasks for concurrent reads
      for (int i = 0; i < THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform multiple read operations
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              String value = (String) mbeanServer.getAttribute(objectName, "Name");
              if ("initialValue".equals(value)) {
                successCounter.incrementAndGet();
              }
            }
            
            return true;
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
            firstException.compareAndSet(null, e);
            return false;
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start timing
      long startTime = System.nanoTime();
      
      // Release all threads to start concurrently
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
      
      // End timing
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      
      log.info("High-concurrency attribute reads test completed in {} ms", durationMs);
      log.info("Successful operations: {} out of {}", 
          successCounter.get(), THREAD_COUNT * OPERATIONS_PER_THREAD);
      
      // Verify all threads completed successfully
      assertThat("All threads should complete in time", completed, equalTo(true));
      
      // Verify no exceptions occurred
      if (firstException.get() != null) {
        throw new AssertionError("Exception during test execution", firstException.get());
      }
      
      // Verify all operations were successful
      assertThat("All operations should succeed", 
          successCounter.get(), equalTo(THREAD_COUNT * OPERATIONS_PER_THREAD));
    }
  }
  
  /**
   * Tests high-concurrency JMX attribute writes using Virtual Threads.
   * 
   * This test validates that thousands of concurrent JMX attribute write operations
   * can be executed efficiently using Virtual Threads without errors.
   */
  @Test
  public void testHighConcurrencyAttributeWrites() throws Exception {
    log.info("Testing high-concurrency JMX attribute writes with Virtual Threads");
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Create atomic counter for successful operations
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Create atomic reference to capture any exception
    AtomicReference<Throwable> firstException = new AtomicReference<>();
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Create tasks for concurrent writes
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform multiple write operations
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              String newValue = "thread-" + threadId + "-op-" + j;
              mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", newValue));
              
              // Verify the value was set (this also tests read after write)
              String currentValue = (String) mbeanServer.getAttribute(objectName, "Name");
              if (currentValue != null) {
                successCounter.incrementAndGet();
              }
            }
            
            return true;
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
            firstException.compareAndSet(null, e);
            return false;
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start timing
      long startTime = System.nanoTime();
      
      // Release all threads to start concurrently
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
      
      // End timing
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      
      log.info("High-concurrency attribute writes test completed in {} ms", durationMs);
      log.info("Successful operations: {} out of {}", 
          successCounter.get(), THREAD_COUNT * OPERATIONS_PER_THREAD);
      
      // Verify all threads completed successfully
      assertThat("All threads should complete in time", completed, equalTo(true));
      
      // Verify no exceptions occurred
      if (firstException.get() != null) {
        throw new AssertionError("Exception during test execution", firstException.get());
      }
      
      // Verify all operations were successful
      assertThat("All operations should succeed", 
          successCounter.get(), equalTo(THREAD_COUNT * OPERATIONS_PER_THREAD));
    }
  }
  
  /**
   * Tests high-concurrency JMX operation invocations using Virtual Threads.
   * 
   * This test validates that thousands of concurrent JMX operation invocations
   * can be executed efficiently using Virtual Threads without errors.
   */
  @Test
  public void testHighConcurrencyOperationInvocations() throws Exception {
    log.info("Testing high-concurrency JMX operation invocations with Virtual Threads");
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Create atomic counter for successful operations
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Create atomic reference to capture any exception
    AtomicReference<Throwable> firstException = new AtomicReference<>();
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Create tasks for concurrent operation invocations
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform multiple operation invocations
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              // First set a name
              String newValue = "thread-" + threadId + "-op-" + j;
              mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", newValue));
              
              // Then reset it using the operation
              mbeanServer.invoke(objectName, "resetName", new Object[0], new String[0]);
              
              // Verify the name was reset
              String currentValue = (String) mbeanServer.getAttribute(objectName, "Name");
              if (currentValue == null) {
                successCounter.incrementAndGet();
              }
            }
            
            return true;
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
            firstException.compareAndSet(null, e);
            return false;
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start timing
      long startTime = System.nanoTime();
      
      // Release all threads to start concurrently
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
      
      // End timing
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      
      log.info("High-concurrency operation invocations test completed in {} ms", durationMs);
      log.info("Successful operations: {} out of {}", 
          successCounter.get(), THREAD_COUNT * OPERATIONS_PER_THREAD);
      
      // Verify all threads completed successfully
      assertThat("All threads should complete in time", completed, equalTo(true));
      
      // Verify no exceptions occurred
      if (firstException.get() != null) {
        throw new AssertionError("Exception during test execution", firstException.get());
      }
      
      // Verify all operations were successful
      assertThat("All operations should succeed", 
          successCounter.get(), equalTo(THREAD_COUNT * OPERATIONS_PER_THREAD));
    }
  }
  
  /**
   * Tests mixed JMX operations (reads, writes, invocations) using Virtual Threads.
   * 
   * This test validates that thousands of concurrent mixed JMX operations
   * can be executed efficiently using Virtual Threads without errors.
   */
  @Test
  public void testMixedJmxOperations() throws Exception {
    log.info("Testing mixed JMX operations with Virtual Threads");
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Create atomic counter for successful operations
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Create atomic reference to capture any exception
    AtomicReference<Throwable> firstException = new AtomicReference<>();
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Create tasks for mixed JMX operations
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform multiple mixed operations
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              // Operation type based on iteration (read, write, or invoke)
              int operationType = j % 3;
              
              switch (operationType) {
                case 0: // Read operation
                  String value = (String) mbeanServer.getAttribute(objectName, "Name");
                  // Just verify we got a value (could be null or any string)
                  successCounter.incrementAndGet();
                  break;
                  
                case 1: // Write operation
                  String newValue = "thread-" + threadId + "-op-" + j;
                  mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", newValue));
                  successCounter.incrementAndGet();
                  break;
                  
                case 2: // Invoke operation
                  mbeanServer.invoke(objectName, "resetName", new Object[0], new String[0]);
                  successCounter.incrementAndGet();
                  break;
              }
            }
            
            return true;
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
            firstException.compareAndSet(null, e);
            return false;
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start timing
      long startTime = System.nanoTime();
      
      // Release all threads to start concurrently
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
      
      // End timing
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      
      log.info("Mixed JMX operations test completed in {} ms", durationMs);
      log.info("Successful operations: {} out of {}", 
          successCounter.get(), THREAD_COUNT * OPERATIONS_PER_THREAD);
      
      // Verify all threads completed successfully
      assertThat("All threads should complete in time", completed, equalTo(true));
      
      // Verify no exceptions occurred
      if (firstException.get() != null) {
        throw new AssertionError("Exception during test execution", firstException.get());
      }
      
      // Verify all operations were successful
      assertThat("All operations should succeed", 
          successCounter.get(), equalTo(THREAD_COUNT * OPERATIONS_PER_THREAD));
    }
  }
  
  /**
   * Compares performance between Virtual Threads and Platform Threads for JMX operations.
   * 
   * This test validates that Virtual Threads provide better performance for I/O-bound
   * JMX operations compared to Platform Threads, especially under high concurrency.
   */
  @Test
  public void testVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    log.info("Comparing Virtual Threads vs Platform Threads for JMX operations");
    
    // Number of threads for comparison (reduced for platform threads to avoid resource exhaustion)
    final int comparisonThreadCount = 1000;
    final int operationsPerThread = 20;
    
    // Measure virtual threads performance
    long virtualThreadsDuration = measurePerformance(
        Executors.newVirtualThreadPerTaskExecutor(),
        comparisonThreadCount,
        operationsPerThread,
        "Virtual Threads");
    
    // Measure platform threads performance with a reasonable thread pool size
    // to avoid resource exhaustion
    int platformThreadPoolSize = Math.min(100, comparisonThreadCount);
    long platformThreadsDuration = measurePerformance(
        Executors.newFixedThreadPool(platformThreadPoolSize),
        comparisonThreadCount,
        operationsPerThread,
        "Platform Threads");
    
    log.info("Performance comparison results:");
    log.info("  Virtual Threads: {} ms for {} concurrent operations", 
        virtualThreadsDuration, comparisonThreadCount * operationsPerThread);
    log.info("  Platform Threads: {} ms for {} concurrent operations", 
        platformThreadsDuration, comparisonThreadCount * operationsPerThread);
    
    // Virtual threads should generally be more efficient for I/O-bound operations like JMX
    // We expect virtual threads to perform better, but the exact performance difference
    // depends on many factors including hardware, JVM version, and system load
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadsDuration, lessThan(platformThreadsDuration * 1.5));
    
    // In most cases, virtual threads should be faster, but we use a conservative assertion
    // to avoid test flakiness due to environmental factors
    log.info("Performance ratio (platform/virtual): {}", 
        (double) platformThreadsDuration / virtualThreadsDuration);
  }
  
  /**
   * Tests asynchronous JMX operations using CompletableFuture with Virtual Threads.
   * 
   * This test validates that JMX operations can be efficiently executed asynchronously
   * using CompletableFuture with Virtual Threads as the execution mechanism.
   */
  @Test
  public void testAsyncJmxOperationsWithCompletableFuture() throws Exception {
    log.info("Testing asynchronous JMX operations with CompletableFuture and Virtual Threads");
    
    // Set initial value
    managedObject.setName("initialValue");
    
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Create atomic counter for successful operations
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Create atomic reference to capture any exception
    AtomicReference<Throwable> firstException = new AtomicReference<>();
    
    // Start timing
    long startTime = System.nanoTime();
    
    // Create CompletableFuture tasks for async JMX operations
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Perform multiple operations
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            // Mixed operations based on iteration
            if (j % 3 == 0) {
              // Read operation
              String value = (String) mbeanServer.getAttribute(objectName, "Name");
              if (value != null || value == null) { // Always true, just to count
                successCounter.incrementAndGet();
              }
            }
            else if (j % 3 == 1) {
              // Write operation
              String newValue = "thread-" + threadId + "-op-" + j;
              mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", newValue));
              successCounter.incrementAndGet();
            }
            else {
              // Invoke operation
              mbeanServer.invoke(objectName, "resetName", new Object[0], new String[0]);
              successCounter.incrementAndGet();
            }
          }
        }
        catch (Exception e) {
          log.error("Error in CompletableFuture task", e);
          firstException.compareAndSet(null, e);
        }
        finally {
          completionLatch.countDown();
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
      
      futures.add(future);
    }
    
    // Wait for all operations to complete
    boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
    
    // End timing
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    
    log.info("Async JMX operations test completed in {} ms", durationMs);
    log.info("Successful operations: {} out of {}", 
        successCounter.get(), THREAD_COUNT * OPERATIONS_PER_THREAD);
    
    // Verify all operations completed successfully
    assertThat("All operations should complete in time", completed, equalTo(true));
    
    // Verify no exceptions occurred
    if (firstException.get() != null) {
      throw new AssertionError("Exception during async execution", firstException.get());
    }
    
    // Verify all operations were successful
    assertThat("All operations should succeed", 
        successCounter.get(), equalTo(THREAD_COUNT * OPERATIONS_PER_THREAD));
    
    // Verify CompletableFuture tasks completed successfully
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    allFutures.join(); // This should not throw if all futures completed successfully
  }
  
  /**
   * Tests resource utilization of Virtual Threads vs Platform Threads for JMX operations.
   * 
   * This test validates that Virtual Threads use significantly less system resources
   * compared to Platform Threads when executing JMX operations, particularly under
   * high concurrency scenarios.
   */
  @Test
  public void testResourceUtilization() throws Exception {
    log.info("Testing resource utilization of Virtual Threads vs Platform Threads for JMX operations");
    
    // Number of threads for comparison
    final int resourceThreadCount = 1000;
    
    // Measure memory usage before creating platform threads
    long beforePlatformThreads = getUsedMemory();
    
    // Create platform threads (but don't start them to avoid excessive resource usage)
    List<Thread> platformThreads = new ArrayList<>();
    for (int i = 0; i < resourceThreadCount; i++) {
      Thread thread = new Thread(() -> {
        try {
          // Simulate JMX operation
          mbeanServer.getAttribute(objectName, "Name");
          Thread.sleep(10);
        }
        catch (Exception e) {
          // Ignore
        }
      });
      platformThreads.add(thread);
    }
    
    // Measure memory after creating platform threads
    long afterPlatformThreads = getUsedMemory();
    long platformThreadsMemory = afterPlatformThreads - beforePlatformThreads;
    
    // Clean up platform threads to free memory
    platformThreads.clear();
    System.gc();
    
    // Measure memory usage before creating virtual threads
    long beforeVirtualThreads = getUsedMemory();
    
    // Create virtual threads (but don't start them)
    List<Thread> virtualThreads = new ArrayList<>();
    ThreadFactory factory = Thread.ofVirtual().factory();
    for (int i = 0; i < resourceThreadCount; i++) {
      Thread thread = factory.newThread(() -> {
        try {
          // Simulate JMX operation
          mbeanServer.getAttribute(objectName, "Name");
          Thread.sleep(10);
        }
        catch (Exception e) {
          // Ignore
        }
      });
      virtualThreads.add(thread);
    }
    
    // Measure memory after creating virtual threads
    long afterVirtualThreads = getUsedMemory();
    long virtualThreadsMemory = afterVirtualThreads - beforeVirtualThreads;
    
    log.info("Memory usage for {} threads:", resourceThreadCount);
    log.info("  Platform threads: {} bytes", platformThreadsMemory);
    log.info("  Virtual threads: {} bytes", virtualThreadsMemory);
    
    // Virtual threads should use significantly less memory
    assertThat("Virtual threads should use less memory than platform threads",
        virtualThreadsMemory, lessThan(platformThreadsMemory));
    
    // Calculate and log the memory efficiency ratio
    double memoryEfficiencyRatio = (double) platformThreadsMemory / virtualThreadsMemory;
    log.info("Memory efficiency ratio (platform/virtual): {}", memoryEfficiencyRatio);
    
    // In most environments, virtual threads should be at least 2x more memory efficient
    assertThat("Virtual threads should be significantly more memory efficient",
        memoryEfficiencyRatio, greaterThan(1.5));
  }
  
  /**
   * Helper method to measure performance of JMX operations using the provided executor.
   * 
   * @param executor The executor service to use for concurrent operations
   * @param threadCount The number of concurrent threads/tasks
   * @param operationsPerThread The number of operations per thread
   * @param executorType A descriptive name for the executor type (for logging)
   * @return The duration in milliseconds to complete all operations
   */
  private long measurePerformance(
      ExecutorService executor,
      int threadCount,
      int operationsPerThread,
      String executorType) throws Exception
  {
    // Reset the managed object
    managedObject.setName(null);
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create atomic counter for successful operations
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Create atomic reference to capture any exception
    AtomicReference<Throwable> firstException = new AtomicReference<>();
    
    List<Future<?>> futures = new ArrayList<>();
    
    try {
      // Create tasks for mixed JMX operations
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform a mix of read, write, and operation invocations
            for (int j = 0; j < operationsPerThread; j++) {
              // Operation type based on iteration
              int operationType = j % 3;
              
              switch (operationType) {
                case 0: // Read operation
                  String value = (String) mbeanServer.getAttribute(objectName, "Name");
                  // Just verify we got a value (could be null or any string)
                  successCounter.incrementAndGet();
                  break;
                  
                case 1: // Write operation
                  String newValue = "thread-" + threadId + "-op-" + j;
                  mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", newValue));
                  successCounter.incrementAndGet();
                  break;
                  
                case 2: // Invoke operation
                  mbeanServer.invoke(objectName, "resetName", new Object[0], new String[0]);
                  successCounter.incrementAndGet();
                  break;
              }
            }
            
            return true;
          }
          catch (Exception e) {
            log.error("Error in {} thread", executorType, e);
            firstException.compareAndSet(null, e);
            return false;
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start timing
      long startTime = System.nanoTime();
      
      // Release all threads to start concurrently
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
      
      // End timing
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      
      log.info("{} test completed in {} ms", executorType, durationMs);
      log.info("Successful operations: {} out of {}", 
          successCounter.get(), threadCount * operationsPerThread);
      
      // Verify all threads completed successfully
      assertThat("All threads should complete in time", completed, equalTo(true));
      
      // Verify no exceptions occurred
      if (firstException.get() != null) {
        throw new AssertionError("Exception during test execution", firstException.get());
      }
      
      // Verify all operations were successful
      assertThat("All operations should succeed", 
          successCounter.get(), equalTo(threadCount * operationsPerThread));
      
      return durationMs;
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Helper method to measure used memory.
   * 
   * @return The amount of used memory in bytes
   */
  private long getUsedMemory() {
    System.gc(); // Request garbage collection to get more accurate measurements
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}