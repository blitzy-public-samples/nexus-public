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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.lang.InheritableThreadLocal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.thread.NexusVirtualThreadFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NexusVirtualThreadFactory}.
 * 
 * This test class validates that the NexusVirtualThreadFactory correctly creates Java 21 Virtual Threads
 * with proper naming, thread groups, and inheritance of thread-local values.
 * 
 * Virtual Threads are a key feature of Java 21 that provide lightweight thread implementation
 * for improved scalability of applications with many concurrent tasks, particularly those that
 * spend most of their time blocked on I/O operations.
 * 
 * @since 3.60
 */
public class NexusVirtualThreadFactoryTest
    extends TestSupport
{
  private static final String TEST_PREFIX = "test-virtual";
  
  private NexusVirtualThreadFactory virtualThreadFactory;
  
  @BeforeEach
  public void setUp() {
    // Create a new NexusVirtualThreadFactory with the test prefix
    virtualThreadFactory = new NexusVirtualThreadFactory(TEST_PREFIX);
  }
  
  /**
   * Tests that threads created by the factory are actual virtual threads.
   */
  @Test
  @DisplayName("Factory should create virtual threads")
  public void testCreatesVirtualThreads() throws Exception {
    AtomicReference<Boolean> isVirtual = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = virtualThreadFactory.newThread(() -> {
      isVirtual.set(Thread.currentThread().isVirtual());
      latch.countDown();
    });
    
    thread.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    assertTrue(isVirtual.get(), "Thread should be a virtual thread");
  }
  
  /**
   * Tests that threads created by the factory have the expected name prefix.
   */
  @Test
  @DisplayName("Thread names should contain the specified prefix")
  public void testThreadNaming() throws Exception {
    AtomicReference<String> threadName = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = virtualThreadFactory.newThread(() -> {
      threadName.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    thread.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    assertThat(threadName.get(), containsString(TEST_PREFIX));
  }
  
  /**
   * Tests that thread-local variables are properly propagated to virtual threads.
   */
  @Test
  @DisplayName("ThreadLocal values should be propagated to virtual threads")
  public void testThreadLocalPropagation() throws Exception {
    // Create and set a ThreadLocal value in the parent thread
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("parent-value");
    
    AtomicReference<String> childValue = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create a virtual thread that reads the ThreadLocal value
    Thread thread = virtualThreadFactory.newThread(() -> {
      childValue.set(threadLocal.get());
      latch.countDown();
    });
    
    thread.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    // Verify that the ThreadLocal value was correctly propagated to the virtual thread
    assertEquals("parent-value", childValue.get(), "ThreadLocal value should be propagated to virtual thread");
  }
  
  /**
   * Tests that InheritableThreadLocal values are properly propagated to virtual threads.
   */
  @Test
  @DisplayName("InheritableThreadLocal values should be propagated to virtual threads")
  public void testInheritableThreadLocalPropagation() throws Exception {
    // Create and set an InheritableThreadLocal value in the parent thread
    InheritableThreadLocal<String> inheritableThreadLocal = new InheritableThreadLocal<>();
    inheritableThreadLocal.set("inheritable-value");
    
    AtomicReference<String> childValue = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create a virtual thread that reads the InheritableThreadLocal value
    Thread thread = virtualThreadFactory.newThread(() -> {
      childValue.set(inheritableThreadLocal.get());
      latch.countDown();
    });
    
    thread.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    // Verify that the InheritableThreadLocal value was correctly propagated to the virtual thread
    assertEquals("inheritable-value", childValue.get(), 
        "InheritableThreadLocal value should be propagated to virtual thread");
  }
  
  /**
   * Tests that thread-local variables can be modified independently in virtual threads.
   */
  @Test
  @DisplayName("ThreadLocal values should be independent between parent and child threads")
  public void testThreadLocalIndependence() throws Exception {
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("parent-value");
    
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = virtualThreadFactory.newThread(() -> {
      // Modify the thread-local in the child thread
      threadLocal.set("child-value");
      latch.countDown();
    });
    
    thread.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    // Parent thread's value should remain unchanged
    assertEquals("parent-value", threadLocal.get(), "ThreadLocal value in parent should not be affected");
  }
  
  /**
   * Tests that the factory correctly handles daemon thread settings.
   */
  @Test
  @DisplayName("Virtual threads should always be daemon threads")
  public void testDaemonSetting() throws Exception {
    // Create a factory with daemon threads
    NexusVirtualThreadFactory daemonFactory = new NexusVirtualThreadFactory(TEST_PREFIX, true);
    
    AtomicReference<Boolean> isDaemon = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = daemonFactory.newThread(() -> {
      isDaemon.set(Thread.currentThread().isDaemon());
      latch.countDown();
    });
    
    thread.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    // Virtual threads are always daemon threads in Java 21
    assertTrue(isDaemon.get(), "Virtual thread should be a daemon thread");
    
    // Create a factory with non-daemon threads (though this doesn't affect virtual threads)
    NexusVirtualThreadFactory nonDaemonFactory = new NexusVirtualThreadFactory(TEST_PREFIX, false);
    
    AtomicReference<Boolean> isDaemon2 = new AtomicReference<>();
    CountDownLatch latch2 = new CountDownLatch(1);
    
    Thread thread2 = nonDaemonFactory.newThread(() -> {
      isDaemon2.set(Thread.currentThread().isDaemon());
      latch2.countDown();
    });
    
    thread2.start();
    assertTrue(latch2.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    // Virtual threads are always daemon threads regardless of the setting
    assertTrue(isDaemon2.get(), "Virtual thread should always be a daemon thread");
  }
  
  /**
   * Tests that the factory correctly handles thread priority settings.
   */
  @Test
  @DisplayName("Virtual threads should use normal priority regardless of setting")
  public void testPrioritySetting() throws Exception {
    // Create a factory with a specific priority
    NexusVirtualThreadFactory priorityFactory = new NexusVirtualThreadFactory(TEST_PREFIX, Thread.MAX_PRIORITY);
    
    AtomicReference<Integer> priority = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = priorityFactory.newThread(() -> {
      priority.set(Thread.currentThread().getPriority());
      latch.countDown();
    });
    
    thread.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    // Virtual threads in Java 21 ignore priority settings and use the default priority
    // This is a documented behavior of virtual threads in JEP 444
    assertEquals(Thread.NORM_PRIORITY, priority.get().intValue(), 
        "Virtual thread should use normal priority regardless of setting");
    
    // Verify that attempting to set priority on a virtual thread has no effect
    AtomicReference<Integer> priorityAfterSet = new AtomicReference<>();
    CountDownLatch latch2 = new CountDownLatch(1);
    
    Thread thread2 = priorityFactory.newThread(() -> {
      Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
      priorityAfterSet.set(Thread.currentThread().getPriority());
      latch2.countDown();
    });
    
    thread2.start();
    assertTrue(latch2.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    // Priority should still be NORM_PRIORITY despite the attempt to change it
    assertEquals(Thread.NORM_PRIORITY, priorityAfterSet.get().intValue(),
        "Virtual thread priority should not be changeable");
  }
  
  /**
   * Tests that the factory correctly handles thread group settings.
   */
  @Test
  @DisplayName("Virtual threads should have a thread group")
  public void testThreadGroupSetting() throws Exception {
    // Create a specific thread group
    ThreadGroup testGroup = new ThreadGroup("test-group");
    
    // Create a factory with the specific thread group
    NexusVirtualThreadFactory groupFactory = new NexusVirtualThreadFactory(TEST_PREFIX, testGroup);
    
    AtomicReference<ThreadGroup> threadGroup = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = groupFactory.newThread(() -> {
      threadGroup.set(Thread.currentThread().getThreadGroup());
      latch.countDown();
    });
    
    thread.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    // Virtual threads in Java 21 don't belong to thread groups in the same way as platform threads
    // They typically belong to a system-provided thread group or null
    assertNotNull(threadGroup.get(), "Virtual thread should have a thread group");
  }
  
  /**
   * Tests that multiple threads created by the factory have unique names.
   */
  @Test
  @DisplayName("Multiple threads should have unique names")
  public void testUniqueThreadNames() throws Exception {
    AtomicReference<String> name1 = new AtomicReference<>();
    AtomicReference<String> name2 = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(2);
    
    Thread thread1 = virtualThreadFactory.newThread(() -> {
      name1.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    Thread thread2 = virtualThreadFactory.newThread(() -> {
      name2.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    thread1.start();
    thread2.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Threads did not complete in time");
    
    // Verify that both thread names contain the specified prefix
    assertThat(name1.get(), containsString(TEST_PREFIX));
    assertThat(name2.get(), containsString(TEST_PREFIX));
    
    // Verify that the thread names are unique
    assertFalse(name1.get().equals(name2.get()), "Thread names should be unique");
    
    // Create multiple threads in a loop to verify consistent naming pattern
    int threadCount = 10;
    CountDownLatch multiLatch = new CountDownLatch(threadCount);
    List<String> threadNames = Collections.synchronizedList(new ArrayList<>());
    
    for (int i = 0; i < threadCount; i++) {
      virtualThreadFactory.newThread(() -> {
        threadNames.add(Thread.currentThread().getName());
        multiLatch.countDown();
      }).start();
    }
    
    assertTrue(multiLatch.await(5, TimeUnit.SECONDS), "Threads did not complete in time");
    
    // Verify that we have the expected number of thread names
    assertEquals(threadCount, threadNames.size(), "Should have collected all thread names");
    
    // Verify that all thread names contain the prefix
    for (String name : threadNames) {
      assertThat(name, containsString(TEST_PREFIX));
    }
    
    // Verify that all thread names are unique
    Set<String> uniqueNames = new HashSet<>(threadNames);
    assertEquals(threadCount, uniqueNames.size(), "All thread names should be unique");
  }
  
  /**
   * Tests that the factory can be used as a ThreadFactory in standard Java concurrency APIs.
   */
  @Test
  @DisplayName("Factory should implement ThreadFactory interface")
  public void testThreadFactoryInterface() {
    // Verify that NexusVirtualThreadFactory implements ThreadFactory
    ThreadFactory factory = virtualThreadFactory;
    assertNotNull(factory, "Factory should implement ThreadFactory interface");
    
    // Verify that the factory can create threads through the ThreadFactory interface
    Thread thread = factory.newThread(() -> {});
    assertNotNull(thread, "Factory should create a thread");
    
    // Verify that the created thread is a virtual thread
    assertTrue(thread.isVirtual(), "Thread created through ThreadFactory interface should be virtual");
  }
  
  /**
   * Tests that the factory handles null thread names gracefully.
   */
  @Test
  @DisplayName("Factory should handle null thread name prefix gracefully")
  public void testNullThreadName() throws Exception {
    // Create a factory with null prefix (should use a default)
    NexusVirtualThreadFactory nullPrefixFactory = new NexusVirtualThreadFactory(null);
    
    AtomicReference<String> threadName = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = nullPrefixFactory.newThread(() -> {
      threadName.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    thread.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Thread did not complete in time");
    
    assertNotNull(threadName.get(), "Thread should have a non-null name");
    assertFalse(threadName.get().isEmpty(), "Thread should have a non-empty name");
  }
  
  /**
   * Tests that the factory correctly handles exceptions in virtual threads.
   */
  @Test
  @DisplayName("Factory should handle exceptions in virtual threads properly")
  public void testExceptionHandling() throws Exception {
    CountDownLatch exceptionLatch = new CountDownLatch(1);
    AtomicReference<Throwable> caughtException = new AtomicReference<>();
    
    // Create a thread with a custom UncaughtExceptionHandler
    Thread thread = virtualThreadFactory.newThread(() -> {
      throw new RuntimeException("Test exception");
    });
    
    thread.setUncaughtExceptionHandler((t, e) -> {
      caughtException.set(e);
      exceptionLatch.countDown();
    });
    
    thread.start();
    assertTrue(exceptionLatch.await(5, TimeUnit.SECONDS), "Exception was not caught in time");
    
    // Verify that the exception was caught by the handler
    assertNotNull(caughtException.get(), "Exception should have been caught");
    assertTrue(caughtException.get() instanceof RuntimeException, "Exception should be of correct type");
    assertEquals("Test exception", caughtException.get().getMessage(), "Exception should have correct message");
  }
}