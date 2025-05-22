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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.jmx.ObjectNameEntry;
import org.sonatype.nexus.jmx.reflect.ManagedAttribute;
import org.sonatype.nexus.jmx.reflect.ManagedObject;
import org.sonatype.nexus.jmx.reflect.ManagedOperation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests that MBean attributes and operations function correctly when accessed through Virtual Threads.
 * This test ensures that descriptor reflection logic, attribute access, and operation invocation
 * maintain correctness when executed concurrently through thousands of Virtual Threads.
 */
public class VirtualThreadMBeanTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  private MBeanServer mbeanServer;
  private ObjectName objectName;
  private ExecutorService executor;
  
  /**
   * Test MBean interface defining attributes and operations to be tested with Virtual Threads.
   */
  public interface TestVirtualThreadMBean
  {
    String getName();
    
    void setName(String name);
    
    int getCounter();
    
    int increment();
    
    void reset();
  }
  
  /**
   * Implementation of the TestVirtualThreadMBean interface with JMX annotations.
   */
  @Named
  @Singleton
  @ManagedObject(domain = "org.sonatype.nexus.virtualthread", 
      entries = {@ObjectNameEntry(name = "test", value = "virtualthread")},
      description = "Test MBean for Virtual Thread access")
  public static class TestVirtualThreadMBeanImpl implements TestVirtualThreadMBean
  {
    private String name = "default";
    private final AtomicInteger counter = new AtomicInteger(0);
    
    @Override
    @ManagedAttribute(description = "Get the name attribute")
    public String getName() {
      return name;
    }
    
    @Override
    @ManagedAttribute(description = "Set the name attribute")
    public void setName(String name) {
      this.name = name;
    }
    
    @Override
    @ManagedAttribute(description = "Get the current counter value")
    public int getCounter() {
      return counter.get();
    }
    
    @Override
    @ManagedOperation(description = "Increment the counter and return the new value")
    public int increment() {
      return counter.incrementAndGet();
    }
    
    @Override
    @ManagedOperation(description = "Reset the counter to zero")
    public void reset() {
      counter.set(0);
    }
  }
  
  @Before
  public void setUp() throws Exception {
    // Create the test MBean instance
    TestVirtualThreadMBean mbean = new TestVirtualThreadMBeanImpl();
    
    // Get the platform MBean server
    mbeanServer = ManagementFactory.getPlatformMBeanServer();
    
    // Create the ObjectName for our test MBean
    objectName = new ObjectName("org.sonatype.nexus.virtualthread:test=virtualthread");
    
    // Register the MBean if it's not already registered
    if (!mbeanServer.isRegistered(objectName)) {
      mbeanServer.registerMBean(mbean, objectName);
    }
    
    // Create a virtual thread executor
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @After
  public void tearDown() throws Exception {
    // Shutdown the executor
    if (executor != null) {
      executor.shutdown();
      executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    // Unregister the MBean
    if (mbeanServer != null && objectName != null && mbeanServer.isRegistered(objectName)) {
      mbeanServer.unregisterMBean(objectName);
    }
  }
  
  /**
   * Tests that MBean attribute getters can be accessed concurrently from multiple Virtual Threads.
   */
  @Test
  public void testAttributeGetterWithVirtualThreads() throws Exception {
    // Set initial name value
    mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", "initialValue"));
    
    // Create a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create tasks to get the attribute value
    List<Future<String>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executor.submit(() -> {
        // Wait for all threads to be ready
        startLatch.await();
        // Get the attribute value
        return (String) mbeanServer.getAttribute(objectName, "Name");
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Verify all threads got the correct value
    for (Future<String> future : futures) {
      assertThat(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), is("initialValue"));
    }
  }
  
  /**
   * Tests that MBean attribute setters can be accessed concurrently from multiple Virtual Threads.
   */
  @Test
  public void testAttributeSetterWithVirtualThreads() throws Exception {
    // Reset the counter
    mbeanServer.invoke(objectName, "reset", null, null);
    
    // Create a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create tasks to set the attribute value
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      futures.add(executor.submit(() -> {
        // Wait for all threads to be ready
        startLatch.await();
        // Set the attribute value
        mbeanServer.setAttribute(objectName, new javax.management.Attribute("Name", "value-" + index));
        return null;
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    for (Future<?> future : futures) {
      future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    // Verify the attribute was set (we don't know which thread's value will win, but it should be one of them)
    String finalValue = (String) mbeanServer.getAttribute(objectName, "Name");
    assertThat(finalValue, notNullValue());
    assertThat(finalValue.startsWith("value-"), is(true));
  }
  
  /**
   * Tests that MBean operations can be invoked concurrently from multiple Virtual Threads.
   */
  @Test
  public void testOperationInvocationWithVirtualThreads() throws Exception {
    // Reset the counter
    mbeanServer.invoke(objectName, "reset", null, null);
    
    // Create a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create tasks to invoke the increment operation
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executor.submit(() -> {
        // Wait for all threads to be ready
        startLatch.await();
        // Invoke the increment operation
        return (Integer) mbeanServer.invoke(objectName, "increment", null, null);
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    for (Future<Integer> future : futures) {
      future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    // Verify the counter was incremented by all threads
    int finalCount = (Integer) mbeanServer.getAttribute(objectName, "Counter");
    assertThat(finalCount, equalTo(THREAD_COUNT));
  }
  
  /**
   * Tests that MBean descriptor reflection logic works correctly when invoked from Virtual Threads.
   */
  @Test
  public void testMBeanInfoWithVirtualThreads() throws Exception {
    // Create a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create tasks to get MBean info
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(executor.submit(() -> {
        // Wait for all threads to be ready
        startLatch.await();
        // Get MBean info
        mbeanServer.getMBeanInfo(objectName);
        return null;
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    for (Future<?> future : futures) {
      future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    // If we got here without exceptions, the test passed
  }
}