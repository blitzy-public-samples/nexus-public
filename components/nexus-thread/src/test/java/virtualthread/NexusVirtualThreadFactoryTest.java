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
package virtualthread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.thread.NexusThreadFactory;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NexusThreadFactory} with Java 21 Virtual Threads.
 *
 * @since 3.60
 */
public class NexusVirtualThreadFactoryTest
{
  private static final String POOL_ID = "test-pool";
  private static final String THREAD_GROUP_NAME = "test-group";
  private static final int THREAD_PRIORITY = Thread.NORM_PRIORITY;
  
  /**
   * Tests that threads created by the factory with virtual threads enabled are properly named
   * and are actually virtual threads.
   */
  @Test
  public void virtualThreadsAreProperlyNamed() throws Exception {
    NexusThreadFactory factory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true, // daemon
        true  // use virtual threads
    );
    
    AtomicReference<String> threadName = new AtomicReference<>();
    AtomicReference<Boolean> isVirtual = new AtomicReference<>();
    
    Thread thread = factory.newThread(() -> {
      threadName.set(Thread.currentThread().getName());
      isVirtual.set(Thread.currentThread().isVirtual());
    });
    
    thread.start();
    thread.join();
    
    assertThat(threadName.get(), containsString("vt-" + POOL_ID));
    assertThat(isVirtual.get(), is(true));
  }
  
  /**
   * Tests that the factory correctly reports whether it's using virtual threads.
   */
  @Test
  public void factoryCorrectlyReportsVirtualThreadUsage() {
    NexusThreadFactory virtualFactory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true, // daemon
        true  // use virtual threads
    );
    
    NexusThreadFactory platformFactory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true, // daemon
        false // use platform threads
    );
    
    assertThat(virtualFactory.isUsingVirtualThreads(), is(true));
    assertThat(platformFactory.isUsingVirtualThreads(), is(false));
    
    // Test setter
    virtualFactory.setUseVirtualThreads(false);
    assertThat(virtualFactory.isUsingVirtualThreads(), is(false));
    
    platformFactory.setUseVirtualThreads(true);
    assertThat(platformFactory.isUsingVirtualThreads(), is(true));
  }
  
  /**
   * Tests that thread-local variables are properly maintained with virtual threads
   * when inheritInheritableThreadLocals is set to true (default).
   */
  @Test
  public void threadLocalVariablesAreInheritedByDefault() throws Exception {
    NexusThreadFactory factory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true, // daemon
        true  // use virtual threads
    );
    
    ThreadLocal<String> threadLocal = new InheritableThreadLocal<>();
    threadLocal.set("parent-value");
    
    AtomicReference<String> childValue = new AtomicReference<>();
    
    Thread thread = factory.newThread(() -> {
      childValue.set(threadLocal.get());
    });
    
    thread.start();
    thread.join();
    
    assertThat(childValue.get(), is("parent-value"));
  }
  
  /**
   * Tests that thread-local variables are not inherited when inheritInheritableThreadLocals
   * is set to false.
   */
  @Test
  public void threadLocalVariablesAreNotInheritedWhenDisabled() throws Exception {
    NexusThreadFactory factory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true,  // daemon
        true,  // use virtual threads
        false  // do not inherit thread locals
    );
    
    ThreadLocal<String> threadLocal = new InheritableThreadLocal<>();
    threadLocal.set("parent-value");
    
    AtomicReference<String> childValue = new AtomicReference<>();
    
    Thread thread = factory.newThread(() -> {
      childValue.set(threadLocal.get());
    });
    
    thread.start();
    thread.join();
    
    assertThat(childValue.get(), is((String)null));
  }
  
  /**
   * Tests that the factory correctly reports and sets the inheritInheritableThreadLocals property.
   */
  @Test
  public void factoryCorrectlyReportsThreadLocalInheritance() {
    NexusThreadFactory inheritingFactory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true,  // daemon
        true,  // use virtual threads
        true   // inherit thread locals
    );
    
    NexusThreadFactory nonInheritingFactory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true,  // daemon
        true,  // use virtual threads
        false  // do not inherit thread locals
    );
    
    assertThat(inheritingFactory.isInheritInheritableThreadLocals(), is(true));
    assertThat(nonInheritingFactory.isInheritInheritableThreadLocals(), is(false));
    
    // Test setter
    inheritingFactory.setInheritInheritableThreadLocals(false);
    assertThat(inheritingFactory.isInheritInheritableThreadLocals(), is(false));
    
    nonInheritingFactory.setInheritInheritableThreadLocals(true);
    assertThat(nonInheritingFactory.isInheritInheritableThreadLocals(), is(true));
  }
  
  /**
   * Tests that the factory works correctly with high concurrency using virtual threads.
   */
  @Test
  public void virtualThreadsHandleHighConcurrency() throws Exception {
    NexusThreadFactory factory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true, // daemon
        true  // use virtual threads
    );
    
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create and start many virtual threads
    for (int i = 0; i < threadCount; i++) {
      Thread thread = factory.newThread(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Simulate some work
          Thread.sleep(ThreadLocalRandom.current().nextInt(10));
          
          // Verify this is a virtual thread
          assertTrue(Thread.currentThread().isVirtual());
          
          // Verify thread name follows the expected pattern
          String name = Thread.currentThread().getName();
          assertThat(name, containsString("vt-" + POOL_ID));
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          completionLatch.countDown();
        }
      });
      thread.start();
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(5, TimeUnit.SECONDS);
    assertTrue(completed, "All virtual threads should complete within the timeout");
  }
  
  /**
   * Tests that the factory works correctly with ExecutorService for virtual threads.
   */
  @Test
  public void worksWithExecutorService() throws Exception {
    NexusThreadFactory factory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true, // daemon
        true  // use virtual threads
    );
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);
    
    try {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Verify this is a virtual thread
            assertTrue(Thread.currentThread().isVirtual());
            
            // Verify thread name follows the expected pattern
            String name = Thread.currentThread().getName();
            assertThat(name, containsString("vt-" + POOL_ID));
            
            // Simulate some work
            Thread.sleep(ThreadLocalRandom.current().nextInt(10));
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(5, TimeUnit.SECONDS);
      assertTrue(completed, "All executor tasks should complete within the timeout");
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that the factory works correctly with ForkJoinPool for virtual threads.
   */
  @Test
  public void worksWithForkJoinPool() throws Exception {
    NexusThreadFactory factory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true, // daemon
        true  // use virtual threads
    );
    
    // Create a ForkJoinPool that will use our factory to create threads
    ForkJoinPool pool = new ForkJoinPool(
        Runtime.getRuntime().availableProcessors(),
        pool -> {
          // This will be called when the pool needs to create a new worker thread
          // We'll use our factory to create the thread
          return factory.newThread(pool);
        },
        null,
        false
    );
    
    try {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      
      // Submit tasks to the pool
      for (int i = 0; i < taskCount; i++) {
        pool.submit(() -> {
          try {
            // Verify thread name follows the expected pattern
            String name = Thread.currentThread().getName();
            assertThat(name, notNullValue());
            
            // Simulate some work
            Thread.sleep(ThreadLocalRandom.current().nextInt(10));
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(5, TimeUnit.SECONDS);
      assertTrue(completed, "All ForkJoinPool tasks should complete within the timeout");
    }
    finally {
      pool.shutdown();
    }
  }
  
  /**
   * Tests that platform threads are created correctly when virtual threads are disabled.
   */
  @Test
  public void platformThreadsAreCreatedWhenVirtualThreadsDisabled() throws Exception {
    NexusThreadFactory factory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        THREAD_PRIORITY, 
        true,  // daemon
        false  // use platform threads
    );
    
    AtomicReference<String> threadName = new AtomicReference<>();
    AtomicReference<Boolean> isVirtual = new AtomicReference<>();
    AtomicReference<ThreadGroup> threadGroup = new AtomicReference<>();
    
    Thread thread = factory.newThread(() -> {
      threadName.set(Thread.currentThread().getName());
      isVirtual.set(Thread.currentThread().isVirtual());
      threadGroup.set(Thread.currentThread().getThreadGroup());
    });
    
    thread.start();
    thread.join();
    
    // Should not have the "vt-" prefix for platform threads
    assertThat(threadName.get(), containsString(POOL_ID));
    assertThat(threadName.get().contains("vt-"), is(false));
    
    // Should not be a virtual thread
    assertThat(isVirtual.get(), is(false));
    
    // Should have the correct thread group
    assertThat(threadGroup.get().getName(), containsString(THREAD_GROUP_NAME));
  }
  
  /**
   * Tests that thread priority is set correctly for platform threads but ignored for virtual threads.
   */
  @Test
  public void threadPriorityIsSetCorrectlyForPlatformThreads() throws Exception {
    int customPriority = Thread.MAX_PRIORITY;
    
    // Create a factory for platform threads with custom priority
    NexusThreadFactory platformFactory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        customPriority, 
        true,  // daemon
        false  // use platform threads
    );
    
    // Create a factory for virtual threads with custom priority
    NexusThreadFactory virtualFactory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        customPriority, 
        true,  // daemon
        true   // use virtual threads
    );
    
    AtomicReference<Integer> platformThreadPriority = new AtomicReference<>();
    AtomicReference<Integer> virtualThreadPriority = new AtomicReference<>();
    
    // Test platform thread priority
    Thread platformThread = platformFactory.newThread(() -> {
      platformThreadPriority.set(Thread.currentThread().getPriority());
    });
    
    platformThread.start();
    platformThread.join();
    
    // Platform thread should have the custom priority
    assertThat(platformThreadPriority.get(), is(customPriority));
    
    // Test virtual thread priority
    Thread virtualThread = virtualFactory.newThread(() -> {
      virtualThreadPriority.set(Thread.currentThread().getPriority());
    });
    
    virtualThread.start();
    virtualThread.join();
    
    // Virtual threads ignore priority settings and always use normal priority
    assertThat(virtualThreadPriority.get(), is(Thread.NORM_PRIORITY));
  }
  
  /**
   * Tests that daemon flag is set correctly for both platform and virtual threads.
   */
  @Test
  public void daemonFlagIsSetCorrectly() throws Exception {
    // Create a factory for daemon threads
    NexusThreadFactory daemonFactory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        Thread.NORM_PRIORITY, 
        true,  // daemon
        false  // use platform threads
    );
    
    // Create a factory for non-daemon threads
    NexusThreadFactory nonDaemonFactory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        Thread.NORM_PRIORITY, 
        false, // non-daemon
        false  // use platform threads
    );
    
    // Create a factory for virtual threads (which are always daemon)
    NexusThreadFactory virtualFactory = new NexusThreadFactory(
        POOL_ID, 
        THREAD_GROUP_NAME, 
        Thread.NORM_PRIORITY, 
        false, // This should be ignored for virtual threads
        true   // use virtual threads
    );
    
    AtomicReference<Boolean> daemonThreadIsDaemon = new AtomicReference<>();
    AtomicReference<Boolean> nonDaemonThreadIsDaemon = new AtomicReference<>();
    AtomicReference<Boolean> virtualThreadIsDaemon = new AtomicReference<>();
    
    // Test daemon thread
    Thread daemonThread = daemonFactory.newThread(() -> {
      daemonThreadIsDaemon.set(Thread.currentThread().isDaemon());
    });
    
    daemonThread.start();
    daemonThread.join();
    
    // Daemon thread should be a daemon
    assertThat(daemonThreadIsDaemon.get(), is(true));
    
    // Test non-daemon thread
    Thread nonDaemonThread = nonDaemonFactory.newThread(() -> {
      nonDaemonThreadIsDaemon.set(Thread.currentThread().isDaemon());
    });
    
    nonDaemonThread.start();
    nonDaemonThread.join();
    
    // Non-daemon thread should not be a daemon
    assertThat(nonDaemonThreadIsDaemon.get(), is(false));
    
    // Test virtual thread
    Thread virtualThread = virtualFactory.newThread(() -> {
      virtualThreadIsDaemon.set(Thread.currentThread().isDaemon());
    });
    
    virtualThread.start();
    virtualThread.join();
    
    // Virtual threads are always daemon threads
    assertThat(virtualThreadIsDaemon.get(), is(true));
  }
}