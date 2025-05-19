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

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.cooperation2.Cooperation2;
import org.sonatype.nexus.common.cooperation2.datastore.DefaultCooperation2Factory;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link DefaultCooperation2Factory} with Virtual Thread support.
 * 
 * <p>These tests verify that the cooperation framework correctly leverages Java 21 Virtual Threads
 * for I/O-bound operations, ensuring proper context propagation and thread management.</p>
 * 
 * <p>Note: These tests will be skipped on Java versions prior to Java 21 that don't support Virtual Threads.</p>
 */
public class VirtualThreadCooperation2Test
    extends TestSupport
{
  private DefaultCooperation2Factory factory;
  
  private boolean virtualThreadSupported;

  @Before
  public void setUp() {
    factory = new DefaultCooperation2Factory();
    
    // Check if Virtual Threads are supported in the current JVM
    try {
      Thread.class.getMethod("ofVirtual");
      virtualThreadSupported = true;
    } catch (NoSuchMethodException e) {
      virtualThreadSupported = false;
    }
  }

  /**
   * Tests that I/O-bound operations are executed using Virtual Threads when available.
   */
  @Test
  public void testVirtualThreadExecution() throws Exception {
    if (!virtualThreadSupported) {
      log.info("Skipping test as Virtual Threads are not supported in this JVM");
      return;
    }
    
    // Configure cooperation with Virtual Thread support
    Cooperation2 cooperation = factory.configure()
        .majorTimeout(Duration.ofSeconds(10))
        .minorTimeout(Duration.ofSeconds(5))
        .useVirtualThreads(true)
        .build("test-virtual-thread");
    
    // Track whether the operation was executed in a Virtual Thread
    AtomicBoolean wasVirtualThread = new AtomicBoolean(false);
    
    // Execute an I/O-bound operation
    String result = cooperation.on(() -> {
      // Simulate I/O by sleeping
      Thread.sleep(100);
      
      // Check if we're running in a Virtual Thread
      wasVirtualThread.set(Thread.currentThread().isVirtual());
      
      return "success";
    })
    .useVirtualThread(true)
    .cooperate("test-operation");
    
    // Verify the operation was successful and ran in a Virtual Thread
    assertThat(result, is("success"));
    assertThat("Operation should have executed in a Virtual Thread", wasVirtualThread.get(), is(true));
  }

  /**
   * Tests that context is properly propagated to Virtual Threads.
   */
  @Test
  public void testContextPropagation() throws Exception {
    if (!virtualThreadSupported) {
      log.info("Skipping test as Virtual Threads are not supported in this JVM");
      return;
    }
    
    // Configure cooperation with Virtual Thread support
    Cooperation2 cooperation = factory.configure()
        .majorTimeout(Duration.ofSeconds(10))
        .minorTimeout(Duration.ofSeconds(5))
        .useVirtualThreads(true)
        .build("test-context-propagation");
    
    // Create a ThreadLocal variable to test context propagation
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("test-context");
    
    // Track the value of the ThreadLocal in the Virtual Thread
    AtomicReference<String> contextInVirtualThread = new AtomicReference<>();
    
    // Execute an operation that accesses the ThreadLocal
    String result = cooperation.on(() -> {
      // Capture the ThreadLocal value in the Virtual Thread
      contextInVirtualThread.set(threadLocal.get());
      return "success";
    })
    .useVirtualThread(true)
    .propagateContext(true)
    .cooperate("test-context-operation");
    
    // Verify the operation was successful and the context was propagated
    assertThat(result, is("success"));
    assertThat("Context should have been propagated to Virtual Thread", 
        contextInVirtualThread.get(), is("test-context"));
  }

  /**
   * Tests that multiple threads can cooperate when using Virtual Threads.
   */
  @Test
  public void testCooperationWithVirtualThreads() throws Exception {
    if (!virtualThreadSupported) {
      log.info("Skipping test as Virtual Threads are not supported in this JVM");
      return;
    }
    
    // Configure cooperation with Virtual Thread support
    Cooperation2 cooperation = factory.configure()
        .majorTimeout(Duration.ofSeconds(10))
        .minorTimeout(Duration.ofSeconds(5))
        .useVirtualThreads(true)
        .build("test-cooperation");
    
    // Track how many times the work function is called
    AtomicBoolean workPerformed = new AtomicBoolean(false);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(2);
    
    // Create a work function that can be used to verify cooperation
    Runnable cooperativeTask = () -> {
      try {
        // Wait for all threads to be ready
        startLatch.await(5, TimeUnit.SECONDS);
        
        // Execute the cooperative operation
        String result = cooperation.on(() -> {
          // Only one thread should execute this
          if (workPerformed.compareAndSet(false, true)) {
            // Simulate work by sleeping
            Thread.sleep(200);
            return "work-done";
          }
          throw new AssertionError("Work function called multiple times");
        })
        .useVirtualThread(true)
        .cooperate("test-cooperative-operation");
        
        // Verify the result
        assertThat(result, is("work-done"));
        
        // Signal completion
        completionLatch.countDown();
      }
      catch (Exception e) {
        log.error("Error in cooperative task", e);
      }
    };
    
    // Start two threads that will cooperate
    Thread thread1 = new Thread(cooperativeTask);
    Thread thread2 = new Thread(cooperativeTask);
    thread1.start();
    thread2.start();
    
    // Allow the threads to proceed
    startLatch.countDown();
    
    // Wait for both threads to complete
    assertThat("Both threads should complete", 
        completionLatch.await(5, TimeUnit.SECONDS), is(true));
    
    // Verify that the work was performed exactly once
    assertThat("Work should have been performed exactly once", 
        workPerformed.get(), is(true));
  }
}