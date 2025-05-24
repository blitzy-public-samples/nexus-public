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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for JMX operations using Java 21 Virtual Threads.
 * 
 * This test suite validates that JMX operations can be efficiently executed using
 * Virtual Threads, demonstrating improved performance for I/O-bound operations
 * and correct behavior under high concurrency scenarios.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class VirtualThreadJmxOperationsTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 5000;
  private static final int OPERATION_DELAY_MS = 10;
  private static final int TIMEOUT_SECONDS = 30;
  
  private MBeanServer mbeanServer;
  private ObjectName testBeanName;
  private TestMBeanImpl testBean;
  
  /**
   * Interface for our test MBean.
   */
  public interface TestMBean {
    int getValue();
    void setValue(int value);
    int performOperation(int input);
    int performSlowOperation(int input, long delayMs);
  }
  
  /**
   * Implementation of our test MBean.
   */
  public static class TestMBeanImpl implements TestMBean {
    private final AtomicInteger value = new AtomicInteger(0);
    private final AtomicLong operationCount = new AtomicLong(0);
    
    @Override
    public int getValue() {
      return value.get();
    }
    
    @Override
    public void setValue(int newValue) {
      value.set(newValue);
    }
    
    @Override
    public int performOperation(int input) {
      operationCount.incrementAndGet();
      return input * 2;
    }
    
    @Override
    public int performSlowOperation(int input, long delayMs) {
      operationCount.incrementAndGet();
      try {
        // Simulate I/O-bound operation with a delay
        Thread.sleep(delayMs);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return input * 2;
    }
    
    public long getOperationCount() {
      return operationCount.get();
    }
    
    public void resetOperationCount() {
      operationCount.set(0);
    }
  }
  
  @BeforeEach
  void setUp() throws Exception {
    mbeanServer = ManagementFactory.getPlatformMBeanServer();
    testBean = new TestMBeanImpl();
    testBeanName = new ObjectName("org.sonatype.nexus.virtualthread:type=TestMBean");
    
    // Register the MBean
    if (mbeanServer.isRegistered(testBeanName)) {
      mbeanServer.unregisterMBean(testBeanName);
    }
    mbeanServer.registerMBean(testBean, testBeanName);
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (mbeanServer.isRegistered(testBeanName)) {
      mbeanServer.unregisterMBean(testBeanName);
    }
  }
  
  /**
   * Tests that JMX operations can be invoked concurrently using Virtual Threads.
   */
  @Test
  void testConcurrentJmxOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int operationCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int value = i;
        executor.submit(() -> {
          try {
            int result = (int) mbeanServer.invoke(testBeanName, "performOperation", 
                new Object[]{value}, new String[]{"int"});
            if (result != value * 2) {
              errorCount.incrementAndGet();
            }
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
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "All operations should complete within timeout");
      
      // Verify results
      assertThat("No errors should occur during concurrent operations", 
          errorCount.get(), is(0));
      assertThat("All operations should be processed", 
          testBean.getOperationCount(), is((long) operationCount));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that JMX attribute updates can be performed concurrently using Virtual Threads.
   */
  @Test
  void testConcurrentJmxAttributeUpdatesWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int operationCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent attribute updates using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int value = i;
        executor.submit(() -> {
          try {
            mbeanServer.setAttribute(testBeanName, 
                new javax.management.Attribute("Value", value));
            
            // Read back the attribute to verify
            int readValue = (int) mbeanServer.getAttribute(testBeanName, "Value");
            // We can't verify exact value due to race conditions, but we can check it's valid
            if (readValue < 0 || readValue >= operationCount) {
              errorCount.incrementAndGet();
            }
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
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "All operations should complete within timeout");
      
      // Verify results
      assertThat("No errors should occur during concurrent attribute updates", 
          errorCount.get(), is(0));
      
      // Final value should be within valid range
      int finalValue = testBean.getValue();
      assertThat("Final value should be within valid range", 
          finalValue, is(greaterThan(-1)));
      assertThat("Final value should be within valid range", 
          finalValue, is(lessThan(operationCount)));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Compares performance between Virtual Threads and Platform Threads for I/O-bound JMX operations.
   */
  @Test
  void compareVirtualThreadsVsPlatformThreadsForJmxOperations() throws Exception {
    // Reset operation count
    testBean.resetOperationCount();
    
    // Run with platform threads
    long platformThreadDuration = measureJmxOperationsWithThreadType(false);
    long platformThreadOperations = testBean.getOperationCount();
    testBean.resetOperationCount();
    
    // Run with virtual threads
    long virtualThreadDuration = measureJmxOperationsWithThreadType(true);
    long virtualThreadOperations = testBean.getOperationCount();
    
    // Log the results
    log.info("Platform Threads: {} operations in {} ms", platformThreadOperations, platformThreadDuration);
    log.info("Virtual Threads: {} operations in {} ms", virtualThreadOperations, virtualThreadDuration);
    
    // Virtual threads should complete all operations
    assertThat("Virtual threads should complete all operations", 
        virtualThreadOperations, is((long) CONCURRENT_OPERATIONS));
    
    // Platform threads might not complete all operations due to thread pool limitations
    // but we expect virtual threads to be more efficient
    assertTrue(virtualThreadDuration <= platformThreadDuration * 1.2, 
        "Virtual threads should be at least as efficient as platform threads");
  }
  
  /**
   * Tests that I/O-bound JMX operations benefit from Virtual Threads.
   */
  @Test
  void testIoBoundJmxOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int operationCount = 1000; // Reduced count for I/O-bound operations
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean allOperationsStarted = new AtomicBoolean(false);
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    try {
      // Submit multiple concurrent I/O-bound operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int value = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            int result = (int) mbeanServer.invoke(testBeanName, "performSlowOperation", 
                new Object[]{value, OPERATION_DELAY_MS}, 
                new String[]{"int", "long"});
            if (result != value * 2) {
              errorCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }
      
      allOperationsStarted.set(true);
      
      // Wait for all tasks to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "All operations should complete within timeout");
      
      // Verify results
      assertThat("No errors should occur during I/O-bound operations", 
          errorCount.get(), is(0));
      assertThat("All operations should be processed", 
          testBean.getOperationCount(), is((long) operationCount));
      
      // All futures should complete successfully
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that JMX operations maintain correctness under extreme concurrency with Virtual Threads.
   */
  @Test
  void testJmxOperationCorrectnessUnderExtremeConcurrency() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int operationCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Reset the bean value
    testBean.setValue(0);
    
    try {
      // Submit multiple concurrent increment operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Get current value
            int currentValue = (int) mbeanServer.getAttribute(testBeanName, "Value");
            
            // Increment value (this is intentionally not atomic to test concurrency issues)
            mbeanServer.setAttribute(testBeanName, 
                new javax.management.Attribute("Value", currentValue + 1));
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
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "All operations should complete within timeout");
      
      // Verify results
      assertThat("No errors should occur during concurrent operations", 
          errorCount.get(), is(0));
      
      // Due to race conditions, the final value will be less than operationCount
      // but we can verify it's greater than zero and less than or equal to operationCount
      int finalValue = testBean.getValue();
      log.info("Final value after {} concurrent increments: {}", operationCount, finalValue);
      
      assertThat("Final value should be greater than zero", 
          finalValue, is(greaterThan(0)));
      assertThat("Final value should be less than or equal to operation count", 
          finalValue, is(lessThan(operationCount + 1)));
      
      // The race condition is expected, so we don't assert that finalValue == operationCount
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that JMX operations can be performed with thousands of concurrent Virtual Threads.
   */
  @Test
  void testThousandsOfConcurrentJmxOperations() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int operationCount = 10000; // Ten thousand concurrent operations
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger completedCount = new AtomicInteger(0);
    
    try {
      // Reset operation count
      testBean.resetOperationCount();
      
      // Submit thousands of concurrent operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int value = i;
        executor.submit(() -> {
          try {
            int result = (int) mbeanServer.invoke(testBeanName, "performOperation", 
                new Object[]{value}, new String[]{"int"});
            if (result == value * 2) {
              completedCount.incrementAndGet();
            }
            else {
              errorCount.incrementAndGet();
            }
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
      assertTrue(latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS), 
          "All operations should complete within extended timeout");
      
      // Verify results
      assertThat("No errors should occur during concurrent operations", 
          errorCount.get(), is(0));
      assertThat("All operations should complete successfully", 
          completedCount.get(), is(operationCount));
      assertThat("All operations should be processed by the MBean", 
          testBean.getOperationCount(), is((long) operationCount));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Helper method to measure JMX operation performance with either platform or virtual threads.
   * 
   * @param useVirtualThreads true to use virtual threads, false to use platform threads
   * @return the duration in milliseconds
   */
  private long measureJmxOperationsWithThreadType(boolean useVirtualThreads) throws Exception {
    ThreadFactory threadFactory;
    ExecutorService executor;
    
    if (useVirtualThreads) {
      threadFactory = Thread.ofVirtual().factory();
      executor = Executors.newThreadPerTaskExecutor(threadFactory);
    } 
    else {
      // Use a fixed thread pool for platform threads with a reasonable size
      int threadPoolSize = Math.min(100, Runtime.getRuntime().availableProcessors() * 8);
      executor = Executors.newFixedThreadPool(threadPoolSize);
    }
    
    int operationCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    try {
      long startTime = System.currentTimeMillis();
      
      // Submit operations
      for (int i = 0; i < operationCount; i++) {
        final int value = i;
        executor.submit(() -> {
          try {
            mbeanServer.invoke(testBeanName, "performSlowOperation", 
                new Object[]{value, OPERATION_DELAY_MS / 2}, // Use half the delay for this benchmark
                new String[]{"int", "long"});
          } 
          catch (Exception e) {
            // Log but continue
            log.error("Error during JMX operation", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for operations to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      long endTime = System.currentTimeMillis();
      
      if (!completed) {
        log.warn("Not all operations completed within timeout using {} threads", 
            useVirtualThreads ? "virtual" : "platform");
      }
      
      return endTime - startTime;
    } 
    finally {
      executor.shutdownNow();
    }
  }
}