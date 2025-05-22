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
package org.sonatype.nexus.pax.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.logging.task.TaskLogger;
import org.sonatype.nexus.logging.task.TaskLoggerHelper;
import org.sonatype.nexus.logging.task.TaskLoggingEvent;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.slf4j.MDC;
import org.slf4j.Marker;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.sonatype.nexus.logging.task.TaskLogger.LOGBACK_TASK_DISCRIMINATOR_ID;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.NEXUS_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;

/**
 * Tests for {@link TaskLogsFilter} when executed within Java 21 Virtual Threads.
 * Verifies that task context is properly maintained across virtual thread boundaries,
 * markers are correctly handled when events are generated from virtual threads,
 * and the filter's decision logic works consistently in a virtual thread environment.
 */
public class TaskLogsFilterVirtualThreadTest
    extends TestSupport
{
  private static final String TEST_MESSAGE = "test message";

  private static final Object[] TEST_ARGS = new Object[]{"x"};

  @Mock
  private TaskLogger taskLogger;

  private TaskLogsFilter taskLogsFilter;

  private ExecutorService virtualThreadExecutor;

  @Before
  public void setUp() {
    taskLogsFilter = new TaskLogsFilter();
    // Create a virtual thread executor using Java 21's virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @After
  public void tearDown() throws Exception {
    MDC.remove(LOGBACK_TASK_DISCRIMINATOR_ID);
    if (TaskLoggerHelper.get() != null) {
      TaskLoggerHelper.finish();
    }
    // Shutdown the virtual thread executor
    virtualThreadExecutor.shutdown();
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
  }

  /**
   * Test that the filter correctly denies events when not in a task context,
   * even when executed in a virtual thread.
   */
  @Test
  public void testNotATaskInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      // Ensure we're not in a task context
      MDC.remove(LOGBACK_TASK_DISCRIMINATOR_ID);
      assertThat(taskLogsFilter.decide(eventWithMarkerOf(null)), equalTo(DENY));
    }, virtualThreadExecutor);
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Test that the filter correctly denies events with NEXUS_LOG_ONLY marker
   * when executed in a virtual thread.
   */
  @Test
  public void testIsANexusLogInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      startTask();
      assertThat(taskLogsFilter.decide(eventWithMarkerOf(NEXUS_LOG_ONLY)), equalTo(DENY));
    }, virtualThreadExecutor);
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Test that the filter correctly denies events with INTERNAL_PROGRESS marker
   * when executed in a virtual thread.
   */
  @Test
  public void testIsInternalProgressInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      startTask();
      assertThat(taskLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS)), equalTo(DENY));
    }, virtualThreadExecutor);
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Test that the filter correctly handles events with PROGRESS marker
   * when executed in a virtual thread, and that the task logger receives the progress event.
   */
  @Test
  public void testIsProgressInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      startTask();
      assertThat(taskLogsFilter.decide(eventWithMarkerOf(PROGRESS)), equalTo(NEUTRAL));
      assertNotNull(TaskLoggerHelper.get());

      ArgumentCaptor<TaskLoggingEvent> argumentCaptor = ArgumentCaptor.forClass(TaskLoggingEvent.class);
      verify(taskLogger).progress(argumentCaptor.capture());
      TaskLoggingEvent taskLoggingEvent = argumentCaptor.getValue();
      assertNotNull(taskLoggingEvent);
      assertThat(taskLoggingEvent.getMessage(), equalTo(TEST_MESSAGE));
      assertThat(taskLoggingEvent.getArgumentArray(), equalTo(TEST_ARGS));
    }, virtualThreadExecutor);
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Test that the filter correctly handles events without a specific marker
   * when executed in a virtual thread.
   */
  @Test
  public void testNotProgressInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      startTask();
      assertThat(taskLogsFilter.decide(eventWithMarkerOf(null)), equalTo(NEUTRAL));
      assertNotNull(TaskLoggerHelper.get());
    }, virtualThreadExecutor);
    
    future.get(5, TimeUnit.SECONDS);
  }

  /**
   * Test that the filter correctly handles events across multiple concurrent virtual threads,
   * verifying that task context is properly maintained and isolated between threads.
   */
  @Test
  public void testConcurrentVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger progressCount = new AtomicInteger(0);
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    // Create multiple virtual threads that will execute concurrently
    for (int i = 0; i < threadCount; i++) {
      final String taskId = "task-" + i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Set up a unique task context for each virtual thread
          MDC.put(LOGBACK_TASK_DISCRIMINATOR_ID, taskId);
          TaskLoggerHelper.start(taskLogger);
          
          // Test with different markers to exercise all code paths
          if (taskId.hashCode() % 3 == 0) {
            assertThat(taskLogsFilter.decide(eventWithMarkerOf(PROGRESS)), equalTo(NEUTRAL));
            progressCount.incrementAndGet();
          } else if (taskId.hashCode() % 3 == 1) {
            assertThat(taskLogsFilter.decide(eventWithMarkerOf(NEXUS_LOG_ONLY)), equalTo(DENY));
          } else {
            assertThat(taskLogsFilter.decide(eventWithMarkerOf(null)), equalTo(NEUTRAL));
          }
          
          // Verify task context is maintained
          assertNotNull(TaskLoggerHelper.get());
          assertThat(MDC.get(LOGBACK_TASK_DISCRIMINATOR_ID), equalTo(taskId));
        } finally {
          // Clean up task context
          if (TaskLoggerHelper.get() != null) {
            TaskLoggerHelper.finish();
          }
          MDC.remove(LOGBACK_TASK_DISCRIMINATOR_ID);
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all virtual threads to complete
    latch.await(30, TimeUnit.SECONDS);
    
    // Verify all futures completed successfully
    for (CompletableFuture<Void> future : futures) {
      future.get(1, TimeUnit.SECONDS); // This will throw if any future completed exceptionally
    }
    
    // Verify the progress marker was processed the expected number of times
    // Each thread with taskId % 3 == 0 should have triggered a progress event
    verify(taskLogger, times(progressCount.get())).progress(ArgumentCaptor.forClass(TaskLoggingEvent.class).capture());
  }

  private void startTask() {
    // default is a task
    MDC.put(LOGBACK_TASK_DISCRIMINATOR_ID, "taskId");

    TaskLoggerHelper.start(taskLogger);
  }

  private ILoggingEvent eventWithMarkerOf(final Marker marker) {
    LoggingEvent event = new LoggingEvent();
    event.setMessage(TEST_MESSAGE);
    event.setMarker(marker);
    event.setArgumentArray(TEST_ARGS);
    return event;
  }
}