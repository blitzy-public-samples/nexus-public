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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
 import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.logging.task.TaskLogger;
import org.sonatype.nexus.logging.task.TaskLoggerHelper;
import org.sonatype.nexus.logging.task.TaskLoggingEvent;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.slf4j.Marker;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.logging.task.TaskLogger.LOGBACK_TASK_DISCRIMINATOR_ID;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.NEXUS_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;

@ExtendWith(MockitoExtension.class)
public class TaskLogsFilterTest
    extends TestSupport
{
  private static final String TEST_MESSAGE = "test message";

  private static final Object[] TEST_ARGS = new Object[]{"x"};

  @Mock
  private TaskLogger taskLogger;

  private TaskLogsFilter taskLogsFilter;

  @BeforeEach
  public void setUp() {
    taskLogsFilter = new TaskLogsFilter();
  }

  @AfterEach
  public void tearDown() {
    MDC.remove(LOGBACK_TASK_DISCRIMINATOR_ID);
    if (TaskLoggerHelper.get() != null) {
      TaskLoggerHelper.finish();
    }
  }

  @Test
  public void testNotATask() {
    // not a task
    MDC.remove(LOGBACK_TASK_DISCRIMINATOR_ID);
    assertThat(taskLogsFilter.decide(eventWithMarkerOf(null)), equalTo(DENY));
  }

  @Test
  public void testIsANexusLog() {
    startTask();
    assertThat(taskLogsFilter.decide(eventWithMarkerOf(NEXUS_LOG_ONLY)), equalTo(DENY));
  }

  @Test
  public void testIsInternalProgress() {
    startTask();
    assertThat(taskLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS)), equalTo(DENY));
  }

  @Test
  public void testIsProgress() {
    startTask();
    assertThat(taskLogsFilter.decide(eventWithMarkerOf(PROGRESS)), equalTo(NEUTRAL));
    assertNotNull(TaskLoggerHelper.get());

    ArgumentCaptor<TaskLoggingEvent> argumentCaptor = ArgumentCaptor.forClass(TaskLoggingEvent.class);
    verify(taskLogger).progress(argumentCaptor.capture());
    TaskLoggingEvent taskLoggingEvent = argumentCaptor.getValue();
    assertNotNull(taskLoggingEvent);
    assertThat(taskLoggingEvent.getMessage(), equalTo(TEST_MESSAGE));
    assertThat(taskLoggingEvent.getArgumentArray(), equalTo(TEST_ARGS));
  }

  @Test
  public void testNotProgress() {
    startTask();
    assertThat(taskLogsFilter.decide(eventWithMarkerOf(null)), equalTo(NEUTRAL));
    assertNotNull(TaskLoggerHelper.get());
  }

  @Test
  public void testMDCPropagationInVirtualThread() throws Exception {
    // Test that MDC context is properly propagated to virtual threads
    AtomicReference<String> mdcValueInVirtualThread = new AtomicReference<>();
    
    // Set MDC in main thread
    MDC.put(LOGBACK_TASK_DISCRIMINATOR_ID, "virtualThreadTaskId");
    TaskLoggerHelper.start(taskLogger);
    
    // Run in virtual thread and capture MDC value
    runInVirtualThread(() -> {
      mdcValueInVirtualThread.set(MDC.get(LOGBACK_TASK_DISCRIMINATOR_ID));
      return null;
    });
    
    // Verify MDC was properly propagated
    assertNotNull(mdcValueInVirtualThread.get());
    assertThat(mdcValueInVirtualThread.get(), equalTo("virtualThreadTaskId"));
  }

  @Test
  public void testTaskProgressInVirtualThread() throws Exception {
    // Test that task progress logging works correctly in virtual threads
    startTask();
    
    // Run task progress logging in virtual thread
    runInVirtualThread(() -> {
      ILoggingEvent event = eventWithMarkerOf(PROGRESS);
      return taskLogsFilter.decide(event);
    });
    
    // Verify progress was captured
    ArgumentCaptor<TaskLoggingEvent> argumentCaptor = ArgumentCaptor.forClass(TaskLoggingEvent.class);
    verify(taskLogger).progress(argumentCaptor.capture());
    TaskLoggingEvent taskLoggingEvent = argumentCaptor.getValue();
    
    assertNotNull(taskLoggingEvent);
    assertThat(taskLoggingEvent.getMessage(), equalTo(TEST_MESSAGE));
    assertThat(taskLoggingEvent.getArgumentArray(), equalTo(TEST_ARGS));
  }

  @Test
  public void testConcurrentVirtualThreadTaskLogging() throws Exception {
    // Test concurrent task logging with multiple virtual threads
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Start task in main thread
    startTask();
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < threadCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Log with progress marker in each virtual thread
            LoggingEvent event = new LoggingEvent();
            event.setMessage(TEST_MESSAGE + "-" + taskId);
            event.setMarker(PROGRESS);
            event.setArgumentArray(TEST_ARGS);
            
            taskLogsFilter.decide(event);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(5, TimeUnit.SECONDS);
    }
    
    // Verify task logger was called for each thread
    ArgumentCaptor<TaskLoggingEvent> argumentCaptor = ArgumentCaptor.forClass(TaskLoggingEvent.class);
    verify(taskLogger, org.mockito.Mockito.atLeast(threadCount)).progress(argumentCaptor.capture());
  }

  /**
   * Helper method to run code in a virtual thread and return its result.
   */
  private <T> T runInVirtualThread(java.util.function.Supplier<T> task) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Exception> exception = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        result.set(task.get());
      } catch (Exception e) {
        exception.set(e);
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    
    if (exception.get() != null) {
      throw exception.get();
    }
    
    return result.get();
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