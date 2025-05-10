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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.scheduling.spi.SchedulerSPI;

import com.codahale.metrics.health.HealthCheck.Result;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SchedulerHealthCheck}.
 */
public class SchedulerHealthCheckTest
    extends TestSupport
{
  @Mock
  private SchedulerSPI scheduler;

  @Mock
  private VirtualThreadStatistics virtualThreadStats;

  private SchedulerHealthCheck underTest;

  @Before
  public void setup() {
    underTest = new SchedulerHealthCheck(() -> scheduler, virtualThreadStats);
  }

  @Test
  public void testHealthyWhenNoMissingTriggers() {
    when(scheduler.getMissingTriggerDescriptions()).thenReturn(Collections.emptyList());
    when(scheduler.isVirtualThreadsEnabled()).thenReturn(false);

    Result result = underTest.check();

    assertThat(result.isHealthy(), is(true));
  }

  @Test
  public void testUnhealthyWhenMissingTriggers() {
    List<String> missingTriggers = new ArrayList<>();
    missingTriggers.add("task1");
    missingTriggers.add("task2");

    when(scheduler.getMissingTriggerDescriptions()).thenReturn(missingTriggers);
    when(scheduler.isVirtualThreadsEnabled()).thenReturn(false);

    Result result = underTest.check();

    assertThat(result.isHealthy(), is(false));
    assertThat(result.getMessage(), containsString("2 tasks require frequency updates"));
    assertThat(result.getMessage(), containsString("task1, task2"));
  }

  @Test
  public void testHealthyWithVirtualThreadsEnabled() {
    when(scheduler.getMissingTriggerDescriptions()).thenReturn(Collections.emptyList());
    when(scheduler.isVirtualThreadsEnabled()).thenReturn(true);
    when(virtualThreadStats.getPinnedThreadsCount()).thenReturn(5); // Below threshold
    when(virtualThreadStats.getMaxPinningDurationMs()).thenReturn(1000L); // Below threshold
    when(virtualThreadStats.getRecentPinningEvents()).thenReturn(Collections.emptyList());

    Result result = underTest.check();

    assertThat(result.isHealthy(), is(true));
  }

  @Test
  public void testUnhealthyWithExcessivePinnedThreads() {
    when(scheduler.getMissingTriggerDescriptions()).thenReturn(Collections.emptyList());
    when(scheduler.isVirtualThreadsEnabled()).thenReturn(true);
    when(virtualThreadStats.getPinnedThreadsCount()).thenReturn(20); // Above threshold
    when(virtualThreadStats.getMaxPinningDurationMs()).thenReturn(1000L); // Below threshold
    when(virtualThreadStats.getRecentPinningEvents()).thenReturn(Collections.emptyList());
    when(virtualThreadStats.getVirtualThreadsCreated()).thenReturn(100);
    when(virtualThreadStats.getVirtualThreadsTerminated()).thenReturn(80);
    when(virtualThreadStats.getActiveVirtualThreads()).thenReturn(20);
    when(virtualThreadStats.getAveragePinningDurationMs()).thenReturn(500.0);

    Result result = underTest.check();

    assertThat(result.isHealthy(), is(false));
    assertThat(result.getMessage(), containsString("Excessive virtual thread pinning detected"));
    assertThat(result.getMessage(), containsString("20 pinned threads"));
  }

  @Test
  public void testUnhealthyWithLongDurationPinning() {
    when(scheduler.getMissingTriggerDescriptions()).thenReturn(Collections.emptyList());
    when(scheduler.isVirtualThreadsEnabled()).thenReturn(true);
    when(virtualThreadStats.getPinnedThreadsCount()).thenReturn(5); // Below threshold
    when(virtualThreadStats.getMaxPinningDurationMs()).thenReturn(10000L); // Above threshold
    when(virtualThreadStats.getRecentPinningEvents()).thenReturn(Collections.emptyList());
    when(virtualThreadStats.getVirtualThreadsCreated()).thenReturn(100);
    when(virtualThreadStats.getVirtualThreadsTerminated()).thenReturn(80);
    when(virtualThreadStats.getActiveVirtualThreads()).thenReturn(20);
    when(virtualThreadStats.getAveragePinningDurationMs()).thenReturn(2000.0);

    Result result = underTest.check();

    assertThat(result.isHealthy(), is(false));
    assertThat(result.getMessage(), containsString("Long-duration thread pinning detected"));
    assertThat(result.getMessage(), containsString("10000 ms maximum pinning duration"));
  }

  @Test
  public void testUnhealthyWithRecentPinningEvents() {
    List<VirtualThreadStatistics.ThreadPinningEvent> recentEvents = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      recentEvents.add(new VirtualThreadStatistics.ThreadPinningEvent(
          "thread-" + i, 1000L, "stack-trace-" + i, Instant.now()));
    }

    when(scheduler.getMissingTriggerDescriptions()).thenReturn(Collections.emptyList());
    when(scheduler.isVirtualThreadsEnabled()).thenReturn(true);
    when(virtualThreadStats.getPinnedThreadsCount()).thenReturn(5); // Below threshold
    when(virtualThreadStats.getMaxPinningDurationMs()).thenReturn(1000L); // Below threshold
    when(virtualThreadStats.getRecentPinningEvents()).thenReturn(recentEvents);
    when(virtualThreadStats.getVirtualThreadsCreated()).thenReturn(100);
    when(virtualThreadStats.getVirtualThreadsTerminated()).thenReturn(80);
    when(virtualThreadStats.getActiveVirtualThreads()).thenReturn(20);
    when(virtualThreadStats.getAveragePinningDurationMs()).thenReturn(500.0);

    Result result = underTest.check();

    assertThat(result.isHealthy(), is(false));
    assertThat(result.getMessage(), containsString("thread pinning events detected"));
  }

  @Test
  public void testMultipleHealthIssues() {
    List<String> missingTriggers = new ArrayList<>();
    missingTriggers.add("task1");

    when(scheduler.getMissingTriggerDescriptions()).thenReturn(missingTriggers);
    when(scheduler.isVirtualThreadsEnabled()).thenReturn(true);
    when(virtualThreadStats.getPinnedThreadsCount()).thenReturn(20); // Above threshold
    when(virtualThreadStats.getMaxPinningDurationMs()).thenReturn(10000L); // Above threshold
    when(virtualThreadStats.getRecentPinningEvents()).thenReturn(Collections.emptyList());
    when(virtualThreadStats.getVirtualThreadsCreated()).thenReturn(100);
    when(virtualThreadStats.getVirtualThreadsTerminated()).thenReturn(80);
    when(virtualThreadStats.getActiveVirtualThreads()).thenReturn(20);
    when(virtualThreadStats.getAveragePinningDurationMs()).thenReturn(2000.0);

    Result result = underTest.check();

    assertThat(result.isHealthy(), is(false));
    assertThat(result.getMessage(), containsString("tasks require frequency updates"));
    assertThat(result.getMessage(), containsString("Excessive virtual thread pinning detected"));
    assertThat(result.getMessage(), containsString("Long-duration thread pinning detected"));
  }
}