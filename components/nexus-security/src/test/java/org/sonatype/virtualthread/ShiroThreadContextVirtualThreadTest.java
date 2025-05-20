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
package org.sonatype.virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ThreadContext} behavior with Java 21 Virtual Threads.
 * 
 * <p>This test verifies that security contexts are properly bound, propagated, and cleared
 * when using virtual threads, preventing context leakage between concurrent operations.</p>
 * 
 * <p>To detect thread pinning issues, run with the JVM flag: -Djdk.tracePinnedThreads=full</p>
 */
public class ShiroThreadContextVirtualThreadTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(ShiroThreadContextVirtualThreadTest.class);
  
  private static final int THREAD_COUNT = 100;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
  
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() throws Exception {
    // Clear any ThreadContext that might be set
    ThreadContext.remove();
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    log.info("Test setup complete with virtual thread executor");
  }
  
  @After
  public void tearDown() throws Exception {
    // Clear ThreadContext
    ThreadContext.remove();
    
    // Shutdown executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("Virtual thread executor did not terminate in the expected time");
        virtualThreadExecutor.shutdownNow();
      }
    }
  }
  
  /**
   * Creates a mock Subject with the given principal.
   */
  private Subject createMockSubject(final String principal) {
    Subject subject = mock(Subject.class);
    when(subject.getPrincipal()).thenReturn(principal);
    return subject;
  }
  
  /**
   * Creates a mock SecurityManager.
   */
  private SecurityManager createMockSecurityManager() {
    return mock(SecurityManager.class);
  }
  
  /**
   * Tests that ThreadContext isolation works correctly across concurrent virtual threads.
   * Each virtual thread should have its own isolated ThreadContext.
   */
  @Test
  public void testThreadContextIsolationAcrossVirtualThreads() throws Exception {
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    final AtomicInteger failures = new AtomicInteger(0);
    final Map<String, Subject> threadSubjects = new ConcurrentHashMap<>();
    
    // Create and submit tasks
    for (int i = 0; i < THREAD_COUNT; i++) {
      final String threadId = "thread-" + i;
      final Subject threadSubject = createMockSubject(threadId);
      threadSubjects.put(threadId, threadSubject);
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Bind subject to this virtual thread
          ThreadContext.bind(threadSubject);
          
          // Verify the subject is correctly bound to this thread
          Subject boundSubject = ThreadContext.getSubject();
          if (!threadId.equals(boundSubject.getPrincipal())) {
            log.error("Thread {} has incorrect subject: {}", threadId, boundSubject.getPrincipal());
            failures.incrementAndGet();
          }
          
          // Sleep a bit to increase chance of thread scheduling interference
          Thread.sleep(50);
          
          // Verify the subject is still correctly bound to this thread
          boundSubject = ThreadContext.getSubject();
          if (!threadId.equals(boundSubject.getPrincipal())) {
            log.error("Thread {} has incorrect subject after sleep: {}", threadId, boundSubject.getPrincipal());
            failures.incrementAndGet();
          }
          
          // Clean up
          ThreadContext.unbindSubject();
        }
        catch (Exception e) {
          log.error("Exception in virtual thread {}", threadId, e);
          failures.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for completion
    assertThat("All virtual threads should complete in time", 
        completionLatch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    // Verify no failures occurred
    assertThat("No failures should occur in virtual threads", failures.get(), is(0));
  }
  
  /**
   * Tests explicit context propagation from platform threads to virtual threads.
   * This verifies that a Subject bound on a platform thread can be explicitly propagated
   * to a virtual thread.
   */
  @Test
  public void testExplicitContextPropagationToVirtualThread() throws Exception {
    // Set up subject on the main (platform) thread
    final Subject mainThreadSubject = createMockSubject("main-thread-subject");
    ThreadContext.bind(mainThreadSubject);
    
    // Get the resources from the platform thread to propagate to virtual thread
    final Map<Object, Object> resources = ThreadContext.getResources();
    assertThat("Resources should not be null", resources, not(nullValue()));
    
    // Create a latch to wait for the virtual thread to complete
    final CountDownLatch latch = new CountDownLatch(1);
    final List<Subject> virtualThreadSubjects = new ArrayList<>();
    
    // Execute a task on a virtual thread
    virtualThreadExecutor.submit(() -> {
      try {
        // Initially, the virtual thread should not have a subject
        Subject initialSubject = ThreadContext.getSubject();
        virtualThreadSubjects.add(initialSubject); // Will be null
        
        // Explicitly set the resources from the platform thread
        ThreadContext.setResources(resources);
        
        // Now the virtual thread should have the same subject as the platform thread
        Subject propagatedSubject = ThreadContext.getSubject();
        virtualThreadSubjects.add(propagatedSubject);
        
        // Clean up
        ThreadContext.remove();
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertThat("Virtual thread should complete in time",
        latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    // Verify results
    assertThat("Should have collected 2 subjects", virtualThreadSubjects.size(), is(2));
    assertThat("Initial subject should be null", virtualThreadSubjects.get(0), nullValue());
    assertThat("Propagated subject should match main thread subject", 
        virtualThreadSubjects.get(1).getPrincipal(), equalTo(mainThreadSubject.getPrincipal()));
    
    // Clean up the main thread
    ThreadContext.remove();
  }
  
  /**
   * Tests that Subject binding and unbinding operations work correctly with virtual threads.
   */
  @Test
  public void testSubjectBindingAndUnbindingWithVirtualThreads() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    final List<Object> results = new ArrayList<>();
    
    // Create a subject and security manager
    final Subject subject = createMockSubject("test-subject");
    final SecurityManager securityManager = createMockSecurityManager();
    
    // Execute a task on a virtual thread
    virtualThreadExecutor.submit(() -> {
      try {
        // Initially, there should be no subject or security manager
        results.add(ThreadContext.getSubject());
        results.add(ThreadContext.getSecurityManager());
        
        // Bind the subject
        ThreadContext.bind(subject);
        results.add(ThreadContext.getSubject().getPrincipal());
        
        // Bind the security manager
        ThreadContext.bind(securityManager);
        results.add(ThreadContext.getSecurityManager() != null);
        
        // Unbind the subject
        Subject unboundSubject = ThreadContext.unbindSubject();
        results.add(unboundSubject.getPrincipal());
        results.add(ThreadContext.getSubject());
        
        // Unbind the security manager
        SecurityManager unboundSecurityManager = ThreadContext.unbindSecurityManager();
        results.add(unboundSecurityManager != null);
        results.add(ThreadContext.getSecurityManager());
        
        // Clear everything
        ThreadContext.remove();
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertThat("Virtual thread should complete in time",
        latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    // Verify results
    assertThat("Should have 8 results", results.size(), is(8));
    assertThat("Initial subject should be null", results.get(0), nullValue());
    assertThat("Initial security manager should be null", results.get(1), nullValue());
    assertThat("Bound subject principal should match", results.get(2), equalTo("test-subject"));
    assertThat("Security manager should be bound", results.get(3), is(true));
    assertThat("Unbound subject principal should match", results.get(4), equalTo("test-subject"));
    assertThat("Subject should be null after unbinding", results.get(5), nullValue());
    assertThat("Unbound security manager should not be null", results.get(6), is(true));
    assertThat("Security manager should be null after unbinding", results.get(7), nullValue());
  }
  
  /**
   * Tests that ThreadContext operations do not cause thread pinning.
   * 
   * <p>Note: This test is primarily for documentation purposes. To detect actual pinning,
   * run the test with the JVM flag: -Djdk.tracePinnedThreads=full</p>
   */
  @Test
  public void testThreadContextOperationsDoNotCauseThreadPinning() throws Exception {
    final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    final AtomicInteger failures = new AtomicInteger(0);
    
    // Create and submit tasks
    for (int i = 0; i < THREAD_COUNT; i++) {
      final String threadId = "thread-" + i;
      final Subject threadSubject = createMockSubject(threadId);
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Perform a series of ThreadContext operations
          ThreadContext.bind(threadSubject);
          
          // Simulate some work that might cause blocking
          Thread.sleep(10);
          
          Subject boundSubject = ThreadContext.getSubject();
          if (!threadId.equals(boundSubject.getPrincipal())) {
            failures.incrementAndGet();
          }
          
          // More simulated work
          Thread.sleep(10);
          
          // Clean up
          ThreadContext.unbindSubject();
        }
        catch (Exception e) {
          log.error("Exception in virtual thread {}", threadId, e);
          failures.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for completion
    assertThat("All virtual threads should complete in time", 
        latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
    
    // Verify no failures occurred
    assertThat("No failures should occur in virtual threads", failures.get(), is(0));
    
    log.info("Completed thread pinning test with {} virtual threads", THREAD_COUNT);
    log.info("To detect thread pinning, run with: -Djdk.tracePinnedThreads=full");
  }
}