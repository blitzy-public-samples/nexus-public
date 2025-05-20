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
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.UserIdMdcHelper;
import org.sonatype.nexus.virtualthread.VirtualThreadTestGroup;

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
 * and that operations are thread-safe in high-concurrency scenarios.
 */
@Category(VirtualThreadTestGroup.class)
public class UserIdMdcHelperVirtualThreadTest
    extends TestSupport
{
  private void reset() {
    MDC.remove(KEY);
    ThreadContext.unbindSubject();
    ThreadContext.unbindSecurityManager();
  }

  @Before
  public void setUp() throws Exception {
    reset();
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
  public void testMdcPropagationToVirtualThread() throws Exception {
    // Set up MDC in parent thread
    ThreadContext.bind(subject("test-user"));
    UserIdMdcHelper.set();
    assertThat(MDC.get(KEY), is("test-user"));
    
    // Create a virtual thread and verify MDC is inherited
    AtomicBoolean mdcInherited = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Verify MDC is properly propagated
        String userId = MDC.get(KEY);
        mdcInherited.set("test-user".equals(userId));
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    assertThat("MDC should be inherited by virtual thread", mdcInherited.get(), is(true));
  }

  /**
   * Tests that MDC context is properly set and cleared in a virtual thread.
   */
  @Test
  public void testMdcSetAndClearInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean setSuccessful = new AtomicBoolean(false);
    AtomicBoolean clearSuccessful = new AtomicBoolean(false);
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Initially MDC should be empty
        assertThat(MDC.get(KEY), nullValue());
        
        // Set MDC in virtual thread
        ThreadContext.bind(subject("virtual-user"));
        UserIdMdcHelper.set();
        
        // Verify MDC was set correctly
        setSuccessful.set("virtual-user".equals(MDC.get(KEY)));
        
        // Clear MDC
        UserIdMdcHelper.unset();
        
        // Verify MDC was cleared
        clearSuccessful.set(MDC.get(KEY) == null);
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    assertThat("MDC should be set successfully in virtual thread", setSuccessful.get(), is(true));
    assertThat("MDC should be cleared successfully in virtual thread", clearSuccessful.get(), is(true));
  }

  /**
   * Tests that MDC operations are thread-safe when executed in parallel virtual threads.
   */
  @Test
  public void testMdcThreadSafetyWithParallelVirtualThreads() throws Exception {
    final int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    List<Thread> threads = new ArrayList<>();
    
    // Create multiple virtual threads that will start simultaneously
    for (int i = 0; i < threadCount; i++) {
      final String userId = "user-" + i;
      Thread virtualThread = Thread.ofVirtual().start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Set MDC with a unique user ID
          ThreadContext.bind(subject(userId));
          UserIdMdcHelper.set();
          
          // Verify MDC contains the correct user ID
          if (!userId.equals(MDC.get(KEY))) {
            errorCount.incrementAndGet();
          }
          
          // Small delay to increase chance of thread interference
          Thread.sleep(5);
          
          // Verify MDC still contains the correct user ID
          if (!userId.equals(MDC.get(KEY))) {
            errorCount.incrementAndGet();
          }
          
          // Clear MDC
          UserIdMdcHelper.unset();
          
          // Verify MDC was cleared
          if (MDC.get(KEY) != null) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          completionLatch.countDown();
        }
      });
      
      threads.add(virtualThread);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(10, TimeUnit.SECONDS);
    
    // Join all threads
    for (Thread thread : threads) {
      thread.join();
    }
    
    assertThat("No errors should occur in parallel MDC operations", errorCount.get(), is(0));
  }

  /**
   * Tests MDC behavior in a high-concurrency scenario with virtual threads.
   */
  @Test
  public void testMdcHighConcurrencyWithVirtualThreads() throws Exception {
    final int threadCount = 1000;
    
    // Use a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(threadCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final String userId = "concurrent-user-" + i;
        
        executor.submit(() -> {
          try {
            // Set MDC with a unique user ID
            ThreadContext.bind(subject(userId));
            UserIdMdcHelper.set();
            
            // Verify MDC contains the correct user ID
            if (!userId.equals(MDC.get(KEY))) {
              errorCount.incrementAndGet();
            }
            
            // Simulate some work
            Thread.sleep(1);
            
            // Verify MDC still contains the correct user ID
            if (!userId.equals(MDC.get(KEY))) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            // Always clear MDC to prevent memory leaks
            UserIdMdcHelper.unset();
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      completionLatch.await(30, TimeUnit.SECONDS);
      
      assertThat("No errors should occur in high-concurrency MDC operations", errorCount.get(), is(0));
    }
  }

  /**
   * Tests that MDC context is properly maintained when a virtual thread is suspended and resumed.
   */
  @Test
  public void testMdcPreservationAcrossVirtualThreadSuspension() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean mdcPreserved = new AtomicBoolean(false);
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Set MDC in virtual thread
        ThreadContext.bind(subject("suspended-user"));
        UserIdMdcHelper.set();
        
        // Verify initial MDC value
        assertThat(MDC.get(KEY), is("suspended-user"));
        
        // Perform an operation that will cause the virtual thread to be suspended
        // (I/O or blocking operation that yields to the scheduler)
        Thread.sleep(100);
        
        // After resuming, verify MDC is still intact
        mdcPreserved.set("suspended-user".equals(MDC.get(KEY)));
      } catch (Exception e) {
        // Ignore
      } finally {
        UserIdMdcHelper.unset();
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    assertThat("MDC should be preserved across virtual thread suspension", mdcPreserved.get(), is(true));
  }

  /**
   * Tests that nested virtual threads properly inherit and maintain MDC context.
   */
  @Test
  public void testNestedVirtualThreadsMdcInheritance() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean parentMdcSet = new AtomicBoolean(false);
    AtomicBoolean childMdcInherited = new AtomicBoolean(false);
    AtomicBoolean childMdcOverridden = new AtomicBoolean(false);
    
    // Create parent virtual thread
    Thread parentThread = Thread.ofVirtual().start(() -> {
      try {
        // Set MDC in parent virtual thread
        ThreadContext.bind(subject("parent-user"));
        UserIdMdcHelper.set();
        
        // Verify parent MDC
        parentMdcSet.set("parent-user".equals(MDC.get(KEY)));
        
        // Create child virtual thread
        Thread childThread = Thread.ofVirtual().start(() -> {
          try {
            // Verify MDC is inherited from parent
            childMdcInherited.set("parent-user".equals(MDC.get(KEY)));
            
            // Override MDC in child thread
            ThreadContext.bind(subject("child-user"));
            UserIdMdcHelper.set();
            
            // Verify child MDC was overridden
            childMdcOverridden.set("child-user".equals(MDC.get(KEY)));
          } finally {
            UserIdMdcHelper.unset();
          }
        });
        
        childThread.join();
        
        // Verify parent MDC is still intact after child completes
        if (!"parent-user".equals(MDC.get(KEY))) {
          parentMdcSet.set(false);
        }
      } finally {
        UserIdMdcHelper.unset();
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    parentThread.join();
    
    assertThat("Parent MDC should be set correctly", parentMdcSet.get(), is(true));
    assertThat("Child should inherit MDC from parent", childMdcInherited.get(), is(true));
    assertThat("Child should be able to override inherited MDC", childMdcOverridden.get(), is(true));
  }
}