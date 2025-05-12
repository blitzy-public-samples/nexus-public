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
package org.sonatype.nexus.thread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests for {@link VirtualThreadFactory}.
 *
 * @since 3.60
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class VirtualThreadFactoryTest
    extends TestSupport
{
  private static final String POOL_ID = "test-pool";
  private static final String THREAD_GROUP_NAME = "test-group";

  /**
   * Verifies that the factory creates virtual threads with the expected naming pattern.
   */
  @Test
  public void virtualThreadsHaveExpectedNaming() throws Exception {
    VirtualThreadFactory factory = new VirtualThreadFactory(POOL_ID, THREAD_GROUP_NAME);
    
    AtomicReference<String> threadName = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = factory.newThread(() -> {
      threadName.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    // Start the thread and wait for it to complete
    thread.start();
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the thread name follows the expected pattern
    assertThat(threadName.get(), startsWith(POOL_ID));
    assertThat(threadName.get(), containsString("-vthread-"));
  }

  /**
   * Verifies that the factory creates actual virtual threads (not platform threads).
   */
  @Test
  public void createsVirtualThreads() throws Exception {
    VirtualThreadFactory factory = new VirtualThreadFactory(POOL_ID, THREAD_GROUP_NAME);
    
    AtomicReference<Boolean> isVirtual = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = factory.newThread(() -> {
      isVirtual.set(Thread.currentThread().isVirtual());
      latch.countDown();
    });
    
    // Start the thread and wait for it to complete
    thread.start();
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the thread is actually a virtual thread
    assertThat(isVirtual.get(), is(true));
  }

  /**
   * Verifies that thread-local inheritance works correctly with the default constructor.
   */
  @Test
  public void threadLocalInheritanceWorks() throws Exception {
    VirtualThreadFactory factory = new VirtualThreadFactory(POOL_ID, THREAD_GROUP_NAME);
    
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("parent-value");
    
    AtomicReference<String> childValue = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = factory.newThread(() -> {
      childValue.set(threadLocal.get());
      latch.countDown();
    });
    
    // Start the thread and wait for it to complete
    thread.start();
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify thread-local value was inherited
    assertThat(childValue.get(), is("parent-value"));
  }

  /**
   * Verifies that thread-local inheritance can be disabled.
   */
  @Test
  public void threadLocalInheritanceCanBeDisabled() throws Exception {
    VirtualThreadFactory factory = new VirtualThreadFactory(POOL_ID, THREAD_GROUP_NAME, false);
    
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("parent-value");
    
    AtomicReference<String> childValue = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = factory.newThread(() -> {
      childValue.set(threadLocal.get());
      latch.countDown();
    });
    
    // Start the thread and wait for it to complete
    thread.start();
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify thread-local value was NOT inherited
    assertThat(childValue.get(), is((String) null));
  }

  /**
   * Verifies that the builder method creates a valid thread factory.
   */
  @Test
  public void builderMethodCreatesValidFactory() throws Exception {
    VirtualThreadFactory factory = (VirtualThreadFactory) VirtualThreadFactory.builder(POOL_ID, THREAD_GROUP_NAME);
    
    AtomicReference<String> threadName = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = factory.newThread(() -> {
      threadName.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    // Start the thread and wait for it to complete
    thread.start();
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the thread name follows the expected pattern
    assertThat(threadName.get(), startsWith(POOL_ID));
  }

  /**
   * Verifies that the executor service factory method creates a working executor service.
   */
  @Test
  public void executorServiceFactoryCreatesWorkingExecutor() throws Exception {
    ExecutorService executor = VirtualThreadFactory.newExecutorService(POOL_ID, THREAD_GROUP_NAME);
    
    AtomicReference<Boolean> isVirtual = new AtomicReference<>();
    AtomicReference<String> threadName = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    executor.submit(() -> {
      isVirtual.set(Thread.currentThread().isVirtual());
      threadName.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    // Wait for the task to complete
    latch.await(5, TimeUnit.SECONDS);
    executor.shutdown();
    
    // Verify the executor used a virtual thread with the expected naming pattern
    assertThat(isVirtual.get(), is(true));
    assertThat(threadName.get(), startsWith(POOL_ID));
  }

  /**
   * Verifies that multiple threads created by the same factory have unique names.
   */
  @Test
  public void multipleThreadsHaveUniqueNames() throws Exception {
    VirtualThreadFactory factory = new VirtualThreadFactory(POOL_ID, THREAD_GROUP_NAME);
    
    AtomicReference<String> threadName1 = new AtomicReference<>();
    AtomicReference<String> threadName2 = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(2);
    
    Thread thread1 = factory.newThread(() -> {
      threadName1.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    Thread thread2 = factory.newThread(() -> {
      threadName2.set(Thread.currentThread().getName());
      latch.countDown();
    });
    
    // Start the threads and wait for them to complete
    thread1.start();
    thread2.start();
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the thread names are different
    assertThat(threadName1.get(), is(notNullValue()));
    assertThat(threadName2.get(), is(notNullValue()));
    assertThat(threadName1.get().equals(threadName2.get()), is(false));
  }
}