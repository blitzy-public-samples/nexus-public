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
package org.java21;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.nexus.security.UserIdMdcHelper;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.UserIdHelper.UNKNOWN;
import static org.sonatype.nexus.security.UserIdMdcHelper.KEY;

/**
 * Tests for {@link UserIdMdcHelper} with Java 21 Virtual Threads.
 * 
 * Verifies that MDC context is properly propagated across virtual threads
 * to ensure logging context is maintained in concurrent operations.
 * 
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class UserIdMdcHelperVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private void reset() {
    MDC.remove(KEY);
    ThreadContext.unbindSubject();
    ThreadContext.unbindSecurityManager();
  }

  @Before
  public void setUp() throws Exception {
    reset();
    assumeVirtualThreadSupported();
  }

  @After
  public void tearDown() throws Exception {
    reset();
  }

  private Subject subject(final Object principal) {
    Subject subject = mock(Subject.class);
    when(subject.getPrincipal()).thenReturn(principal);
    return subject;
  }

  /**
   * Tests that MDC context is properly propagated to a child virtual thread.
   */
  @Test
  public void testMdcPropagationToChildVirtualThread() throws Exception {
    // Set up MDC in parent thread
    Subject subject = subject("testUser");
    UserIdMdcHelper.set(subject);
    assertThat(MDC.get(KEY), is("testUser"));
    
    // Run task in child virtual thread and verify MDC is inherited
    AtomicBoolean mdcInherited = new AtomicBoolean(false);
    
    runVirtual(() -> {
      String userId = MDC.get(KEY);
      mdcInherited.set("testUser".equals(userId));
    });
    
    assertThat("MDC should be inherited by child virtual thread", mdcInherited.get(), is(true));
  }

  /**
   * Tests that MDC context can be set independently in different virtual threads.
   */
  @Test
  public void testMdcIsolationBetweenVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    AtomicBoolean success = new AtomicBoolean(true);
    
    // Start two virtual threads with different MDC values
    Thread thread1 = Thread.ofVirtual().start(() -> {
      try {
        Subject subject1 = subject("user1");
        UserIdMdcHelper.set(subject1);
        assertThat(MDC.get(KEY), is("user1"));
        
        // Wait for both threads to set their MDC values
        latch.countDown();
        latch.await();
        
        // Verify MDC value is still correct after other thread set its value
        if (!"user1".equals(MDC.get(KEY))) {
          success.set(false);
        }
      }
      catch (Exception e) {
        success.set(false);
      }
    });
    
    Thread thread2 = Thread.ofVirtual().start(() -> {
      try {
        Subject subject2 = subject("user2");
        UserIdMdcHelper.set(subject2);
        assertThat(MDC.get(KEY), is("user2"));
        
        // Wait for both threads to set their MDC values
        latch.countDown();
        latch.await();
        
        // Verify MDC value is still correct after other thread set its value
        if (!"user2".equals(MDC.get(KEY))) {
          success.set(false);
        }
      }
      catch (Exception e) {
        success.set(false);
      }
    });
    
    thread1.join();
    thread2.join();
    
    assertThat("MDC values should remain isolated between virtual threads", success.get(), is(true));
  }

  /**
   * Tests that MDC context is properly maintained when operations are executed in parallel virtual threads.
   */
  @Test
  public void testMdcWithManyParallelVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean success = new AtomicBoolean(true);
    
    // Create many virtual threads, each with its own MDC context
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      final String userId = "user" + i;
      Thread thread = Thread.ofVirtual().start(() -> {
        try {
          // Set user ID in MDC
          Subject threadSubject = subject(userId);
          UserIdMdcHelper.set(threadSubject);
          
          // Wait for all threads to start
          startLatch.await();
          
          // Verify MDC value is still correct
          if (!userId.equals(MDC.get(KEY))) {
            success.set(false);
          }
          
          // Simulate some work
          Thread.sleep(10);
          
          // Verify MDC value is still correct after work
          if (!userId.equals(MDC.get(KEY))) {
            success.set(false);
          }
        }
        catch (Exception e) {
          success.set(false);
        }
        finally {
          completionLatch.countDown();
        }
      });
      threads.add(thread);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    for (Thread thread : threads) {
      thread.join(1000);
    }
    
    assertThat("MDC values should be maintained correctly in all virtual threads", success.get(), is(true));
  }

  /**
   * Tests that MDC context is properly cleared to prevent leakage between operations.
   */
  @Test
  public void testMdcClearingPreventsLeakage() throws Exception {
    // Set MDC in parent thread
    Subject subject = subject("parentUser");
    UserIdMdcHelper.set(subject);
    assertThat(MDC.get(KEY), is("parentUser"));
    
    // Create a virtual thread that clears MDC and then spawns another virtual thread
    AtomicBoolean childReceivedParentMdc = new AtomicBoolean(false);
    
    runVirtual(() -> {
      // Verify MDC is inherited from parent
      assertThat(MDC.get(KEY), is("parentUser"));
      
      // Clear MDC
      UserIdMdcHelper.unset();
      assertThat(MDC.get(KEY), nullValue());
      
      // Spawn another virtual thread and verify it doesn't have the original MDC value
      try {
        Thread childThread = Thread.ofVirtual().start(() -> {
          String mdcValue = MDC.get(KEY);
          childReceivedParentMdc.set(mdcValue != null && mdcValue.equals("parentUser"));
        });
        childThread.join();
      }
      catch (InterruptedException e) {
        // Ignore
      }
    });
    
    assertThat("Child thread should not inherit cleared MDC from parent", childReceivedParentMdc.get(), is(false));
  }

  /**
   * Tests that MDC operations are thread-safe when executed in parallel virtual threads.
   */
  @Test
  public void testMdcThreadSafetyWithVirtualThreads() throws Exception {
    int threadCount = 1000;
    int operationsPerThread = 10;
    AtomicBoolean success = new AtomicBoolean(true);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit many tasks that set and unset MDC values
      CountDownLatch completionLatch = new CountDownLatch(threadCount);
      
      for (int i = 0; i < threadCount; i++) {
        final String userId = "user" + i;
        executor.submit(() -> {
          try {
            for (int j = 0; j < operationsPerThread; j++) {
              // Set user ID
              Subject threadSubject = subject(userId);
              UserIdMdcHelper.set(threadSubject);
              
              // Verify it was set correctly
              if (!userId.equals(MDC.get(KEY))) {
                success.set(false);
              }
              
              // Clear it
              UserIdMdcHelper.unset();
              
              // Verify it was cleared
              if (MDC.get(KEY) != null) {
                success.set(false);
              }
            }
          }
          catch (Exception e) {
            success.set(false);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      completionLatch.await(10, TimeUnit.SECONDS);
    }
    
    assertThat("MDC operations should be thread-safe across virtual threads", success.get(), is(true));
  }

  /**
   * Tests that setIfNeeded works correctly with virtual threads.
   */
  @Test
  public void testSetIfNeededWithVirtualThreads() throws Exception {
    // Bind subject to thread context
    ThreadContext.bind(subject("testUser"));
    
    // Run in virtual thread
    AtomicBoolean success = new AtomicBoolean(true);
    
    runVirtual(() -> {
      // MDC should not be set initially
      if (MDC.get(KEY) != null) {
        success.set(false);
        return;
      }
      
      // Call setIfNeeded
      UserIdMdcHelper.setIfNeeded();
      
      // Verify MDC is now set
      if (!"testUser".equals(MDC.get(KEY))) {
        success.set(false);
      }
      
      // Set a different value
      MDC.put(KEY, "differentUser");
      
      // Call setIfNeeded again - should not change the value
      UserIdMdcHelper.setIfNeeded();
      
      // Verify MDC value was not changed
      if (!"differentUser".equals(MDC.get(KEY))) {
        success.set(false);
      }
    });
    
    assertThat("setIfNeeded should work correctly with virtual threads", success.get(), is(true));
  }
}