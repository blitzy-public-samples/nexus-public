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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
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

@ExtendWith(MockitoExtension.class)
class NexusLogFilterTest
{
  private NexusLogFilter excludeProgressLogsFilter;

  private ILoggingEvent event;

  @BeforeEach
  void setup() {
    excludeProgressLogsFilter = new NexusLogFilter();
    event = new LoggingEvent();
  }

  @AfterEach
  void tearDown() {
    MDC.remove(TASK_LOG_ONLY_MDC);
    MDC.remove(TASK_LOG_WITH_PROGRESS_MDC);
  }

  @Test
  void testNothingInMDC() {
    assertThat(excludeProgressLogsFilter.decide(event), equalTo(NEUTRAL));
  }

  @Test
  void testOtherMarkerInMDC() {
    Marker fooMarker = MarkerFactory.getMarker("foo");
    assertThat(excludeProgressLogsFilter.decide(eventWithMarkerOf(fooMarker)), equalTo(NEUTRAL));
  }

  @Test
  void testTaskLogWithProgressMarkerInMDC() {
    MDC.put(TASK_LOG_WITH_PROGRESS_MDC, "true");

    // as the method also returns NEUTRAL by default, we also test that TASK_LOG_ONLY_MDC being set has no affect
    MDC.put(TASK_LOG_ONLY_MDC, "anything");

    assertThat(excludeProgressLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS)), equalTo(NEUTRAL));
  }

  @Test
  void testProgressMarkerInMDC() {
    assertThat(excludeProgressLogsFilter.decide(eventWithMarkerOf(PROGRESS)), equalTo(DENY));
  }

  @Test
  void testTaskMarkerInMDC() {
    assertThat(excludeProgressLogsFilter.decide(eventWithMarkerOf(TASK_LOG_ONLY)), equalTo(DENY));
  }

  @Test
  void testTaskOnlyMDCConstant_InMDC() {
    MDC.put(TASK_LOG_ONLY_MDC, "anything");
    assertThat(excludeProgressLogsFilter.decide(event), equalTo(DENY));
  }

  @Test
  void testAuditLogNotWrittenToNexusLog() {
    assertThat(excludeProgressLogsFilter.decide(eventWithMarkerOf(AUDIT_LOG_ONLY)), equalTo(DENY));
  }

  @Test
  void testInVirtualThread() throws Exception {
    // Create and run a virtual thread to verify NexusLogFilter works in virtual thread context
    Thread virtualThread = Thread.ofVirtual().name("virtual-test-thread").start(() -> {
      // Test basic filter functionality in virtual thread
      assertThat(excludeProgressLogsFilter.decide(event), equalTo(NEUTRAL));
      
      // Test with marker in virtual thread
      MDC.put(TASK_LOG_ONLY_MDC, "virtual-thread-test");
      assertThat(excludeProgressLogsFilter.decide(event), equalTo(DENY));
    });
    
    // Wait for virtual thread to complete
    virtualThread.join();
  }

  @Test
  void testMDCPropagationInVirtualThreads() throws Exception {
    // Set MDC in parent thread
    MDC.put(TASK_LOG_ONLY_MDC, "parent-thread-value");
    
    // Create a reference to hold the MDC value from the virtual thread
    AtomicReference<String> virtualThreadMdcValue = new AtomicReference<>();
    
    // Create and run a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("mdc-propagation-test").start(() -> {
      // Capture the MDC value in the virtual thread
      virtualThreadMdcValue.set(MDC.get(TASK_LOG_ONLY_MDC));
      
      // Test filter behavior with the propagated MDC
      assertThat(excludeProgressLogsFilter.decide(event), equalTo(DENY));
    });
    
    // Wait for virtual thread to complete
    virtualThread.join();
    
    // Verify MDC was properly propagated to the virtual thread
    assertThat(virtualThreadMdcValue.get(), equalTo("parent-thread-value"));
  }

  @Test
  void testVirtualThreadBulkFiltering() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Number of virtual threads to test with
    int threadCount = 1000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    try {
      // Submit tasks to virtual threads
      for (int i = 0; i < threadCount; i++) {
        final String taskId = "task-" + i;
        executor.submit(() -> {
          try {
            // Set unique MDC value for this virtual thread
            MDC.put(TASK_LOG_ONLY_MDC, taskId);
            
            // Create event with marker
            ILoggingEvent threadEvent = eventWithMarkerOf(TASK_LOG_ONLY);
            
            // Verify filter behavior under load
            assertThat(excludeProgressLogsFilter.decide(threadEvent), equalTo(DENY));
          } finally {
            MDC.clear();
            latch.countDown();
          }
        });
      }
      
      // Wait for all virtual threads to complete (with timeout)
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete in time", completed, equalTo(true));
    } finally {
      executor.shutdown();
    }
  }

  private ILoggingEvent eventWithMarkerOf(final Marker marker) {
    LoggingEvent event = new LoggingEvent();
    event.setMessage("Test Message");
    event.setMarker(marker);
    return event;
  }
}