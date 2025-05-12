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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MDC;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.sonatype.nexus.logging.task.ProgressTaskLogger.PROGRESS_LINE;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;

/**
 * Tests for {@link ProgressTaskLogger} specifically focused on validating behavior
 * when running with Java 21 Virtual Threads.
 *
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21)
@Tag("virtual-thread")
public class ProgressTaskLoggerVirtualThreadTest
    extends TestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 100;
  private static final int HIGH_LOAD_THREAD_COUNT = 1000;
  
  @Mock
  private Logger mockLogger;

  private ProgressTaskLogger underTest;

  @BeforeEach
  public void setUp() {
    underTest = new ProgressTaskLogger(mockLogger);
    underTest.start();
  }

  @AfterEach
  public void tearDown() {
    underTest.finish();
  }

  /**
   * Tests basic progress logging with a single virtual thread.
   */
  @Test
  public void testProgressWithVirtualThread() throws Exception {
    String message = "virtual thread test message";
    
    // Create and run a virtual thread to log progress
    Thread.ofVirtual().start(() -> {
      TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, message);
      underTest.progress(event);
      underTest.flush();
    }).join();
    
    // Verify progress was logged properly
    verify(mockLogger).info(eq(INTERNAL_PROGRESS), eq(format(PROGRESS_LINE, message)), (Object[]) null);
  }

  /**
   * Tests that multiple concurrent virtual threads can log progress correctly.
   */
  @Test
  public void testMultipleVirtualThreadsProgress() throws Exception {
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger counter = new AtomicInteger(0);
    
    // Create and start multiple virtual threads
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      final int threadNum = i;
      Thread.ofVirtual().start(() -> {
        String message = "virtual thread " + threadNum + " message";
        TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, message);
        underTest.progress(event);
        underTest.flush();
        counter.incrementAndGet();
        latch.countDown();
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "All virtual threads should complete within timeout");
    assertEquals(VIRTUAL_THREAD_COUNT, counter.get(), "All virtual threads should have executed");
    
    // Verify that progress was logged for each thread
    verify(mockLogger, times(VIRTUAL_THREAD_COUNT)).info(eq(INTERNAL_PROGRESS), anyString(), (Object[]) null);
  }

  /**
   * Tests that MDC context is properly propagated to virtual threads.
   */
  @Test
  public void testMDCPropagationWithVirtualThreads() throws Exception {
    // Set MDC values in the main thread
    MDC.put("testKey", "testValue");
    MDC.put("requestId", "virtual-thread-test");
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread that verifies MDC propagation
    Thread.ofVirtual().start(() -> {
      try {
        // Verify MDC values were propagated to the virtual thread
        if ("testValue".equals(MDC.get("testKey")) && 
            "virtual-thread-test".equals(MDC.get("requestId"))) {
          successCount.incrementAndGet();
        }
        
        // Log progress in the virtual thread
        TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, "MDC test message");
        underTest.progress(event);
        underTest.flush();
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread should complete within timeout");
    assertEquals(1, successCount.get(), "MDC values should be correctly propagated to virtual thread");
    
    // Clean up MDC
    MDC.clear();
  }

  /**
   * Tests throttling behavior with rapid progress events from virtual threads.
   */
  @Test
  public void testProgressThrottlingWithVirtualThreads() throws Exception {
    // Create a test-specific logger with a very short interval to test throttling
    ProgressTaskLogger throttledLogger = new ProgressTaskLogger(mockLogger, 50, 50, TimeUnit.MILLISECONDS);
    throttledLogger.start();
    
    try {
      // Generate many rapid progress events from virtual threads
      for (int i = 0; i < 100; i++) {
        final int eventNum = i;
        Thread.ofVirtual().start(() -> {
          String message = "throttle test " + eventNum;
          TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, message);
          throttledLogger.progress(event);
          // No flush call to test throttling
        }).join(); // Wait for each thread to complete
      }
      
      // Sleep to allow the throttled logger to process events
      Thread.sleep(200);
      
      // Verify that not all 100 events were logged due to throttling
      // The exact number depends on the throttling implementation, but it should be less than 100
      verify(mockLogger, Mockito.atMost(10)).info(eq(INTERNAL_PROGRESS), anyString(), (Object[]) null);
    } finally {
      throttledLogger.finish();
    }
  }

  /**
   * Tests high-load scenario with many concurrent virtual threads generating progress events.
   */
  @Test
  public void testHighLoadWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(HIGH_LOAD_THREAD_COUNT);
    List<Thread> threads = new ArrayList<>();
    
    // Create many virtual threads to simulate high load
    for (int i = 0; i < HIGH_LOAD_THREAD_COUNT; i++) {
      final int threadNum = i;
      Thread thread = Thread.ofVirtual().start(() -> {
        try {
          // Simulate some work
          String message = "high load thread " + threadNum;
          TaskLoggingEvent event = new TaskLoggingEvent(mockLogger, message);
          underTest.progress(event);
          
          // Only flush occasionally to test batching behavior
          if (threadNum % 100 == 0) {
            underTest.flush();
          }
        } finally {
          latch.countDown();
        }
      });
      threads.add(thread);
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "All high-load virtual threads should complete within timeout");
    
    // Verify that progress events were logged (exact count depends on throttling and batching)
    verify(mockLogger, Mockito.atLeast(1)).info(eq(INTERNAL_PROGRESS), anyString(), (Object[]) null);
  }

  /**
   * Tests that no progress is logged when no events are generated.
   */
  @Test
  public void testNoProgressLoggedWithVirtualThreads() throws Exception {
    // Create and run a virtual thread that doesn't generate any progress events
    Thread.ofVirtual().start(() -> {
      // Do some work but don't call progress()
      underTest.flush();
    }).join();
    
    // Verify no progress was logged
    verify(mockLogger, never()).info(any(Marker.class), anyString(), (Object[]) null);
  }
}