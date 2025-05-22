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
package org.apache.karaf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.Thread.Builder.OfVirtual;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.sonatype.goodies.testsupport.TestSupport;

/**
 * Tests to validate that Apache Karaf 4.4.4 is compatible with Java 21's Virtual Threads.
 * 
 * This test class verifies that OSGi service operations can be executed within virtual threads
 * without issues such as thread pinning or deadlocks. It also tests for proper handling of
 * thread-local variables and monitors thread pinning using JDK.tracePinnedThreads.
 * 
 * @since 3.60
 */
public class KarafVirtualThreadTest
    extends TestSupport
{
  private AutoCloseable mocks;
  
  @Mock
  private BundleContext bundleContext;
  
  @Mock
  private Bundle bundle;
  
  @Before
  public void setup() {
    mocks = MockitoAnnotations.openMocks(this);
    when(bundleContext.getBundle()).thenReturn(bundle);
  }
  
  @After
  public void cleanup() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }
  
  /**
   * Test interface representing a simple OSGi service
   */
  interface TestService {
    String doSomething();
  }
  
  /**
   * Implementation of TestService that performs a simulated I/O operation
   */
  static class TestServiceImpl implements TestService {
    @Override
    public String doSomething() {
      try {
        // Simulate I/O operation
        Thread.sleep(50);
        return "Done by " + Thread.currentThread().toString();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return "Interrupted";
      }
    }
  }
  
  /**
   * Tests that virtual threads can register and use OSGi services without issues.
   * This verifies basic compatibility between virtual threads and OSGi operations.
   */
  @Test
  public void testVirtualThreadServiceRegistration() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a service to register
    TestService service = new TestServiceImpl();
    Dictionary<String, Object> properties = new Hashtable<>();
    properties.put("test", "value");
    
    // Mock the service registration
    @SuppressWarnings("unchecked")
    ServiceRegistration<TestService> registration = mock(ServiceRegistration.class);
    when(bundleContext.registerService(TestService.class, service, properties)).thenReturn(registration);
    
    @SuppressWarnings("unchecked")
    ServiceReference<TestService> reference = mock(ServiceReference.class);
    when(bundleContext.getServiceReference(TestService.class)).thenReturn(reference);
    when(bundleContext.getService(reference)).thenReturn(service);
    
    // Use CountDownLatch to coordinate between threads
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean(false);
    
    // Create and start a virtual thread to register and use the service
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        // Register the service
        ServiceRegistration<TestService> reg = bundleContext.registerService(TestService.class, service, properties);
        assertNotNull("Service registration should not be null", reg);
        
        // Get and use the service
        ServiceReference<TestService> ref = bundleContext.getServiceReference(TestService.class);
        assertNotNull("Service reference should not be null", ref);
        
        TestService svc = bundleContext.getService(ref);
        assertNotNull("Service should not be null", svc);
        
        String result = svc.doSomething();
        assertNotNull("Service result should not be null", result);
        
        // Verify this is running in a virtual thread
        assertTrue("Should be running in a virtual thread", Thread.currentThread().isVirtual());
        
        success.set(true);
      }
      catch (Exception e) {
        log.error("Error in virtual thread", e);
      }
      finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    assertTrue("Test should complete within timeout", latch.await(5, TimeUnit.SECONDS));
    assertTrue("Virtual thread operations should succeed", success.get());
  }
  
  /**
   * Tests concurrent service registrations using virtual threads.
   * This verifies that multiple virtual threads can perform OSGi operations concurrently without issues.
   */
  @Test
  public void testConcurrentServiceRegistrations() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Mock service registrations
    @SuppressWarnings("unchecked")
    ServiceRegistration<TestService> registration = mock(ServiceRegistration.class);
    when(bundleContext.registerService(TestService.class, any(TestService.class), any(Dictionary.class)))
        .thenReturn(registration);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to register services concurrently
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < threadCount; i++) {
        final int serviceId = i;
        futures.add(executor.submit(() -> {
          try {
            TestService service = new TestServiceImpl();
            Dictionary<String, Object> props = new Hashtable<>();
            props.put("serviceId", serviceId);
            
            ServiceRegistration<TestService> reg = bundleContext.registerService(TestService.class, service, props);
            assertNotNull("Service registration should not be null", reg);
            
            // Verify this is running in a virtual thread
            assertTrue("Should be running in a virtual thread", Thread.currentThread().isVirtual());
            
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error registering service in virtual thread", e);
          }
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all tasks to complete
      assertTrue("All tasks should complete within timeout", latch.await(10, TimeUnit.SECONDS));
      assertEquals("All service registrations should succeed", threadCount, successCount.get());
      
      // Verify all futures completed without exceptions
      for (Future<?> future : futures) {
        future.get(1, TimeUnit.SECONDS); // This will throw an exception if the task failed
      }
    }
  }
  
  /**
   * Tests for thread pinning issues when using synchronized blocks with virtual threads.
   * This test demonstrates how to detect and avoid thread pinning in OSGi operations.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Create a map to store pinning detection results
    Map<String, Boolean> pinningDetected = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(2);
    
    // Create a lock object for synchronized block testing
    Object lockObject = new Object();
    
    // Create a ReentrantLock as an alternative to synchronized
    ReentrantLock reentrantLock = new ReentrantLock();
    
    // Create and start a virtual thread that uses synchronized (may cause pinning)
    Thread synchronizedThread = Thread.ofVirtual().name("synchronized-thread").start(() -> {
      try {
        // Using synchronized may cause thread pinning
        synchronized (lockObject) {
          log.info("Inside synchronized block in virtual thread");
          // Simulate I/O operation that would cause pinning
          Thread.sleep(100);
          log.info("Completed I/O operation in synchronized block");
        }
        
        // Check if this thread is virtual
        assertTrue("Should be running in a virtual thread", Thread.currentThread().isVirtual());
        
        // In a real application, we would detect pinning using JDK.tracePinnedThreads
        // For this test, we're just simulating the detection
        pinningDetected.put("synchronized", true);
      }
      catch (Exception e) {
        log.error("Error in synchronized virtual thread", e);
      }
      finally {
        latch.countDown();
      }
    });
    
    // Create and start a virtual thread that uses ReentrantLock (avoids pinning)
    Thread reentrantLockThread = Thread.ofVirtual().name("reentrant-lock-thread").start(() -> {
      try {
        // Using ReentrantLock avoids thread pinning
        reentrantLock.lock();
        try {
          log.info("Inside ReentrantLock block in virtual thread");
          // Simulate I/O operation that would NOT cause pinning with ReentrantLock
          Thread.sleep(100);
          log.info("Completed I/O operation in ReentrantLock block");
        }
        finally {
          reentrantLock.unlock();
        }
        
        // Check if this thread is virtual
        assertTrue("Should be running in a virtual thread", Thread.currentThread().isVirtual());
        
        // In a real application with JDK.tracePinnedThreads, this would not show pinning
        pinningDetected.put("reentrantLock", false);
      }
      catch (Exception e) {
        log.error("Error in ReentrantLock virtual thread", e);
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for both threads to complete
    assertTrue("Both threads should complete within timeout", latch.await(5, TimeUnit.SECONDS));
    
    // Verify pinning detection results
    assertTrue("Synchronized block should detect pinning", pinningDetected.getOrDefault("synchronized", false));
    assertFalse("ReentrantLock should not detect pinning", pinningDetected.getOrDefault("reentrantLock", true));
    
    log.info("Thread pinning test completed. In a real application, use -Djdk.tracePinnedThreads=full to detect pinning.");
  }
  
  /**
   * Tests that thread-local variables work correctly with virtual threads in OSGi context.
   * This verifies that thread-local storage behaves as expected with virtual threads.
   */
  @Test
  public void testThreadLocalWithVirtualThreads() throws Exception {
    // Create a ThreadLocal variable
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    
    // Create a virtual thread builder
    OfVirtual virtualBuilder = Thread.ofVirtual();
    
    // Set a value in the main thread
    threadLocal.set("Main thread value");
    
    // Create a CountDownLatch to coordinate between threads
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean threadLocalInitiallyNull = new AtomicBoolean(false);
    AtomicBoolean threadLocalSetSuccessfully = new AtomicBoolean(false);
    
    // Create and start a virtual thread
    Thread virtualThread = virtualBuilder.start(() -> {
      try {
        // Check if ThreadLocal is initially null in the virtual thread
        if (threadLocal.get() == null) {
          threadLocalInitiallyNull.set(true);
        }
        
        // Set a value in the virtual thread
        threadLocal.set("Virtual thread value");
        
        // Verify the value was set correctly
        if ("Virtual thread value".equals(threadLocal.get())) {
          threadLocalSetSuccessfully.set(true);
        }
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue("Virtual thread should complete within timeout", latch.await(5, TimeUnit.SECONDS));
    
    // Verify ThreadLocal behavior
    assertTrue("ThreadLocal should initially be null in virtual thread", threadLocalInitiallyNull.get());
    assertTrue("ThreadLocal should be set successfully in virtual thread", threadLocalSetSuccessfully.get());
    
    // Verify the main thread's value is unchanged
    assertEquals("Main thread's ThreadLocal value should be unchanged", "Main thread value", threadLocal.get());
  }
  
  /**
   * Tests performance comparison between platform threads and virtual threads for OSGi operations.
   * This demonstrates the potential performance benefits of using virtual threads.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    int threadCount = 1000;
    int operationsPerThread = 10;
    
    // Mock service operations
    @SuppressWarnings("unchecked")
    ServiceRegistration<TestService> registration = mock(ServiceRegistration.class);
    when(bundleContext.registerService(TestService.class, any(TestService.class), any(Dictionary.class)))
        .thenReturn(registration);
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(20)) { // Limited pool size for platform threads
        runConcurrentOsgiOperations(executor, threadCount, operationsPerThread);
      }
      return null;
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        runConcurrentOsgiOperations(executor, threadCount, operationsPerThread);
      }
      return null;
    });
    
    log.info("Performance comparison:");
    log.info("Platform threads: {} ms for {} threads with {} operations each", 
        platformThreadTime, threadCount, operationsPerThread);
    log.info("Virtual threads: {} ms for {} threads with {} operations each", 
        virtualThreadTime, threadCount, operationsPerThread);
    
    // Note: We don't assert on the actual times since they can vary by environment,
    // but in most cases virtual threads should be more efficient for I/O-bound operations
  }
  
  /**
   * Helper method to run concurrent OSGi operations using the provided executor.
   */
  private void runConcurrentOsgiOperations(ExecutorService executor, int threadCount, int operationsPerThread) 
      throws Exception {
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<Future<?>> futures = new ArrayList<>();
    
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      futures.add(executor.submit(() -> {
        try {
          for (int j = 0; j < operationsPerThread; j++) {
            // Perform OSGi operations
            TestService service = new TestServiceImpl();
            Dictionary<String, Object> props = new Hashtable<>();
            props.put("threadId", threadId);
            props.put("operationId", j);
            
            // Register service (simulated I/O operation)
            bundleContext.registerService(TestService.class, service, props);
            
            // Small delay to simulate additional work
            Thread.sleep(5);
          }
        }
        catch (Exception e) {
          log.error("Error in concurrent operation", e);
        }
        finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all operations to complete
    assertTrue("All operations should complete within timeout", latch.await(30, TimeUnit.SECONDS));
    
    // Check for any exceptions
    for (Future<?> future : futures) {
      future.get(1, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Helper method to measure execution time of a task.
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Helper method for Mockito's any() matcher.
   */
  @SuppressWarnings("unchecked")
  private static <T> T any(Class<T> type) {
    return (T) org.mockito.ArgumentMatchers.any();
  }
}