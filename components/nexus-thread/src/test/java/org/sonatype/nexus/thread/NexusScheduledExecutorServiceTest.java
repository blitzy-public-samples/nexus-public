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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.security.subject.FakeAlmightySubject;

import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link NexusScheduledExecutorService}.
 *
 * @since 3.60
 */
public class NexusScheduledExecutorServiceTest
{
  private ScheduledExecutorService delegate;
  private Subject subject;

  @BeforeEach
  public void setUp() {
    delegate = Executors.newSingleThreadScheduledExecutor();
    subject = FakeAlmightySubject.TASK_SUBJECT;
  }

  @AfterEach
  public void tearDown() {
    if (delegate != null) {
      delegate.shutdown();
    }
  }

  @Test
  public void testForFixedSubject() throws Exception {
    // Given
    NexusScheduledExecutorService service = NexusScheduledExecutorService.forFixedSubject(delegate, subject);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Subject> executionSubject = new AtomicReference<>();

    // When
    service.schedule(() -> {
      executionSubject.set(subject);
      latch.countDown();
    }, 100, TimeUnit.MILLISECONDS);

    // Then
    assertThat(latch.await(1, TimeUnit.SECONDS), is(true));
    assertThat(executionSubject.get(), is(subject));
  }

  @Test
  public void testForCurrentSubject() throws Exception {
    // Given
    NexusScheduledExecutorService service = NexusScheduledExecutorService.forCurrentSubject(delegate);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Subject> executionSubject = new AtomicReference<>();

    // When
    service.schedule(() -> {
      executionSubject.set(subject);
      latch.countDown();
    }, 100, TimeUnit.MILLISECONDS);

    // Then
    assertThat(latch.await(1, TimeUnit.SECONDS), is(true));
    assertThat(executionSubject.get(), notNullValue());
  }

  @Test
  public void testForFixedSubjectWithVirtualThreads() throws Exception {
    // Given
    NexusScheduledExecutorService service = NexusScheduledExecutorService.forFixedSubjectWithVirtualThreads(subject);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Subject> executionSubject = new AtomicReference<>();
    AtomicReference<String> threadName = new AtomicReference<>();

    // When
    service.schedule(() -> {
      executionSubject.set(subject);
      threadName.set(Thread.currentThread().getName());
      latch.countDown();
    }, 100, TimeUnit.MILLISECONDS);

    // Then
    assertThat(latch.await(1, TimeUnit.SECONDS), is(true));
    assertThat(executionSubject.get(), is(subject));
    assertThat(threadName.get(), containsString("nexus-virtual-"));
    
    // Cleanup
    service.shutdown();
  }

  @Test
  public void testForCurrentSubjectWithVirtualThreads() throws Exception {
    // Given
    NexusScheduledExecutorService service = NexusScheduledExecutorService.forCurrentSubjectWithVirtualThreads();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Subject> executionSubject = new AtomicReference<>();
    AtomicReference<String> threadName = new AtomicReference<>();

    // When
    service.schedule(() -> {
      executionSubject.set(subject);
      threadName.set(Thread.currentThread().getName());
      latch.countDown();
    }, 100, TimeUnit.MILLISECONDS);

    // Then
    assertThat(latch.await(1, TimeUnit.SECONDS), is(true));
    assertThat(executionSubject.get(), notNullValue());
    assertThat(threadName.get(), containsString("nexus-virtual-"));
    
    // Cleanup
    service.shutdown();
  }
}