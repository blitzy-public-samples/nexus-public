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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.jmx.reflect.ExampleManagedObject;
import org.sonatype.nexus.jmx.reflect.ManagedObject;
import org.sonatype.nexus.jmx.reflect.ReflectionMBeanBuilder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests the registration and operation of MBeans when executed from Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class VirtualThreadMBeanExporterTest
    extends TestSupport
{
  private MBeanServer mbeanServer;
  private ObjectName objectName;
  private ExampleManagedObject managedObject;

  @Before
  public void setUp() throws Exception {
    mbeanServer = ManagementFactory.getPlatformMBeanServer();
    managedObject = new ExampleManagedObject();
    
    // Create the ObjectName based on the @ManagedObject annotation
    ManagedObject mo = ExampleManagedObject.class.getAnnotation(ManagedObject.class);
    objectName = new ObjectName(mo.domain() + ":foo=bar");
  }

  @After
  public void tearDown() throws Exception {
    // Unregister the MBean if it exists
    if (mbeanServer.isRegistered(objectName)) {
      mbeanServer.unregisterMBean(objectName);
    }
  }

  /**
   * Tests that an MBean can be registered from a virtual thread.
   */
  @Test
  public void testRegisterMBeanFromVirtualThread() throws Exception {
    AtomicReference<Exception> exception = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Use Java 21 Virtual Thread to register the MBean
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          log.info("Registering MBean from virtual thread: {}", Thread.currentThread());
          
          // Build and register the MBean
          ReflectionMBeanBuilder builder = new ReflectionMBeanBuilder(ExampleManagedObject.class);
          builder.target(() -> managedObject);
          builder.build();
          
          mbeanServer.registerMBean(builder.build(), objectName);
        }
        catch (Exception e) {
          exception.set(e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Check for exceptions
    if (exception.get() != null) {
      throw exception.get();
    }
    
    // Verify the MBean was registered
    assertThat(mbeanServer.isRegistered(objectName), is(true));
  }

  /**
   * Tests that MBean attributes can be accessed from a virtual thread.
   */
  @Test
  public void testMBeanAttributesFromVirtualThread() throws Exception {
    // Register the MBean directly
    ReflectionMBeanBuilder builder = new ReflectionMBeanBuilder(ExampleManagedObject.class);
    builder.target(() -> managedObject);
    mbeanServer.registerMBean(builder.build(), objectName);
    
    AtomicReference<Exception> exception = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Use Java 21 Virtual Thread to access MBean attributes
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          log.info("Accessing MBean attributes from virtual thread: {}", Thread.currentThread());
          
          // Test getting attribute (initially null)
          String name = (String) mbeanServer.getAttribute(objectName, "Name");
          assertThat(name, is(nullValue()));
          
          // Test setting attribute
          mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", "TestName"));
          
          // Test getting attribute after setting
          name = (String) mbeanServer.getAttribute(objectName, "Name");
          assertThat(name, is(equalTo("TestName")));
          
          // Test write-only attribute
          mbeanServer.setAttribute(objectName, new javax.management.Attribute("Password", "secret"));
          assertThat(managedObject.getPassword(), is(equalTo("secret")));
        }
        catch (Exception e) {
          exception.set(e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Check for exceptions
    if (exception.get() != null) {
      throw exception.get();
    }
  }

  /**
   * Tests that MBean operations can be invoked from a virtual thread.
   */
  @Test
  public void testInvokeOperationFromVirtualThread() throws Exception {
    // Register the MBean directly
    ReflectionMBeanBuilder builder = new ReflectionMBeanBuilder(ExampleManagedObject.class);
    builder.target(() -> managedObject);
    mbeanServer.registerMBean(builder.build(), objectName);
    
    // Set a name value to verify reset operation
    managedObject.setName("NameToReset");
    
    AtomicReference<Exception> exception = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Use Java 21 Virtual Thread to invoke MBean operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          log.info("Invoking MBean operation from virtual thread: {}", Thread.currentThread());
          
          // Verify name is set before operation
          String name = (String) mbeanServer.getAttribute(objectName, "Name");
          assertThat(name, is(equalTo("NameToReset")));
          
          // Invoke the resetName operation
          mbeanServer.invoke(objectName, "resetName", new Object[0], new String[0]);
          
          // Verify name was reset
          name = (String) mbeanServer.getAttribute(objectName, "Name");
          assertThat(name, is(nullValue()));
        }
        catch (Exception e) {
          exception.set(e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Check for exceptions
    if (exception.get() != null) {
      throw exception.get();
    }
  }

  /**
   * Tests that an MBean can be unregistered from a virtual thread.
   */
  @Test
  public void testUnregisterMBeanFromVirtualThread() throws Exception {
    // Register the MBean directly
    ReflectionMBeanBuilder builder = new ReflectionMBeanBuilder(ExampleManagedObject.class);
    builder.target(() -> managedObject);
    mbeanServer.registerMBean(builder.build(), objectName);
    
    // Verify it's registered
    assertThat(mbeanServer.isRegistered(objectName), is(true));
    
    AtomicReference<Exception> exception = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Use Java 21 Virtual Thread to unregister the MBean
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          log.info("Unregistering MBean from virtual thread: {}", Thread.currentThread());
          mbeanServer.unregisterMBean(objectName);
        }
        catch (Exception e) {
          exception.set(e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Check for exceptions
    if (exception.get() != null) {
      throw exception.get();
    }
    
    // Verify the MBean was unregistered
    assertThat(mbeanServer.isRegistered(objectName), is(false));
  }

  /**
   * Tests the performance characteristics of JMX operations under virtual threads versus platform threads.
   * This is a simple benchmark to demonstrate that virtual threads can handle many concurrent JMX operations
   * with minimal overhead.
   */
  @Test
  public void testConcurrentJmxOperationsPerformance() throws Exception {
    // Number of concurrent operations to perform
    final int concurrentOperations = 100;
    
    // Register the MBean directly
    ReflectionMBeanBuilder builder = new ReflectionMBeanBuilder(ExampleManagedObject.class);
    builder.target(() -> managedObject);
    mbeanServer.registerMBean(builder.build(), objectName);
    
    // Test with platform threads
    CountDownLatch platformLatch = new CountDownLatch(concurrentOperations);
    long platformStart = System.currentTimeMillis();
    
    try (var executor = Executors.newFixedThreadPool(20)) { // Limited thread pool
      for (int i = 0; i < concurrentOperations; i++) {
        executor.submit(() -> {
          try {
            // Perform a mix of JMX operations
            mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", "Thread" + Thread.currentThread().threadId()));
            mbeanServer.getAttribute(objectName, "Name");
            mbeanServer.invoke(objectName, "resetName", new Object[0], new String[0]);
          }
          catch (Exception e) {
            log.error("Error in platform thread JMX operation", e);
          }
          finally {
            platformLatch.countDown();
          }
        });
      }
    }
    
    platformLatch.await(10, TimeUnit.SECONDS);
    long platformDuration = System.currentTimeMillis() - platformStart;
    
    // Test with virtual threads
    CountDownLatch virtualLatch = new CountDownLatch(concurrentOperations);
    long virtualStart = System.currentTimeMillis();
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrentOperations; i++) {
        executor.submit(() -> {
          try {
            // Perform the same mix of JMX operations
            mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", "VThread" + Thread.currentThread().threadId()));
            mbeanServer.getAttribute(objectName, "Name");
            mbeanServer.invoke(objectName, "resetName", new Object[0], new String[0]);
          }
          catch (Exception e) {
            log.error("Error in virtual thread JMX operation", e);
          }
          finally {
            virtualLatch.countDown();
          }
        });
      }
    }
    
    virtualLatch.await(10, TimeUnit.SECONDS);
    long virtualDuration = System.currentTimeMillis() - virtualStart;
    
    log.info("Platform threads: {} operations in {} ms", concurrentOperations, platformDuration);
    log.info("Virtual threads: {} operations in {} ms", concurrentOperations, virtualDuration);
    
    // We don't assert on specific timings as they can vary by environment,
    // but we log the results for analysis
  }
}