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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.Attribute;
import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that MBean attributes and operations function correctly when accessed through Virtual Threads.
 * 
 * This test ensures that descriptor reflection logic, attribute access, and operation invocation
 * maintain correctness when executed concurrently through thousands of Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(Java21TestGroup.class)
public class VirtualThreadMBeanTest
    extends TestSupport
{
  private static final String DOMAIN = "org.sonatype.nexus.virtualthread";
  
  private static final int THREAD_COUNT = 1000;
  
  private static final int OPERATIONS_PER_THREAD = 10;
  
  private MBeanServer mbeanServer;
  
  private ObjectName testBeanName;
  
  private TestMBean proxy;
  
  private ExecutorService executor;

  @BeforeEach
  void setUp() throws Exception {
    mbeanServer = ManagementFactory.getPlatformMBeanServer();
    
    // Register the test MBean
    testBeanName = new ObjectName(DOMAIN + ":type=TestBean");
    TestMBeanImpl testBean = new TestMBeanImpl();
    mbeanServer.registerMBean(testBean, testBeanName);
    
    // Create a proxy for the MBean
    proxy = JMX.newMBeanProxy(mbeanServer, testBeanName, TestMBean.class);
    
    // Create a virtual thread executor
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (executor != null) {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (testBeanName != null && mbeanServer.isRegistered(testBeanName)) {
      mbeanServer.unregisterMBean(testBeanName);
    }
  }
  
  @Test
  void testMBeanAttributeAccessWithVirtualThreads() throws Exception {
    // Set initial value
    proxy.setValue("initial");
    assertEquals("initial", proxy.getValue());
    
    // Create a map to track values set by each thread
    ConcurrentHashMap<Integer, String> expectedValues = new ConcurrentHashMap<>();
    
    // Create a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Submit tasks to the executor
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      futures.add(executor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();
          
          // Set a unique value for this thread
          String newValue = "value-" + threadId;
          expectedValues.put(threadId, newValue);
          
          // Set the value through the MBean
          proxy.setValue(newValue);
          
          // Get the value and verify it matches what was set
          String retrievedValue = proxy.getValue();
          assertNotNull(retrievedValue);
          assertTrue(retrievedValue.startsWith("value-"), "Value should start with 'value-'");
        }
        catch (Exception e) {
          log.error("Error in virtual thread", e);
          throw new RuntimeException(e);
        }
        finally {
          completionLatch.countDown();
        }
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(30, TimeUnit.SECONDS), "Not all threads completed in time");
    
    // Verify all futures completed without exceptions
    for (Future<?> future : futures) {
      assertDoesNotThrow(() -> future.get());
    }
    
    // Verify the final value is one of the expected values
    String finalValue = proxy.getValue();
    assertTrue(finalValue.startsWith("value-"), "Final value should start with 'value-'");
    
    // Extract the thread ID from the final value
    int threadId = Integer.parseInt(finalValue.substring("value-".length()));
    assertEquals(expectedValues.get(threadId), finalValue, "Final value should match the expected value for that thread ID");
  }
  
  @Test
  void testMBeanOperationInvocationWithVirtualThreads() throws Exception {
    // Create a counter to track successful operations
    AtomicInteger successCounter = new AtomicInteger(0);
    
    // Create a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a latch to wait for all operations to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT * OPERATIONS_PER_THREAD);
    
    // Submit tasks to the executor
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      futures.add(executor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();
          
          // Perform multiple operations per thread
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            // Invoke the operation with a unique parameter
            String param = "thread-" + threadId + "-op-" + j;
            String result = proxy.performOperation(param);
            
            // Verify the result
            assertEquals("Processed: " + param, result, "Operation result should match expected format");
            
            // Increment success counter
            successCounter.incrementAndGet();
            
            // Signal completion of this operation
            completionLatch.countDown();
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread", e);
          throw new RuntimeException(e);
        }
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete
    assertTrue(completionLatch.await(30, TimeUnit.SECONDS), "Not all operations completed in time");
    
    // Verify all futures completed without exceptions
    for (Future<?> future : futures) {
      assertDoesNotThrow(() -> future.get());
    }
    
    // Verify all operations were successful
    assertThat(successCounter.get(), is(equalTo(THREAD_COUNT * OPERATIONS_PER_THREAD)));
    
    // Verify the operation count in the MBean
    assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, proxy.getOperationCount(), 
        "Operation count in MBean should match expected total");
  }
  
  @Test
  void testMBeanAttributeModificationWithVirtualThreads() throws Exception {
    // Set initial value
    mbeanServer.setAttribute(testBeanName, new Attribute("Value", "initial"));
    
    // Create a latch to synchronize thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    // Submit tasks to the executor
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      futures.add(executor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();
          
          // Set a unique value for this thread using direct MBeanServer access
          String newValue = "direct-" + threadId;
          mbeanServer.setAttribute(testBeanName, new Attribute("Value", newValue));
          
          // Get the value and verify it's a valid value
          String retrievedValue = (String) mbeanServer.getAttribute(testBeanName, "Value");
          assertNotNull(retrievedValue);
          assertTrue(retrievedValue.startsWith("direct-"), "Value should start with 'direct-'");
        }
        catch (Exception e) {
          log.error("Error in virtual thread", e);
          throw new RuntimeException(e);
        }
        finally {
          completionLatch.countDown();
        }
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(30, TimeUnit.SECONDS), "Not all threads completed in time");
    
    // Verify all futures completed without exceptions
    for (Future<?> future : futures) {
      assertDoesNotThrow(() -> future.get());
    }
    
    // Verify the final value is one of the expected values
    String finalValue = (String) mbeanServer.getAttribute(testBeanName, "Value");
    assertTrue(finalValue.startsWith("direct-"), "Final value should start with 'direct-'");
  }
  
  /**
   * MBean interface for testing.
   */
  public interface TestMBean {
    String getValue();
    void setValue(String value);
    String performOperation(String param);
    int getOperationCount();
  }
  
  /**
   * MBean implementation for testing.
   */
  public static class TestMBeanImpl implements TestMBean {
    private String value;
    private final AtomicInteger operationCount = new AtomicInteger(0);
    
    @Override
    public String getValue() {
      return value;
    }
    
    @Override
    public void setValue(String value) {
      this.value = value;
    }
    
    @Override
    public String performOperation(String param) {
      operationCount.incrementAndGet();
      return "Processed: " + param;
    }
    
    @Override
    public int getOperationCount() {
      return operationCount.get();
    }
  }
}