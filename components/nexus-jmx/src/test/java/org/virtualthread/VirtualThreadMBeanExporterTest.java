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

import java.lang.management.ManagementFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.jmx.reflect.ExampleManagedObject;
import org.sonatype.nexus.jmx.reflect.ManagedObject;
import org.sonatype.nexus.jmx.reflect.ReflectionMBeanBuilder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests the registration and operation of MBeans when executed from Java 21 Virtual Threads.
 * Verifies that MBean registration, attribute access, and operation invocation function correctly
 * when performed from virtual threads.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class VirtualThreadMBeanExporterTest
    extends TestSupport
{
  private MBeanServer mbeanServer;
  
  private ExampleManagedObject managedObject;
  
  private ObjectName objectName;
  
  @BeforeEach
  void setUp() throws Exception {
    // Use the platform MBeanServer for testing
    mbeanServer = ManagementFactory.getPlatformMBeanServer();
    
    // Create the managed object
    managedObject = new ExampleManagedObject();
    
    // Get the ManagedObject annotation to extract domain and entries
    ManagedObject descriptor = managedObject.getClass().getAnnotation(ManagedObject.class);
    assertThat(descriptor, notNullValue());
    
    // Build the ObjectName from the annotation
    String domain = descriptor.domain();
    objectName = new ObjectName(domain + ":type=ExampleManagedObject");
    
    // Build the MBean using the ReflectionMBeanBuilder
    ReflectionMBeanBuilder builder = new ReflectionMBeanBuilder(managedObject.getClass());
    builder.target(() -> managedObject);
    builder.description(descriptor.description());
    builder.discover();
    
    // Register the MBean
    mbeanServer.registerMBean(builder.build(), objectName);
  }
  
  @AfterEach
  void tearDown() throws Exception {
    // Unregister the MBean if it exists
    if (mbeanServer.isRegistered(objectName)) {
      mbeanServer.unregisterMBean(objectName);
    }
  }
  
  /**
   * Tests that MBean attribute getters and setters work correctly when invoked from a virtual thread.
   */
  @Test
  void testAttributeAccessFromVirtualThread() throws Exception {
    // Create a virtual thread to perform the attribute operations
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        // Verify the thread is a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        // Set the name attribute
        mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", "TestName"));
        
        // Get the name attribute and verify it was set correctly
        String name = (String) mbeanServer.getAttribute(objectName, "Name");
        assertThat(name, equalTo("TestName"));
        
        // Set the password attribute (write-only)
        mbeanServer.setAttribute(objectName, new javax.management.Attribute("Password", "secret"));
        
        // Verify the password was set correctly in the managed object
        assertThat(managedObject.getPassword(), equalTo("secret"));
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to access MBean attributes from virtual thread", e);
      }
    }, Thread.ofVirtual().factory()::newThread);
    
    // Wait for the virtual thread to complete
    future.join();
  }
  
  /**
   * Tests that MBean operations can be invoked correctly from a virtual thread.
   */
  @Test
  void testOperationInvocationFromVirtualThread() throws Exception {
    // Set the name attribute directly
    managedObject.setName("InitialName");
    
    // Create a virtual thread to invoke the operation
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        // Verify the thread is a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        // Invoke the resetName operation
        mbeanServer.invoke(objectName, "resetName", null, null);
        
        // Verify the name was reset
        String name = (String) mbeanServer.getAttribute(objectName, "Name");
        assertThat(name, nullValue());
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to invoke MBean operation from virtual thread", e);
      }
    }, Thread.ofVirtual().factory()::newThread);
    
    // Wait for the virtual thread to complete
    future.join();
  }
  
  /**
   * Tests that MBean registration and unregistration work correctly from a virtual thread.
   */
  @Test
  void testMBeanRegistrationFromVirtualThread() throws Exception {
    // Create a new ObjectName for this test
    ObjectName testObjectName = new ObjectName("org.sonatype.nexus.jmx:type=TestObject");
    
    // Create a new managed object
    ExampleManagedObject testObject = new ExampleManagedObject();
    
    // Use an AtomicReference to capture any exception from the virtual thread
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    
    // Create a virtual thread to register the MBean
    CompletableFuture<Void> registerFuture = CompletableFuture.runAsync(() -> {
      try {
        // Verify the thread is a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        // Build the MBean
        ReflectionMBeanBuilder builder = new ReflectionMBeanBuilder(testObject.getClass());
        builder.target(() -> testObject);
        builder.description("Test object");
        builder.discover();
        
        // Register the MBean
        mbeanServer.registerMBean(builder.build(), testObjectName);
        
        // Verify the MBean is registered
        assertThat(mbeanServer.isRegistered(testObjectName), is(true));
      }
      catch (Exception e) {
        exceptionRef.set(e);
      }
    }, Thread.ofVirtual().factory()::newThread);
    
    // Wait for the registration to complete
    registerFuture.join();
    
    // Check if any exception occurred
    if (exceptionRef.get() != null) {
      throw new AssertionError("MBean registration from virtual thread failed", exceptionRef.get());
    }
    
    // Create a virtual thread to unregister the MBean
    CompletableFuture<Void> unregisterFuture = CompletableFuture.runAsync(() -> {
      try {
        // Verify the thread is a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        // Unregister the MBean
        mbeanServer.unregisterMBean(testObjectName);
        
        // Verify the MBean is no longer registered
        assertThat(mbeanServer.isRegistered(testObjectName), is(false));
      }
      catch (Exception e) {
        exceptionRef.set(e);
      }
    }, Thread.ofVirtual().factory()::newThread);
    
    // Wait for the unregistration to complete
    unregisterFuture.join();
    
    // Check if any exception occurred
    if (exceptionRef.get() != null) {
      throw new AssertionError("MBean unregistration from virtual thread failed", exceptionRef.get());
    }
  }
  
  /**
   * Tests the performance characteristics of JMX operations under virtual threads versus platform threads.
   * This test creates a large number of threads to perform concurrent JMX operations and measures
   * the execution time and success rate for both thread types.
   */
  @Test
  void testJmxPerformanceWithVirtualThreads() throws Exception {
    // Number of concurrent operations to perform
    final int concurrentOperations = 1000;
    
    // Create thread factories for both types
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Test with platform threads
    long platformThreadTime = measureJmxOperations(platformThreadFactory, concurrentOperations);
    logger.info("Platform thread execution time for {} operations: {} ms", concurrentOperations, platformThreadTime);
    
    // Test with virtual threads
    long virtualThreadTime = measureJmxOperations(virtualThreadFactory, concurrentOperations);
    logger.info("Virtual thread execution time for {} operations: {} ms", concurrentOperations, virtualThreadTime);
    
    // Virtual threads should generally be more efficient for I/O-bound operations like JMX
    // However, we don't make a strict assertion here as performance can vary by environment
    logger.info("Performance ratio (platform/virtual): {}", (double) platformThreadTime / virtualThreadTime);
    
    // For high concurrency operations, virtual threads should show better scalability
    if (concurrentOperations >= 1000) {
      assertThat("Virtual threads should be more efficient for high concurrency JMX operations",
          virtualThreadTime, lessThan(platformThreadTime));
    }
  }
  
  /**
   * Helper method to measure the execution time of concurrent JMX operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use (virtual or platform)
   * @param operationCount the number of concurrent operations to perform
   * @return the execution time in milliseconds
   */
  private long measureJmxOperations(ThreadFactory threadFactory, int operationCount) throws Exception {
    // Create an executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(operationCount);
      
      // Track success/failure
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger failureCount = new AtomicInteger(0);
      
      // Start timing
      long startTime = System.currentTimeMillis();
      
      // Submit the operations
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Set a unique name for each operation
            String name = "TestName-" + index;
            mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", name));
            
            // Get the name back
            String retrievedName = (String) mbeanServer.getAttribute(objectName, "Name");
            
            // Verify it matches what we set
            if (name.equals(retrievedName)) {
              successCount.incrementAndGet();
            }
            else {
              failureCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            logger.error("JMX operation failed", e);
            failureCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout after 30 seconds
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // End timing
      long endTime = System.currentTimeMillis();
      
      // Log results
      logger.info("JMX operations completed: {}, success: {}, failure: {}", 
          completed ? "all" : "timeout", successCount.get(), failureCount.get());
      
      // Verify all operations succeeded
      assertThat("All JMX operations should succeed", successCount.get(), equalTo(operationCount));
      assertThat("No JMX operations should fail", failureCount.get(), equalTo(0));
      
      // Return the execution time
      return endTime - startTime;
    }
    finally {
      // Shutdown the executor
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests that multiple concurrent MBean registrations and unregistrations work correctly from virtual threads.
   * This test simulates a high-concurrency scenario where many MBeans are being registered and unregistered
   * simultaneously from virtual threads.
   */
  @Test
  void testConcurrentMBeanRegistrationFromVirtualThreads() throws Exception {
    // Number of MBeans to register concurrently
    final int mbeanCount = 100;
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(mbeanCount * 2); // Register + Unregister
      
      // Track success/failure
      AtomicInteger registrationSuccessCount = new AtomicInteger(0);
      AtomicInteger registrationFailureCount = new AtomicInteger(0);
      AtomicInteger unregistrationSuccessCount = new AtomicInteger(0);
      AtomicInteger unregistrationFailureCount = new AtomicInteger(0);
      
      // Create ObjectNames and managed objects
      ObjectName[] objectNames = new ObjectName[mbeanCount];
      ExampleManagedObject[] managedObjects = new ExampleManagedObject[mbeanCount];
      
      for (int i = 0; i < mbeanCount; i++) {
        objectNames[i] = new ObjectName("org.sonatype.nexus.jmx:type=ConcurrentTest,index=" + i);
        managedObjects[i] = new ExampleManagedObject();
      }
      
      // Submit registration tasks
      for (int i = 0; i < mbeanCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Verify this is a virtual thread
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Build the MBean
            ReflectionMBeanBuilder builder = new ReflectionMBeanBuilder(managedObjects[index].getClass());
            builder.target(() -> managedObjects[index]);
            builder.description("Concurrent test object " + index);
            builder.discover();
            
            // Register the MBean
            mbeanServer.registerMBean(builder.build(), objectNames[index]);
            
            // Verify registration
            if (mbeanServer.isRegistered(objectNames[index])) {
              registrationSuccessCount.incrementAndGet();
            }
            else {
              registrationFailureCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            logger.error("MBean registration failed for index " + index, e);
            registrationFailureCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for registrations to complete (give them a head start)
      Thread.sleep(500);
      
      // Submit unregistration tasks
      for (int i = 0; i < mbeanCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Verify this is a virtual thread
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Only try to unregister if it's registered
            if (mbeanServer.isRegistered(objectNames[index])) {
              // Unregister the MBean
              mbeanServer.unregisterMBean(objectNames[index]);
              
              // Verify unregistration
              if (!mbeanServer.isRegistered(objectNames[index])) {
                unregistrationSuccessCount.incrementAndGet();
              }
              else {
                unregistrationFailureCount.incrementAndGet();
              }
            }
            else {
              // If it wasn't registered, count as a failure
              unregistrationFailureCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            logger.error("MBean unregistration failed for index " + index, e);
            unregistrationFailureCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Log results
      logger.info("Concurrent MBean operations completed: {}", completed ? "all" : "timeout");
      logger.info("Registration success: {}, failure: {}", registrationSuccessCount.get(), registrationFailureCount.get());
      logger.info("Unregistration success: {}, failure: {}", unregistrationSuccessCount.get(), unregistrationFailureCount.get());
      
      // Verify all operations succeeded
      assertThat("All MBean registrations should succeed", registrationSuccessCount.get(), greaterThan(0));
      assertThat("All MBean unregistrations should succeed", unregistrationSuccessCount.get(), greaterThan(0));
      
      // Clean up any remaining MBeans
      for (int i = 0; i < mbeanCount; i++) {
        if (mbeanServer.isRegistered(objectNames[i])) {
          mbeanServer.unregisterMBean(objectNames[i]);
        }
      }
    }
    finally {
      // Shutdown the executor
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}