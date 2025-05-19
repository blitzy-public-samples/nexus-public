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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link TcclBlock} with Java 21 Virtual Threads.
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class VirtualThreadTcclBlockTest
    extends TestSupport
{
  @Test
  public void testBeginAndRestoreClassLoaderWithVirtualThread() throws Exception {
    ClassLoader classLoader = new SecureClassLoader(getClass().getClassLoader())
    {
    };
    
    AtomicBoolean success = new AtomicBoolean(false);
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      Thread thread = Thread.currentThread();
      ClassLoader original = thread.getContextClassLoader();
      
      try (TcclBlock tccl = TcclBlock.begin(classLoader)) {
        // Verify the context ClassLoader was set correctly
        if (thread.getContextClassLoader() == classLoader) {
          success.set(true);
        }
      }
      
      // Verify the context ClassLoader was restored correctly
      if (thread.getContextClassLoader() != original) {
        success.set(false);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    assertThat("TcclBlock should correctly set and restore ClassLoader in virtual thread", 
        success.get(), is(true));
  }
  
  @Test
  public void testMultipleConcurrentVirtualThreads() throws Exception {
    final int threadCount = 100;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final List<Throwable> errors = new ArrayList<>();
    
    // Create a custom ClassLoader for testing
    ClassLoader classLoader = new SecureClassLoader(getClass().getClassLoader())
    {
    };
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to be executed by virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to start at the same time
            startLatch.await();
            
            Thread thread = Thread.currentThread();
            ClassLoader original = thread.getContextClassLoader();
            
            try (TcclBlock tccl = TcclBlock.begin(classLoader)) {
              // Verify the context ClassLoader was set correctly
              assertThat(thread.getContextClassLoader(), is(classLoader));
              
              // Simulate some work
              Thread.sleep(10);
            }
            
            // Verify the context ClassLoader was restored correctly
            assertThat(thread.getContextClassLoader(), is(original));
          }
          catch (Throwable t) {
            synchronized (errors) {
              errors.add(t);
            }
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
    }
    
    // Wait for all threads to complete
    assertThat("All virtual threads should complete in time",
        completionLatch.await(5, TimeUnit.SECONDS), is(true));
    
    // Check for any errors
    assertThat("No errors should occur in virtual threads", errors.isEmpty(), is(true));
  }
  
  @Test
  public void testNestedTcclBlocksWithVirtualThread() throws Exception {
    ClassLoader outerClassLoader = new SecureClassLoader(getClass().getClassLoader()) {};
    ClassLoader innerClassLoader = new SecureClassLoader(getClass().getClassLoader()) {};
    
    AtomicBoolean success = new AtomicBoolean(true);
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      Thread thread = Thread.currentThread();
      ClassLoader original = thread.getContextClassLoader();
      
      try (TcclBlock outerTccl = TcclBlock.begin(outerClassLoader)) {
        // Verify outer ClassLoader was set
        if (thread.getContextClassLoader() != outerClassLoader) {
          success.set(false);
        }
        
        try (TcclBlock innerTccl = TcclBlock.begin(innerClassLoader)) {
          // Verify inner ClassLoader was set
          if (thread.getContextClassLoader() != innerClassLoader) {
            success.set(false);
          }
        }
        
        // Verify outer ClassLoader was restored after inner block
        if (thread.getContextClassLoader() != outerClassLoader) {
          success.set(false);
        }
      }
      
      // Verify original ClassLoader was restored after outer block
      if (thread.getContextClassLoader() != original) {
        success.set(false);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    assertThat("Nested TcclBlocks should correctly set and restore ClassLoaders in virtual thread", 
        success.get(), is(true));
  }
}