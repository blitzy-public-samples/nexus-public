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
package org.sonatype.nexus.jmx.reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.Descriptor;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests JMX operations under high concurrency using virtual threads to verify performance,
 * scalability, and thread-safety.
 * 
 * This test validates that JMX descriptor reflection operations perform well under load
 * with many concurrent virtual threads.
 */
@EnabledOnJre(JRE.JAVA_21)
public class VirtualThreadJmxConcurrencyTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int WARMUP_OPERATIONS = 100;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  void setUp() {
    // Create virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create platform thread executor with same number of threads as concurrent operations
    // This is intentionally inefficient to demonstrate the advantage of virtual threads
    platformThreadExecutor = Executors.newFixedThreadPool(CONCURRENT_OPERATIONS, new ThreadFactory() {
      private final AtomicInteger counter = new AtomicInteger();
      
      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName("platform-thread-" + counter.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    });
  }
  
  @AfterEach
  void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
    }
  }
  
  /**
   * Test bean with JMX annotations for testing descriptor generation
   */
  @TestAuthor("nexus")
  public static class TestBean {
    @TestComments("method one")
    public void methodOne() {
      // Empty implementation
    }
    
    @TestComments("method two")
    public void methodTwo() {
      // Empty implementation
    }
    
    @TestComments("method three")
    public void methodThree() {
      // Empty implementation
    }
  }
  
  /**
   * Tests that JMX descriptor operations can be executed concurrently using virtual threads
   * without errors or thread safety issues.
   */
  @Test
  public void testConcurrentJmxOperationsWithVirtualThreads() throws Exception {
    TestBean testBean = new TestBean();
    ConcurrentMap<Integer, Descriptor> results = new ConcurrentHashMap<>();
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    // Warm up to avoid JIT compilation affecting timing
    for (int i = 0; i < WARMUP_OPERATIONS; i++) {
      DescriptorHelper.build(testBean.getClass());
    }
    
    // Execute concurrent operations using virtual threads
    long startTime = System.nanoTime();
    
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Build descriptor from class
          Descriptor descriptor = DescriptorHelper.build(testBean.getClass());
          results.put(index, descriptor);
        }
        catch (Exception e) {
          log.error("Error in virtual thread operation", e);
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all operations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    long endTime = System.nanoTime();
    long virtualThreadDuration = Duration.ofNanos(endTime - startTime).toMillis();
    
    log.info("Completed {} concurrent JMX operations using virtual threads in {} ms", 
        CONCURRENT_OPERATIONS, virtualThreadDuration);
    
    // Verify results
    assertThat(results.size(), is(CONCURRENT_OPERATIONS));
    
    // Verify a sample of the results
    Descriptor sampleDescriptor = results.get(0);
    assertThat(sampleDescriptor, notNullValue());
    assertThat(sampleDescriptor.getFieldValue("author"), equalTo("nexus"));
  }
  
  /**
   * Tests JMX descriptor operations with method reflection under high concurrency
   * using virtual threads.
   */
  @Test
  public void testConcurrentMethodReflectionWithVirtualThreads() throws Exception {
    TestBean testBean = new TestBean();
    Method methodOne = TestBean.class.getMethod("methodOne");
    Method methodTwo = TestBean.class.getMethod("methodTwo");
    Method methodThree = TestBean.class.getMethod("methodThree");
    Method[] methods = {methodOne, methodTwo, methodThree};
    
    ConcurrentMap<String, Descriptor> results = new ConcurrentHashMap<>();
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    // Execute concurrent operations using virtual threads
    long startTime = System.nanoTime();
    
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Select a method based on the index
          Method method = methods[index % methods.length];
          
          // Build descriptor from method
          Descriptor descriptor = DescriptorHelper.build(method);
          results.put(method.getName() + "-" + index, descriptor);
        }
        catch (Exception e) {
          log.error("Error in virtual thread operation", e);
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all operations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    long endTime = System.nanoTime();
    long duration = Duration.ofNanos(endTime - startTime).toMillis();
    
    log.info("Completed {} concurrent method reflection operations using virtual threads in {} ms", 
        CONCURRENT_OPERATIONS, duration);
    
    // Verify results
    assertThat(results.size(), is(CONCURRENT_OPERATIONS));
    
    // Verify a sample of the results for each method
    Descriptor methodOneDescriptor = results.get("methodOne-0");
    assertThat(methodOneDescriptor, notNullValue());
    assertThat(methodOneDescriptor.getFieldValue("comments"), equalTo("method one"));
    
    Descriptor methodTwoDescriptor = results.get("methodTwo-1");
    assertThat(methodTwoDescriptor, notNullValue());
    assertThat(methodTwoDescriptor.getFieldValue("comments"), equalTo("method two"));
  }
  
  /**
   * Compares performance between virtual threads and platform threads for JMX operations.
   * This test demonstrates the scalability benefits of virtual threads for concurrent I/O-bound
   * or reflection-heavy operations.
   */
  @Test
  public void compareVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    TestBean testBean = new TestBean();
    
    // First run with virtual threads
    ConcurrentMap<Integer, Descriptor> virtualThreadResults = new ConcurrentHashMap<>();
    List<CompletableFuture<Void>> virtualThreadFutures = new ArrayList<>();
    
    long virtualThreadStartTime = System.nanoTime();
    
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Add a small delay to simulate I/O or processing time
          Thread.sleep(5);
          
          // Build descriptor from class
          Descriptor descriptor = DescriptorHelper.build(testBean.getClass());
          virtualThreadResults.put(index, descriptor);
          
          // Also find annotations to add more reflection work
          List<Annotation> annotations = DescriptorHelper.findAllAnnotations(testBean.getClass().getAnnotations());
          assertThat(annotations, hasSize(greaterThan(0)));
        }
        catch (Exception e) {
          log.error("Error in virtual thread operation", e);
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor);
      
      virtualThreadFutures.add(future);
    }
    
    // Wait for all virtual thread operations to complete
    CompletableFuture.allOf(virtualThreadFutures.toArray(new CompletableFuture[0])).join();
    
    long virtualThreadEndTime = System.nanoTime();
    long virtualThreadDuration = Duration.ofNanos(virtualThreadEndTime - virtualThreadStartTime).toMillis();
    
    // Now run with platform threads
    ConcurrentMap<Integer, Descriptor> platformThreadResults = new ConcurrentHashMap<>();
    List<CompletableFuture<Void>> platformThreadFutures = new ArrayList<>();
    
    long platformThreadStartTime = System.nanoTime();
    
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Add a small delay to simulate I/O or processing time
          Thread.sleep(5);
          
          // Build descriptor from class
          Descriptor descriptor = DescriptorHelper.build(testBean.getClass());
          platformThreadResults.put(index, descriptor);
          
          // Also find annotations to add more reflection work
          List<Annotation> annotations = DescriptorHelper.findAllAnnotations(testBean.getClass().getAnnotations());
          assertThat(annotations, hasSize(greaterThan(0)));
        }
        catch (Exception e) {
          log.error("Error in platform thread operation", e);
          throw new RuntimeException(e);
        }
      }, platformThreadExecutor);
      
      platformThreadFutures.add(future);
    }
    
    // Wait for all platform thread operations to complete
    CompletableFuture.allOf(platformThreadFutures.toArray(new CompletableFuture[0])).join();
    
    long platformThreadEndTime = System.nanoTime();
    long platformThreadDuration = Duration.ofNanos(platformThreadEndTime - platformThreadStartTime).toMillis();
    
    // Log performance comparison
    log.info("Performance comparison for {} concurrent JMX operations:", CONCURRENT_OPERATIONS);
    log.info("  Virtual Threads: {} ms", virtualThreadDuration);
    log.info("  Platform Threads: {} ms", platformThreadDuration);
    log.info("  Difference: {} ms ({}%)", 
        platformThreadDuration - virtualThreadDuration,
        (platformThreadDuration > 0) ? 
            (int)((platformThreadDuration - virtualThreadDuration) * 100 / platformThreadDuration) : 0);
    
    // Verify both approaches produced correct results
    assertThat(virtualThreadResults.size(), is(CONCURRENT_OPERATIONS));
    assertThat(platformThreadResults.size(), is(CONCURRENT_OPERATIONS));
    
    // Note: We don't assert that virtual threads are faster as that would make the test brittle
    // The performance difference is logged for informational purposes
  }
  
  /**
   * Tests for thread pinning issues by performing operations that might block
   * virtual threads and verifying they still complete successfully.
   */
  @Test
  public void testVirtualThreadPinningResistance() throws Exception {
    TestBean testBean = new TestBean();
    ConcurrentMap<Integer, Boolean> results = new ConcurrentHashMap<>();
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Perform a mix of operations that might cause pinning in poorly designed code
          // but should work fine with proper virtual thread implementation
          
          // 1. Synchronization block (potential pinning risk)
          synchronized (this) {
            // Short synchronized block should be fine
            Thread.sleep(1);
          }
          
          // 2. JMX descriptor operations (reflection-heavy)
          Descriptor descriptor = DescriptorHelper.build(testBean.getClass());
          assertThat(descriptor.getFieldValue("author"), equalTo("nexus"));
          
          // 3. Another synchronization with reflection
          synchronized (testBean) {
            Method method = TestBean.class.getMethod("methodOne");
            Descriptor methodDescriptor = DescriptorHelper.build(method);
            assertThat(methodDescriptor.getFieldValue("comments"), equalTo("method one"));
          }
          
          results.put(index, true);
        }
        catch (Exception e) {
          log.error("Error in virtual thread pinning test", e);
          results.put(index, false);
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all operations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Verify all operations completed successfully
    assertThat(results.size(), is(CONCURRENT_OPERATIONS));
    assertThat(results.values().stream().filter(v -> !v).count(), is(0L));
    
    log.info("Successfully completed {} concurrent operations with potential pinning risks", 
        CONCURRENT_OPERATIONS);
  }
}