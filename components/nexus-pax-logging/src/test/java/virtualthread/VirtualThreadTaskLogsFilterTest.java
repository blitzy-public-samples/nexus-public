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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.logging.task.TaskLogger;
import org.sonatype.nexus.logging.task.TaskLoggerHelper;
import org.sonatype.nexus.logging.task.TaskLoggingEvent;
import org.sonatype.nexus.pax.logging.TaskLogsFilter;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.slf4j.Marker;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.sonatype.nexus.logging.task.TaskLogger.LOGBACK_TASK_DISCRIMINATOR_ID;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.NEXUS_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;

/**
 * Tests {@link TaskLogsFilter#decide(ILoggingEvent)} functionality specifically in a Java 21 virtual thread environment
 * to ensure that Logback discriminator ID and Marker combinations are correctly handled when accessed from virtual threads.
 */
@ExtendWith(MockitoExtension.class)
@Tag("virtualthread")
public class VirtualThreadTaskLogsFilterTest
    extends VirtualThreadTestSupport
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
  public void testNotATaskInVirtualThread() throws Exception {
    // Run the test in a virtual thread
    Thread vThread = Thread.ofVirtual().start(() -> {
      // not a task
      MDC.remove(LOGBACK_TASK_DISCRIMINATOR_ID);
      FilterReply result = taskLogsFilter.decide(eventWithMarkerOf(null));
      assertThat(result, equalTo(DENY));
    });
    
    // Wait for the virtual thread to complete
    vThread.join();
  }

  @Test
  public void testIsANexusLogInVirtualThread() throws Exception {
    // Run the test in a virtual thread
    Thread vThread = Thread.ofVirtual().start(() -> {
      startTask();
      FilterReply result = taskLogsFilter.decide(eventWithMarkerOf(NEXUS_LOG_ONLY));
      assertThat(result, equalTo(DENY));
    });
    
    // Wait for the virtual thread to complete
    vThread.join();
  }

  @Test
  public void testIsInternalProgressInVirtualThread() throws Exception {
    // Run the test in a virtual thread
    Thread vThread = Thread.ofVirtual().start(() -> {
      startTask();
      FilterReply result = taskLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS));
      assertThat(result, equalTo(DENY));
    });
    
    // Wait for the virtual thread to complete
    vThread.join();
  }

  @Test
  public void testIsProgressInVirtualThread() throws Exception {
    // Run the test in a virtual thread
    Thread vThread = Thread.ofVirtual().start(() -> {
      startTask();
      FilterReply result = taskLogsFilter.decide(eventWithMarkerOf(PROGRESS));
      assertThat(result, equalTo(NEUTRAL));
      assertNotNull(TaskLoggerHelper.get());

      ArgumentCaptor<TaskLoggingEvent> argumentCaptor = ArgumentCaptor.forClass(TaskLoggingEvent.class);
      verify(taskLogger).progress(argumentCaptor.capture());
      TaskLoggingEvent taskLoggingEvent = argumentCaptor.getValue();
      assertNotNull(taskLoggingEvent);
      assertThat(taskLoggingEvent.getMessage(), equalTo(TEST_MESSAGE));
      assertThat(taskLoggingEvent.getArgumentArray(), equalTo(TEST_ARGS));
    });
    
    // Wait for the virtual thread to complete
    vThread.join();
  }

  @Test
  public void testNotProgressInVirtualThread() throws Exception {
    // Run the test in a virtual thread
    Thread vThread = Thread.ofVirtual().start(() -> {
      startTask();
      FilterReply result = taskLogsFilter.decide(eventWithMarkerOf(null));
      assertThat(result, equalTo(NEUTRAL));
      assertNotNull(TaskLoggerHelper.get());
    });
    
    // Wait for the virtual thread to complete
    vThread.join();
  }

  @Test
  public void testConcurrentTaskLoggingInVirtualThreads() throws Exception {
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicReference<Exception> testException = new AtomicReference<>();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple tasks
      for (int i = 0; i < threadCount; i++) {
        final String taskId = "task-" + i;
        executor.submit(() -> {
          try {
            // Wait for all threads to start at the same time
            startLatch.await();
            
            // Set up task context for this virtual thread
            MDC.put(LOGBACK_TASK_DISCRIMINATOR_ID, taskId);
            TaskLoggerHelper.start(taskLogger);
            
            // Test different marker combinations
            assertThat(taskLogsFilter.decide(eventWithMarkerOf(NEXUS_LOG_ONLY)), equalTo(DENY));
            assertThat(taskLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS)), equalTo(DENY));
            assertThat(taskLogsFilter.decide(eventWithMarkerOf(PROGRESS)), equalTo(NEUTRAL));
            assertThat(taskLogsFilter.decide(eventWithMarkerOf(null)), equalTo(NEUTRAL));
            
            // Clean up
            MDC.remove(LOGBACK_TASK_DISCRIMINATOR_ID);
            TaskLoggerHelper.finish();
          }
          catch (Exception e) {
            testException.set(e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(5, TimeUnit.SECONDS);
    }
    
    // Check if any exceptions occurred
    Exception exception = testException.get();
    if (exception != null) {
      throw exception;
    }
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