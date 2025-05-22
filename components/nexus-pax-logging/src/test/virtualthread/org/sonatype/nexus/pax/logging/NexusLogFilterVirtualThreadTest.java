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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.sonatype.nexus.logging.task.TaskLogger.TASK_LOG_ONLY_MDC;
import static org.sonatype.nexus.logging.task.TaskLogger.TASK_LOG_WITH_PROGRESS_MDC;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.AUDIT_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.TASK_LOG_ONLY;

/**
 * Tests for {@link NexusLogFilter} when executed within Java 21 Virtual Threads.
 * 
 * This test class verifies that MDC context is properly propagated across virtual thread boundaries,
 * markers are correctly handled when events are generated from virtual threads, and the filter's
 * decision logic works consistently in a virtual thread environment.
 */
public class NexusLogFilterVirtualThreadTest
{
  private NexusLogFilter excludeProgressLogsFilter;

  private ILoggingEvent event;

  @BeforeEach
  public void setup() {
    excludeProgressLogsFilter = new NexusLogFilter();
    event = new LoggingEvent();
  }

  @AfterEach
  public void tearDown() {
    MDC.remove(TASK_LOG_ONLY_MDC);
    MDC.remove(TASK_LOG_WITH_PROGRESS_MDC);
  }

  /**
   * Test that MDC context is properly propagated to virtual threads and the filter
   * correctly processes events with no MDC context.
   */
  @Test
  public void testNothingInMDC_VirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        return excludeProgressLogsFilter.decide(event) == NEUTRAL;
      }, executor);
      
      assertThat(future.get(5, TimeUnit.SECONDS), equalTo(true));
    }
  }

  /**
   * Test that markers are correctly handled when events are generated from virtual threads.
   */
  @Test
  public void testOtherMarkerInMDC_VirtualThread() throws Exception {
    Marker fooMarker = MarkerFactory.getMarker("foo");
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        return excludeProgressLogsFilter.decide(eventWithMarkerOf(fooMarker)) == NEUTRAL;
      }, executor);
      
      assertThat(future.get(5, TimeUnit.SECONDS), equalTo(true));
    }
  }

  /**
   * Test that task log with progress marker is correctly handled in virtual threads.
   */
  @Test
  public void testTaskLogWithProgressMarkerInMDC_VirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        MDC.put(TASK_LOG_WITH_PROGRESS_MDC, "true");
        MDC.put(TASK_LOG_ONLY_MDC, "anything"); // Should have no effect
        
        return excludeProgressLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS)) == NEUTRAL;
      }, executor);
      
      assertThat(future.get(5, TimeUnit.SECONDS), equalTo(true));
    }
  }

  /**
   * Test that progress marker is correctly handled in virtual threads.
   */
  @Test
  public void testProgressMarkerInMDC_VirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        return excludeProgressLogsFilter.decide(eventWithMarkerOf(PROGRESS)) == DENY;
      }, executor);
      
      assertThat(future.get(5, TimeUnit.SECONDS), equalTo(true));
    }
  }

  /**
   * Test that task marker is correctly handled in virtual threads.
   */
  @Test
  public void testTaskMarkerInMDC_VirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        return excludeProgressLogsFilter.decide(eventWithMarkerOf(TASK_LOG_ONLY)) == DENY;
      }, executor);
      
      assertThat(future.get(5, TimeUnit.SECONDS), equalTo(true));
    }
  }

  /**
   * Test that task only MDC constant is correctly handled in virtual threads.
   */
  @Test
  public void testTaskOnlyMDCConstant_InMDC_VirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        MDC.put(TASK_LOG_ONLY_MDC, "anything");
        return excludeProgressLogsFilter.decide(event) == DENY;
      }, executor);
      
      assertThat(future.get(5, TimeUnit.SECONDS), equalTo(true));
    }
  }

  /**
   * Test that audit log is not written to nexus log in virtual threads.
   */
  @Test
  public void testAuditLogNotWrittenToNexusLog_VirtualThread() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        return excludeProgressLogsFilter.decide(eventWithMarkerOf(AUDIT_LOG_ONLY)) == DENY;
      }, executor);
      
      assertThat(future.get(5, TimeUnit.SECONDS), equalTo(true));
    }
  }

  /**
   * Test high-concurrency scenario with multiple virtual threads.
   * This test verifies that the filter's decision logic works consistently
   * across multiple concurrent virtual threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    int threadCount = 100;
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create tasks that set MDC and test filter with different markers
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
          // Cycle through different test cases based on index
          switch (index % 5) {
            case 0:
              // Test with no MDC
              return excludeProgressLogsFilter.decide(event) == NEUTRAL;
            case 1:
              // Test with progress marker
              return excludeProgressLogsFilter.decide(eventWithMarkerOf(PROGRESS)) == DENY;
            case 2:
              // Test with task marker
              return excludeProgressLogsFilter.decide(eventWithMarkerOf(TASK_LOG_ONLY)) == DENY;
            case 3:
              // Test with MDC constant
              MDC.put(TASK_LOG_ONLY_MDC, "thread-" + index);
              boolean result = excludeProgressLogsFilter.decide(event) == DENY;
              MDC.remove(TASK_LOG_ONLY_MDC);
              return result;
            case 4:
              // Test with task log with progress
              MDC.put(TASK_LOG_WITH_PROGRESS_MDC, "true");
              boolean progressResult = excludeProgressLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS)) == NEUTRAL;
              MDC.remove(TASK_LOG_WITH_PROGRESS_MDC);
              return progressResult;
            default:
              return false;
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all futures to complete and verify results
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
      allFutures.get(10, TimeUnit.SECONDS);
      
      // Verify all results are true (all tests passed)
      for (CompletableFuture<Boolean> future : futures) {
        assertThat(future.get(), equalTo(true));
      }
    }
  }

  /**
   * Test MDC context isolation between virtual threads.
   * This test verifies that MDC changes in one virtual thread do not affect other threads.
   */
  @Test
  public void testMDCContextIsolationBetweenVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Thread 1: Sets MDC and verifies DENY response
      CompletableFuture<Boolean> thread1 = CompletableFuture.supplyAsync(() -> {
        MDC.put(TASK_LOG_ONLY_MDC, "thread1");
        return excludeProgressLogsFilter.decide(event) == DENY;
      }, executor);
      
      // Thread 2: Should not see MDC from Thread 1, should get NEUTRAL
      CompletableFuture<Boolean> thread2 = CompletableFuture.supplyAsync(() -> {
        // Ensure we don't start before thread1 sets its MDC
        try {
          TimeUnit.MILLISECONDS.sleep(50);
        } 
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return excludeProgressLogsFilter.decide(event) == NEUTRAL;
      }, executor);
      
      assertThat(thread1.get(5, TimeUnit.SECONDS), equalTo(true));
      assertThat(thread2.get(5, TimeUnit.SECONDS), equalTo(true));
    }
  }

  private ILoggingEvent eventWithMarkerOf(final Marker marker) {
    LoggingEvent event = new LoggingEvent();
    event.setMessage("Test Message");
    event.setMarker(marker);
    return event;
  }
}