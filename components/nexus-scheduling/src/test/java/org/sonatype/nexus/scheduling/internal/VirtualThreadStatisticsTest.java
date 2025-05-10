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
package org.sonatype.nexus.scheduling.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.scheduling.internal.VirtualThreadStatistics.ThreadPinningEvent;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Tests for {@link VirtualThreadStatistics}.
 */
public class VirtualThreadStatisticsTest
    extends TestSupport
{
  private VirtualThreadStatistics underTest;

  @Before
  public void setup() {
    underTest = new VirtualThreadStatistics();
  }

  @Test
  public void testInitialState() {
    assertThat(underTest.getVirtualThreadsCreated(), is(0));
    assertThat(underTest.getVirtualThreadsTerminated(), is(0));
    assertThat(underTest.getActiveVirtualThreads(), is(0));
    assertThat(underTest.getPinnedThreadsCount(), is(0));
    assertThat(underTest.getTotalPinningDurationMs(), is(0L));
    assertThat(underTest.getMaxPinningDurationMs(), is(0L));
    assertThat(underTest.getAveragePinningDurationMs(), is(0.0));
    assertThat(underTest.getRecentPinningEvents(), hasSize(0));
  }

  @Test
  public void testRecordThreadCreatedAndTerminated() {
    underTest.recordThreadCreated();
    underTest.recordThreadCreated();
    underTest.recordThreadCreated();
    
    assertThat(underTest.getVirtualThreadsCreated(), is(3));
    assertThat(underTest.getVirtualThreadsTerminated(), is(0));
    assertThat(underTest.getActiveVirtualThreads(), is(3));
    
    underTest.recordThreadTerminated();
    
    assertThat(underTest.getVirtualThreadsCreated(), is(3));
    assertThat(underTest.getVirtualThreadsTerminated(), is(1));
    assertThat(underTest.getActiveVirtualThreads(), is(2));
  }

  @Test
  public void testRecordThreadPinning() {
    underTest.recordThreadPinning("thread-1", 100L, "stack-trace-1");
    underTest.recordThreadPinning("thread-2", 200L, "stack-trace-2");
    underTest.recordThreadPinning("thread-3", 300L, "stack-trace-3");
    
    assertThat(underTest.getPinnedThreadsCount(), is(3));
    assertThat(underTest.getTotalPinningDurationMs(), is(600L));
    assertThat(underTest.getMaxPinningDurationMs(), is(300L));
    assertThat(underTest.getAveragePinningDurationMs(), is(200.0));
    
    List<ThreadPinningEvent> events = underTest.getRecentPinningEvents();
    assertThat(events, hasSize(3));
    
    ThreadPinningEvent event = events.get(0);
    assertThat(event.getThreadName(), is("thread-1"));
    assertThat(event.getDurationMs(), is(100L));
    assertThat(event.getStackTrace(), is("stack-trace-1"));
    
    // Add a longer duration event and verify max is updated
    underTest.recordThreadPinning("thread-4", 500L, "stack-trace-4");
    assertThat(underTest.getMaxPinningDurationMs(), is(500L));
    assertThat(underTest.getAveragePinningDurationMs(), is(275.0)); // (100+200+300+500)/4
  }

  @Test
  public void testRecentPinningEventsLimit() {
    // Add more than the maximum number of events
    for (int i = 0; i < 150; i++) {
      underTest.recordThreadPinning("thread-" + i, i, "stack-trace-" + i);
    }
    
    // Verify only the most recent events are kept
    List<ThreadPinningEvent> events = underTest.getRecentPinningEvents();
    assertThat(events, hasSize(100)); // MAX_RECENT_EVENTS = 100
    
    // Verify the oldest events were removed (events 0-49 should be gone)
    ThreadPinningEvent firstEvent = events.get(0);
    assertThat(firstEvent.getThreadName(), is("thread-50"));
    
    // Verify the newest events are present
    ThreadPinningEvent lastEvent = events.get(99);
    assertThat(lastEvent.getThreadName(), is("thread-149"));
  }

  @Test
  public void testThreadPinningEvent() {
    Instant now = Instant.now();
    ThreadPinningEvent event = new ThreadPinningEvent("test-thread", 500L, "test-stack-trace", now);
    
    assertThat(event.getThreadName(), is("test-thread"));
    assertThat(event.getDurationMs(), is(500L));
    assertThat(event.getStackTrace(), is("test-stack-trace"));
    assertThat(event.getTimestamp(), is(now));
    
    // Age should be a small duration since we just created it
    Duration age = event.getAge();
    assertThat(age.toMillis(), greaterThan(0L));
    assertThat(age.toSeconds(), lessThanOrEqualTo(1L));
    
    // Test toString
    String eventString = event.toString();
    assertThat(eventString.contains("test-thread"), is(true));
    assertThat(eventString.contains("500ms"), is(true));
  }

  @Test
  public void testReset() {
    // Add some data
    underTest.recordThreadCreated();
    underTest.recordThreadCreated();
    underTest.recordThreadTerminated();
    underTest.recordThreadPinning("thread-1", 100L, "stack-trace-1");
    underTest.recordThreadPinning("thread-2", 200L, "stack-trace-2");
    
    // Verify data was recorded
    assertThat(underTest.getVirtualThreadsCreated(), is(2));
    assertThat(underTest.getVirtualThreadsTerminated(), is(1));
    assertThat(underTest.getPinnedThreadsCount(), is(2));
    assertThat(underTest.getRecentPinningEvents(), hasSize(2));
    
    // Reset and verify all counters are back to initial state
    underTest.reset();
    
    assertThat(underTest.getVirtualThreadsCreated(), is(0));
    assertThat(underTest.getVirtualThreadsTerminated(), is(0));
    assertThat(underTest.getActiveVirtualThreads(), is(0));
    assertThat(underTest.getPinnedThreadsCount(), is(0));
    assertThat(underTest.getTotalPinningDurationMs(), is(0L));
    assertThat(underTest.getMaxPinningDurationMs(), is(0L));
    assertThat(underTest.getAveragePinningDurationMs(), is(0.0));
    assertThat(underTest.getRecentPinningEvents(), hasSize(0));
  }
}