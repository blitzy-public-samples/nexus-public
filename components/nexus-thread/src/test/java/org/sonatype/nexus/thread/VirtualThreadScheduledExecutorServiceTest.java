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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link NexusScheduledExecutorService} with virtual threads.
 *
 * @since 3.60
 */
public class VirtualThreadScheduledExecutorServiceTest
    extends TestSupport
{
  @Mock
  private Subject subject;

  private ScheduledExecutorService executorService;

  @Before
  public void setUp() {
    when(subject.toString()).thenReturn("test-subject");
    executorService = NexusScheduledExecutorService.forFixedSubjectWithVirtualThreads(1, subject);
  }

  @After
  public void tearDown() {
    if (executorService != null) {
      executorService.shutdownNow();
    }
  }

  @Test
  public void testScheduleRunnable() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> threadName = new AtomicReference<>();

    executorService.schedule(() -> {
      threadName.set(Thread.currentThread().toString());
      latch.countDown();
    }, 100, TimeUnit.MILLISECONDS);

    assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
    assertThat(threadName.get(), containsString("VirtualThread"));
  }

  @Test
  public void testScheduleCallable() throws Exception {
    AtomicReference<String> threadName = new AtomicReference<>();

    String result = executorService.schedule(() -> {
      threadName.set(Thread.currentThread().toString());
      return "done";
    }, 100, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);

    assertThat(result, is("done"));
    assertThat(threadName.get(), containsString("VirtualThread"));
  }

  @Test
  public void testScheduleAtFixedRate() throws Exception {
    CountDownLatch latch = new CountDownLatch(3);
    AtomicReference<String> threadName = new AtomicReference<>();
    AtomicBoolean cancel = new AtomicBoolean(false);

    ScheduledFuture<?> future = executorService.scheduleAtFixedRate(() -> {
      threadName.set(Thread.currentThread().toString());
      latch.countDown();
      if (cancel.get()) {
        throw new RuntimeException("Cancelling");
      }
    }, 100, 100, TimeUnit.MILLISECONDS);

    assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
    cancel.set(true);
    Thread.sleep(200); // Allow time for the task to be cancelled
    future.cancel(true);

    assertThat(threadName.get(), containsString("VirtualThread"));
  }

  @Test
  public void testScheduleWithFixedDelay() throws Exception {
    CountDownLatch latch = new CountDownLatch(3);
    AtomicReference<String> threadName = new AtomicReference<>();
    AtomicBoolean cancel = new AtomicBoolean(false);

    ScheduledFuture<?> future = executorService.scheduleWithFixedDelay(() -> {
      threadName.set(Thread.currentThread().toString());
      latch.countDown();
      if (cancel.get()) {
        throw new RuntimeException("Cancelling");
      }
    }, 100, 100, TimeUnit.MILLISECONDS);

    assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
    cancel.set(true);
    Thread.sleep(200); // Allow time for the task to be cancelled
    future.cancel(true);

    assertThat(threadName.get(), containsString("VirtualThread"));
  }

  @Test
  public void testExecute() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> threadName = new AtomicReference<>();

    executorService.execute(() -> {
      threadName.set(Thread.currentThread().toString());
      latch.countDown();
    });

    assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
    assertThat(threadName.get(), containsString("VirtualThread"));
  }
}