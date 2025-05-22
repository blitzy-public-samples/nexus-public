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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.sonatype.nexus.pax.logging.NexusLogFilter;

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
 * Tests for {@link NexusLogFilter} in a virtual thread environment to ensure that
 * SLF4J MDC keys and Logback Markers are correctly propagated across thread boundaries
 * when using Java 21 virtual threads.
 */
@Tag("VirtualThreadTest")
public class VirtualThreadNexusLogFilterTest
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

  @Test
  public void testNothingInMDC() throws Exception {
    AtomicReference<FilterReply> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      result.set(excludeProgressLogsFilter.decide(event));
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    assertThat(result.get(), equalTo(NEUTRAL));
  }

  @Test
  public void testOtherMarkerInMDC() throws Exception {
    Marker fooMarker = MarkerFactory.getMarker("foo");
    AtomicReference<FilterReply> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      result.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(fooMarker)));
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    assertThat(result.get(), equalTo(NEUTRAL));
  }

  @Test
  public void testTaskLogWithProgressMarkerInMDC() throws Exception {
    MDC.put(TASK_LOG_WITH_PROGRESS_MDC, "true");
    // as the method also returns NEUTRAL by default, we also test that TASK_LOG_ONLY_MDC being set has no affect
    MDC.put(TASK_LOG_ONLY_MDC, "anything");
    
    AtomicReference<FilterReply> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      result.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS)));
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    assertThat(result.get(), equalTo(NEUTRAL));
  }

  @Test
  public void testProgressMarkerInMDC() throws Exception {
    AtomicReference<FilterReply> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      result.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(PROGRESS)));
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    assertThat(result.get(), equalTo(DENY));
  }

  @Test
  public void testTaskMarkerInMDC() throws Exception {
    AtomicReference<FilterReply> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      result.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(TASK_LOG_ONLY)));
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    assertThat(result.get(), equalTo(DENY));
  }

  @Test
  public void testTaskOnlyMDCConstant_InMDC() throws Exception {
    MDC.put(TASK_LOG_ONLY_MDC, "anything");
    
    AtomicReference<FilterReply> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      result.set(excludeProgressLogsFilter.decide(event));
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    assertThat(result.get(), equalTo(DENY));
  }

  @Test
  public void testAuditLogNotWrittenToNexusLog() throws Exception {
    AtomicReference<FilterReply> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      result.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(AUDIT_LOG_ONLY)));
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    assertThat(result.get(), equalTo(DENY));
  }
  
  @Test
  public void testMDCPropagationAcrossVirtualThreads() throws Exception {
    MDC.put(TASK_LOG_ONLY_MDC, "anything");
    
    AtomicReference<FilterReply> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.ofVirtual().start(() -> {
      // Verify MDC is propagated to virtual thread
      assertThat(MDC.get(TASK_LOG_ONLY_MDC), equalTo("anything"));
      result.set(excludeProgressLogsFilter.decide(event));
      latch.countDown();
    });
    
    latch.await(5, TimeUnit.SECONDS);
    assertThat(result.get(), equalTo(DENY));
  }
  
  @Test
  public void testConcurrentVirtualThreadsWithDifferentMDC() throws Exception {
    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    AtomicReference<FilterReply> result1 = new AtomicReference<>();
    AtomicReference<FilterReply> result2 = new AtomicReference<>();
    
    // First virtual thread with TASK_LOG_ONLY_MDC
    Thread.ofVirtual().name("vthread-1").start(() -> {
      MDC.put(TASK_LOG_ONLY_MDC, "thread1");
      result1.set(excludeProgressLogsFilter.decide(event));
      latch1.countDown();
    });
    
    // Second virtual thread with TASK_LOG_WITH_PROGRESS_MDC
    Thread.ofVirtual().name("vthread-2").start(() -> {
      MDC.put(TASK_LOG_WITH_PROGRESS_MDC, "thread2");
      result2.set(excludeProgressLogsFilter.decide(eventWithMarkerOf(INTERNAL_PROGRESS)));
      latch2.countDown();
    });
    
    latch1.await(5, TimeUnit.SECONDS);
    latch2.await(5, TimeUnit.SECONDS);
    
    // Verify each thread got the expected result based on its own MDC
    assertThat(result1.get(), equalTo(DENY)); // TASK_LOG_ONLY_MDC should result in DENY
    assertThat(result2.get(), equalTo(NEUTRAL)); // TASK_LOG_WITH_PROGRESS_MDC with INTERNAL_PROGRESS should be NEUTRAL
  }

  private ILoggingEvent eventWithMarkerOf(final Marker marker) {
    LoggingEvent event = new LoggingEvent();
    event.setMessage("Test Message");
    event.setMarker(marker);
    return event;
  }
}