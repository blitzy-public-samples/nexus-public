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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Assertions;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VirtualThreadFactory}.
 *
 * @since 3.60
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class VirtualThreadFactoryTest
{
  private static final String POOL_ID = "test-pool";
  private static final String THREAD_GROUP_NAME = "test-group";
  
  private VirtualThreadFactory virtualThreadFactory;
  private ExecutorService executorService;

  @BeforeEach
  public void setUp() {
    virtualThreadFactory = new VirtualThreadFactory(POOL_ID, THREAD_GROUP_NAME);
    executorService = VirtualThreadFactory.newExecutorService(POOL_ID, THREAD_GROUP_NAME);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (executorService != null) {
      executorService.shutdown();
      executorService.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testCreateVirtualThread() throws Exception {
    AtomicBoolean executed = new AtomicBoolean(false);
    Thread thread = virtualThreadFactory.newThread(() -> executed.set(true));
    
    assertThat(thread, notNullValue());
    assertTrue(thread.isVirtual(), "Thread should be a virtual thread");
    
    thread.start();
    thread.join(1000);
    
    assertTrue(executed.get(), "Thread should have executed the runnable");
    
    // Verify thread state after execution
    Assertions.assertEquals(Thread.State.TERMINATED, thread.getState(), "Thread should be terminated");
  }

  @Test
  public void testThreadNaming() {
    Thread thread = virtualThreadFactory.newThread(() -> {});
    
    assertThat(thread.getName(), startsWith(POOL_ID));
    assertThat(thread.getName(), containsString("vthread"));
    
    // Verify the naming pattern: poolId-poolNumber-vthread-threadNumber
    String[] parts = thread.getName().split("-");
    assertThat(parts.length, is(4));
    assertThat(parts[0], is(POOL_ID));
    assertThat(parts[2], is("vthread"));
  }

  @Test
  public void testMultipleThreadsGetUniqueNames() {
    Thread thread1 = virtualThreadFactory.newThread(() -> {});
    Thread thread2 = virtualThreadFactory.newThread(() -> {});
    
    assertThat(thread1.getName(), is(not(thread2.getName())));
    assertThat(thread1.getName(), startsWith(POOL_ID));
    assertThat(thread2.getName(), startsWith(POOL_ID));
  }

  @Test
  public void testThreadLocalInheritance() throws Exception {
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("parent-value");
    
    // Create factory with inheritance enabled
    VirtualThreadFactory factoryWithInheritance = new VirtualThreadFactory(POOL_ID, THREAD_GROUP_NAME, true);
    
    AtomicReference<String> valueInThread = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = factoryWithInheritance.newThread(() -> {
      valueInThread.set(threadLocal.get());
      latch.countDown();
    });
    
    thread.start();
    latch.await(1, TimeUnit.SECONDS);
    
    assertThat(valueInThread.get(), is("parent-value"));
  }

  @Test
  public void testThreadLocalNonInheritance() throws Exception {
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("parent-value");
    
    // Create factory with inheritance disabled
    VirtualThreadFactory factoryWithoutInheritance = new VirtualThreadFactory(POOL_ID, THREAD_GROUP_NAME, false);
    
    AtomicReference<String> valueInThread = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = factoryWithoutInheritance.newThread(() -> {
      valueInThread.set(threadLocal.get());
      latch.countDown();
    });
    
    thread.start();
    latch.await(1, TimeUnit.SECONDS);
    
    assertThat(valueInThread.get(), is(nullValue()));
  }

  @Test
  public void testExecutorServiceCreation() throws Exception {
    AtomicBoolean executed = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    
    executorService.submit(() -> {
      executed.set(true);
      latch.countDown();
    });
    
    latch.await(1, TimeUnit.SECONDS);
    assertTrue(executed.get(), "Task should have been executed");
  }

  @Test
  public void testBuilderMethod() {
    Thread thread = VirtualThreadFactory.builder(POOL_ID, THREAD_GROUP_NAME)
        .newThread(() -> {});
    
    assertThat(thread, notNullValue());
    assertTrue(thread.isVirtual(), "Thread should be a virtual thread");
    assertThat(thread.getName(), startsWith(POOL_ID));
    assertThat(thread.getName(), containsString("vthread"));
  }
  
  @Test
  public void testVirtualThreadCharacteristics() {
    Thread thread = virtualThreadFactory.newThread(() -> {});
    
    // Virtual threads have specific characteristics
    assertTrue(thread.isVirtual(), "Thread should be a virtual thread");
    Assertions.assertFalse(thread.isDaemon(), "Virtual threads are not daemon threads by default");
    Assertions.assertEquals(Thread.NORM_PRIORITY, thread.getPriority(), "Virtual threads should have normal priority");
    
    // Virtual threads don't have a thread group in the traditional sense
    Assertions.assertNull(thread.getThreadGroup(), "Virtual threads don't have a thread group");
  }
}