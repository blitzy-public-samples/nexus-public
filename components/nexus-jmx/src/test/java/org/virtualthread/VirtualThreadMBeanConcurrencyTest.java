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
package org.virtualthread;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.jmx.reflect.ExampleManagedObject;

/**
 * Test to validate JMX functionality under high concurrency using Java 21 Virtual Threads.
 * 
 * This test simulates hundreds or thousands of concurrent JMX operations using virtual threads
 * to verify scalability, thread safety, and performance characteristics of the JMX subsystem.
 * 
 * @since 3.60
 */
public class VirtualThreadMBeanConcurrencyTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 1000;
  private static final int OPERATIONS_PER_THREAD = 100;
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
   * Tests concurrent read operations on MBean attributes using virtual threads.
   */
  @Test
  public void testConcurrentReadWithVirtualThreads() throws Exception {
    log.info("Testing concurrent read operations with virtual threads");
    
    // Set initial value
    managedObject.setName("initialValue");
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
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
              assertThat(value, equalTo("initialValue"));
            }
            
            return true;
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
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
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      
      // End timing
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      
      log.info("Virtual threads read test completed in {} ms", durationMs);
      
      // Verify all threads completed successfully
      assertThat("All threads should complete in time", completed, equalTo(true));
      
      // Check results from all futures
      for (Future<?> future : futures) {
        assertThat(future.get(), equalTo(true));
      }
    }
  }
  
  /**
   * Tests concurrent write operations on MBean attributes using virtual threads.
   */
  @Test
  public void testConcurrentWriteWithVirtualThreads() throws Exception {
    log.info("Testing concurrent write operations with virtual threads");
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Create an atomic counter to track successful operations
    AtomicInteger successCounter = new AtomicInteger(0);
    
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
              
              // Verify the value was set
              String currentValue = (String) mbeanServer.getAttribute(objectName, "Name");
              // We don't assert equality because other threads might have changed the value
              // We just verify it's not null
              assertThat(currentValue, notNullValue());
              
              successCounter.incrementAndGet();
            }
            
            return true;
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
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
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      
      // End timing
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      
      log.info("Virtual threads write test completed in {} ms", durationMs);
      
      // Verify all threads completed successfully
      assertThat("All threads should complete in time", completed, equalTo(true));
      
      // Check results from all futures
      for (Future<?> future : futures) {
        assertThat(future.get(), equalTo(true));
      }
      
      // Verify the expected number of successful operations
      assertThat(successCounter.get(), equalTo(THREAD_COUNT * OPERATIONS_PER_THREAD));
    }
  }
  
  /**
   * Tests concurrent operation invocation on MBeans using virtual threads.
   */
  @Test
  public void testConcurrentOperationInvocationWithVirtualThreads() throws Exception {
    log.info("Testing concurrent operation invocation with virtual threads");
    
    // Set initial value
    managedObject.setName("initialValue");
    
    // Create a countdown latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Create an atomic counter to track successful operations
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Create tasks for concurrent operation invocations
      for (int i = 0; i < THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform multiple operation invocations
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              // First set a name
              String newValue = "thread-" + Thread.currentThread().threadId() + "-op-" + j;
              mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", newValue));
              
              // Then reset it using the operation
              mbeanServer.invoke(objectName, "resetName", new Object[0], new String[0]);
              
              // Verify the name was reset
              String currentValue = (String) mbeanServer.getAttribute(objectName, "Name");
              assertThat(currentValue, equalTo(null));
              
              successCounter.incrementAndGet();
            }
            
            return true;
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
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
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      
      // End timing
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      
      log.info("Virtual threads operation invocation test completed in {} ms", durationMs);
      
      // Verify all threads completed successfully
      assertThat("All threads should complete in time", completed, equalTo(true));
      
      // Check results from all futures
      for (Future<?> future : futures) {
        assertThat(future.get(), equalTo(true));
      }
      
      // Verify the expected number of successful operations
      assertThat(successCounter.get(), equalTo(THREAD_COUNT * OPERATIONS_PER_THREAD));
    }
  }
  
  /**
   * Compares performance between virtual threads and platform threads for JMX operations.
   */
  @Test
  public void testCompareVirtualThreadsVsPlatformThreads() throws Exception {
    log.info("Comparing virtual threads vs platform threads for JMX operations");
    
    // Number of threads for comparison (reduced for platform threads to avoid resource exhaustion)
    final int comparisonThreadCount = 500;
    final int operationsPerThread = 50;
    
    // Measure virtual threads performance
    long virtualThreadsDuration = measurePerformance(
        Executors.newVirtualThreadPerTaskExecutor(),
        comparisonThreadCount,
        operationsPerThread,
        "Virtual Threads");
    
    // Measure platform threads performance
    long platformThreadsDuration = measurePerformance(
        Executors.newFixedThreadPool(Math.min(100, comparisonThreadCount)), // Use a reasonable thread pool size
        comparisonThreadCount,
        operationsPerThread,
        "Platform Threads");
    
    log.info("Performance comparison results:");
    log.info("  Virtual Threads: {} ms", virtualThreadsDuration);
    log.info("  Platform Threads: {} ms", platformThreadsDuration);
    
    // Virtual threads should generally be more efficient for I/O-bound operations like JMX
    // However, the exact performance difference depends on many factors
    // We're mainly interested in verifying that virtual threads work correctly
    // and don't perform significantly worse than platform threads
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadsDuration, lessThan(platformThreadsDuration * 2));
  }
  
  /**
   * Helper method to measure performance of JMX operations using the provided executor.
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
              // Read operation
              String value = (String) mbeanServer.getAttribute(objectName, "Name");
              
              // Write operation
              String newValue = "thread-" + threadId + "-op-" + j;
              mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", newValue));
              
              // Operation invocation (every 5th iteration)
              if (j % 5 == 0) {
                mbeanServer.invoke(objectName, "resetName", new Object[0], new String[0]);
              }
            }
            
            return true;
          }
          catch (Exception e) {
            log.error("Error in {} thread", executorType, e);
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
      
      // Verify all threads completed successfully
      assertThat("All threads should complete in time", completed, equalTo(true));
      
      // Check results from all futures
      for (Future<?> future : futures) {
        assertThat(future.get(), equalTo(true));
      }
      
      return durationMs;
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests resource utilization (memory) of virtual threads vs platform threads.
   */
  @Test
  public void testResourceUtilization() throws Exception {
    log.info("Testing resource utilization of virtual threads vs platform threads");
    
    // Number of threads for comparison
    final int resourceThreadCount = 1000;
    
    // Measure memory usage before creating platform threads
    long beforePlatformThreads = getUsedMemory();
    
    // Create platform threads (but don't start them to avoid excessive resource usage)
    List<Thread> platformThreads = new ArrayList<>();
    for (int i = 0; i < resourceThreadCount; i++) {
      Thread thread = new Thread(() -> {
        try {
          Thread.sleep(1000);
        }
        catch (InterruptedException e) {
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
          Thread.sleep(1000);
        }
        catch (InterruptedException e) {
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
  }
  
  /**
   * Helper method to measure used memory.
   */
  private long getUsedMemory() {
    System.gc(); // Request garbage collection to get more accurate measurements
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}