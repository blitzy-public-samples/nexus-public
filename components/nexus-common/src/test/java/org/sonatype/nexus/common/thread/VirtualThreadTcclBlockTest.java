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
package org.sonatype.nexus.common.thread;

import java.security.SecureClassLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests for {@link TcclBlock} with Java 21 Virtual Threads.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class VirtualThreadTcclBlockTest
    extends TestSupport
{
  /**
   * Verifies that TcclBlock correctly sets and restores the context ClassLoader for a virtual thread.
   */
  @Test
  public void testBeginAndRestoreClassLoaderWithVirtualThread() throws Exception {
    // Create a custom ClassLoader for testing
    ClassLoader customClassLoader = new SecureClassLoader(getClass().getClassLoader()) {};
    
    // Create a reference to store the thread's context ClassLoader
    AtomicReference<ClassLoader> threadContextClassLoader = new AtomicReference<>();
    AtomicReference<ClassLoader> insideBlockClassLoader = new AtomicReference<>();
    AtomicReference<ClassLoader> afterBlockClassLoader = new AtomicReference<>();
    AtomicBoolean testCompleted = new AtomicBoolean(false);
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Store the original context ClassLoader
        threadContextClassLoader.set(Thread.currentThread().getContextClassLoader());
        
        // Use TcclBlock to set a new context ClassLoader
        try (TcclBlock tccl = TcclBlock.begin(customClassLoader)) {
          // Store the ClassLoader inside the block
          insideBlockClassLoader.set(Thread.currentThread().getContextClassLoader());
        }
        
        // Store the ClassLoader after the block
        afterBlockClassLoader.set(Thread.currentThread().getContextClassLoader());
        testCompleted.set(true);
      }
      catch (Exception e) {
        log.error("Error in virtual thread test", e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(5000);
    
    // Verify the test completed successfully
    assertThat("Test should have completed", testCompleted.get(), is(true));
    
    // Verify the ClassLoader was correctly set inside the block
    assertThat(insideBlockClassLoader.get(), is(notNullValue()));
    assertThat(insideBlockClassLoader.get(), is(sameInstance(customClassLoader)));
    
    // Verify the ClassLoader was correctly restored after the block
    assertThat(afterBlockClassLoader.get(), is(notNullValue()));
    assertThat(afterBlockClassLoader.get(), is(sameInstance(threadContextClassLoader.get())));
  }
  
  /**
   * Tests multiple concurrent virtual threads using the same ClassLoader with TcclBlock.
   */
  @Test
  public void testMultipleConcurrentVirtualThreads() throws Exception {
    // Create a custom ClassLoader for testing
    ClassLoader customClassLoader = new SecureClassLoader(getClass().getClassLoader()) {};
    
    // Number of virtual threads to test
    int threadCount = 100;
    
    // Create a countdown latch to synchronize thread completion
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Create an executor service with virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Track any errors that occur during testing
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    // Submit tasks to the executor
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          // Get the original ClassLoader
          ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
          
          // Use TcclBlock to set a new context ClassLoader
          try (TcclBlock tccl = TcclBlock.begin(customClassLoader)) {
            // Verify the ClassLoader was correctly set
            ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
            if (currentClassLoader != customClassLoader) {
              log.error("ClassLoader not correctly set in virtual thread");
              hasErrors.set(true);
            }
            
            // Simulate some work
            Thread.sleep(10);
          }
          
          // Verify the ClassLoader was correctly restored
          ClassLoader restoredClassLoader = Thread.currentThread().getContextClassLoader();
          if (restoredClassLoader != originalClassLoader) {
            log.error("ClassLoader not correctly restored in virtual thread");
            hasErrors.set(true);
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread", e);
          hasErrors.set(true);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(10, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify all threads completed
    assertThat("All virtual threads should have completed", completed, is(true));
    
    // Verify no errors occurred
    assertThat("No errors should have occurred in virtual threads", hasErrors.get(), is(false));
  }
  
  /**
   * Tests that TcclBlock properly handles thread pinning scenarios with virtual threads.
   * This test verifies that even when a virtual thread might be pinned due to ClassLoader operations,
   * the TcclBlock correctly restores the original ClassLoader.
   */
  @Test
  public void testTcclBlockWithThreadPinningScenario() throws Exception {
    // Create a custom ClassLoader for testing
    ClassLoader customClassLoader = new SecureClassLoader(getClass().getClassLoader()) {
      // Override loadClass to potentially cause thread pinning
      @Override
      public Class<?> loadClass(String name) throws ClassNotFoundException {
        // Simulate some work that might cause pinning
        try {
          Thread.sleep(5);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return super.loadClass(name);
      }
    };
    
    // Create a reference to store the thread's context ClassLoader
    AtomicReference<ClassLoader> threadContextClassLoader = new AtomicReference<>();
    AtomicReference<ClassLoader> afterBlockClassLoader = new AtomicReference<>();
    AtomicBoolean testCompleted = new AtomicBoolean(false);
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Store the original context ClassLoader
        threadContextClassLoader.set(Thread.currentThread().getContextClassLoader());
        
        // Use TcclBlock to set a new context ClassLoader
        try (TcclBlock tccl = TcclBlock.begin(customClassLoader)) {
          // Perform operations that might cause thread pinning
          try {
            // Load a class to potentially trigger pinning
            customClassLoader.loadClass("java.util.ArrayList");
            
            // Perform some synchronized operations
            synchronized (customClassLoader) {
              customClassLoader.getResource("java/util/ArrayList.class");
            }
          }
          catch (ClassNotFoundException e) {
            log.error("Error loading class", e);
          }
        }
        
        // Store the ClassLoader after the block
        afterBlockClassLoader.set(Thread.currentThread().getContextClassLoader());
        testCompleted.set(true);
      }
      catch (Exception e) {
        log.error("Error in virtual thread test", e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(5000);
    
    // Verify the test completed successfully
    assertThat("Test should have completed", testCompleted.get(), is(true));
    
    // Verify the ClassLoader was correctly restored after the block, even with potential pinning
    assertThat(afterBlockClassLoader.get(), is(notNullValue()));
    assertThat(afterBlockClassLoader.get(), is(sameInstance(threadContextClassLoader.get())));
  }
}