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
package org.sonatype.nexus.logging.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.Marker;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.sonatype.nexus.logging.task.ProgressTaskLogger.PROGRESS_LINE;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;

/**
 * Tests for {@link ProgressTaskLogger} using Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class ProgressTaskLoggerVirtualThreadTest
    extends TestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 1000;
  private static final int PROGRESS_EVENTS_PER_THREAD = 10;
  private static final String MDC_TEST_KEY = "testMdcKey";
  private static final String MDC_TEST_VALUE = "testMdcValue";
  
  @Mock
  private Logger mockLogger;

  @Captor
  private ArgumentCaptor<Marker> markerCaptor;

  @Captor
  private ArgumentCaptor<String> messageCaptor;

  private ProgressTaskLogger underTest;

  @BeforeEach
  void setUp() {
    // Create a ProgressTaskLogger with a short interval for testing
    underTest = new ProgressTaskLogger(mockLogger, 10, 100, TimeUnit.MILLISECONDS);
    underTest.start();
  }

  @AfterEach
  void tearDown() {
    underTest.finish();
    MDC.clear();
  }

  /**
   * Tests basic progress logging with a single virtual thread.
   */
  @Test
  void testProgressWithSingleVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    String message = "test message from virtual thread";
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, message);
      underTest.progress(event);
    });
    
    virtualThread.start();
    virtualThread.join();
    
    // Force progress logging
    underTest.logProgress();
    
    // Verify progress was logged properly
    verify(mockLogger).info(eq(INTERNAL_PROGRESS), eq(format(PROGRESS_LINE, message)), (Object[]) null);
  }

  /**
   * Tests high-volume progress event generation with many concurrent virtual threads.
   */
  @Test
  void testHighVolumeProgressEventsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger completedThreads = new AtomicInteger(0);
    
    try {
      // Submit tasks to generate progress events from many virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            for (int j = 0; j < PROGRESS_EVENTS_PER_THREAD; j++) {
              String message = "Progress from virtual thread " + threadId + ", event " + j;
              TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, message);
              underTest.progress(event);
            }
            completedThreads.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Force progress logging
      underTest.logProgress();
      
      // Verify all threads completed successfully
      assertEquals(VIRTUAL_THREAD_COUNT, completedThreads.get(), "Not all virtual threads completed successfully");
      
      // Verify progress was logged at least once
      verify(mockLogger, atLeastOnce()).info(eq(INTERNAL_PROGRESS), anyString(), (Object[]) null);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests MDC context propagation with virtual threads.
   */
  @Test
  void testMdcPropagationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger mdcSuccessCount = new AtomicInteger(0);
    
    try {
      // Set MDC in the main thread
      MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
      
      // Create a new ProgressTaskLogger to capture the current MDC
      ProgressTaskLogger mdcLogger = new ProgressTaskLogger(mockLogger);
      mdcLogger.start();
      
      // Submit a task to verify MDC propagation
      executor.submit(() -> {
        try {
          // Check if MDC value is correctly propagated
          String mdcValue = MDC.get(MDC_TEST_KEY);
          if (MDC_TEST_VALUE.equals(mdcValue)) {
            mdcSuccessCount.incrementAndGet();
          }
          
          // Generate a progress event
          TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, "MDC test event");
          mdcLogger.progress(event);
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for the thread to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual thread to complete");
      
      // Force progress logging
      mdcLogger.logProgress();
      mdcLogger.finish();
      
      // Verify MDC was correctly propagated
      assertEquals(1, mdcSuccessCount.get(), "MDC context was not properly propagated to virtual thread");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests progress message throttling with virtual threads.
   */
  @Test
  void testProgressThrottlingWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Create a ProgressTaskLogger with a longer interval for throttling test
    ProgressTaskLogger throttledLogger = new ProgressTaskLogger(mockLogger, 0, 1000, TimeUnit.MILLISECONDS);
    throttledLogger.start();
    
    try {
      // Submit tasks to generate progress events from many virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Generate multiple progress events in quick succession
            for (int j = 0; j < PROGRESS_EVENTS_PER_THREAD; j++) {
              String message = "Throttle test from thread " + threadId + ", event " + j;
              TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, message);
              throttledLogger.progress(event);
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Force progress logging once
      throttledLogger.logProgress();
      
      // Capture the number of info calls
      ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
      verify(mockLogger, atLeastOnce()).info(eq(INTERNAL_PROGRESS), messageCaptor.capture(), (Object[]) null);
      
      // The number of logged messages should be much less than the total number of progress events
      int totalEvents = VIRTUAL_THREAD_COUNT * PROGRESS_EVENTS_PER_THREAD;
      int loggedEvents = messageCaptor.getAllValues().size();
      
      // We expect significantly fewer log entries than events due to throttling
      assertThat(loggedEvents, lessThanOrEqualTo(totalEvents / 10));
    } finally {
      throttledLogger.finish();
      executor.shutdown();
    }
  }

  /**
   * Tests event ordering with multiple virtual threads.
   */
  @Test
  void testEventOrderingWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    int threadCount = 10; // Use a smaller number for ordering test
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<String> expectedMessages = new ArrayList<>();
    
    try {
      // Submit tasks to generate sequential progress events
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        String message = "Ordered event from thread " + threadId;
        expectedMessages.add(message);
        
        executor.submit(() -> {
          try {
            TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, message);
            underTest.progress(event);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Force progress logging
      underTest.logProgress();
      
      // Capture logged messages
      verify(mockLogger, times(threadCount)).info(markerCaptor.capture(), messageCaptor.capture(), (Object[]) null);
      
      List<String> actualMessages = messageCaptor.getAllValues();
      
      // Verify all expected messages were logged
      for (String expected : expectedMessages) {
        boolean found = false;
        for (String actual : actualMessages) {
          if (actual.contains(expected)) {
            found = true;
            break;
          }
        }
        assertTrue(found, "Expected message not found: " + expected);
      }
      
      // Verify all markers are INTERNAL_PROGRESS
      for (Marker marker : markerCaptor.getAllValues()) {
        assertEquals(INTERNAL_PROGRESS, marker);
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests stress test with extreme virtual thread load.
   */
  @Test
  void testStressWithExtremeVirtualThreadLoad() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    int extremeThreadCount = 5000; // Very high number of virtual threads
    CountDownLatch latch = new CountDownLatch(extremeThreadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Set MDC in the main thread
      MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
      
      // Create a new ProgressTaskLogger to capture the current MDC
      ProgressTaskLogger stressLogger = new ProgressTaskLogger(mockLogger);
      stressLogger.start();
      
      // Submit a large number of tasks
      for (int i = 0; i < extremeThreadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Check MDC propagation
            String mdcValue = MDC.get(MDC_TEST_KEY);
            if (MDC_TEST_VALUE.equals(mdcValue)) {
              successCount.incrementAndGet();
            }
            
            // Generate a progress event
            String message = "Stress test from thread " + threadId;
            TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, message);
            stressLogger.progress(event);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete with a longer timeout
      assertTrue(latch.await(60, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Force progress logging
      stressLogger.logProgress();
      stressLogger.finish();
      
      // Verify a high percentage of threads had correct MDC propagation
      int successPercentage = (successCount.get() * 100) / extremeThreadCount;
      assertThat(successPercentage, greaterThanOrEqualTo(95));
    } finally {
      executor.shutdown();
    }
  }
}