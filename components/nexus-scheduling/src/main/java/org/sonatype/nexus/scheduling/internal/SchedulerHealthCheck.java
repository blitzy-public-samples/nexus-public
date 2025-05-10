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
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.scheduling.internal.VirtualThreadStatistics.ThreadPinningEvent;
import org.sonatype.nexus.scheduling.spi.SchedulerSPI;

import com.codahale.metrics.health.HealthCheck;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

/**
 * Scheduler health check that monitors:
 * <ul>
 *   <li>Tasks that were recovered and had manual triggers created automatically</li>
 *   <li>Virtual thread pool operations and health metrics</li>
 *   <li>Thread pinning issues that could block carrier threads</li>
 *   <li>Operations that are incompatible with virtual threads</li>
 * </ul>
 *
 * @since 3.17
 */
@Named("Scheduler")
@Singleton
public class SchedulerHealthCheck
    extends HealthCheck
{
  private final Provider<SchedulerSPI> scheduler;
  private final VirtualThreadStatistics virtualThreadStats;
  
  // Thresholds for health check
  private static final int MAX_PINNED_THREADS_THRESHOLD = 10; // More than this many pinned threads is unhealthy
  private static final long MAX_PINNING_DURATION_THRESHOLD_MS = 5000; // Pinning longer than 5 seconds is unhealthy
  private static final int RECENT_PINNING_EVENTS_THRESHOLD = 5; // More than this many recent events is unhealthy
  private static final Duration RECENT_EVENT_WINDOW = Duration.ofMinutes(5); // Events in the last 5 minutes

  @Inject
  public SchedulerHealthCheck(
      final Provider<SchedulerSPI> scheduler,
      final VirtualThreadStatistics virtualThreadStats)
  {
    this.scheduler = checkNotNull(scheduler);
    this.virtualThreadStats = checkNotNull(virtualThreadStats);
  }

  @Override
  protected Result check() {
    List<String> healthIssues = new ArrayList<>();
    
    // Check for missing task triggers
    List<String> missingTaskDescriptions = scheduler.get().getMissingTriggerDescriptions();
    if (!missingTaskDescriptions.isEmpty()) {
      healthIssues.add(reasonForMissingTriggers(missingTaskDescriptions));
    }
    
    // Only check virtual thread health if virtual threads are enabled
    if (scheduler.get().isVirtualThreadsEnabled()) {
      // Check for excessive thread pinning
      if (virtualThreadStats.getPinnedThreadsCount() > MAX_PINNED_THREADS_THRESHOLD) {
        healthIssues.add(format("Excessive virtual thread pinning detected: %d pinned threads", 
            virtualThreadStats.getPinnedThreadsCount()));
      }
      
      // Check for long-duration pinning events
      if (virtualThreadStats.getMaxPinningDurationMs() > MAX_PINNING_DURATION_THRESHOLD_MS) {
        healthIssues.add(format("Long-duration thread pinning detected: %d ms maximum pinning duration", 
            virtualThreadStats.getMaxPinningDurationMs()));
      }
      
      // Check for recent pinning events
      List<ThreadPinningEvent> recentEvents = getRecentPinningEvents();
      if (recentEvents.size() > RECENT_PINNING_EVENTS_THRESHOLD) {
        healthIssues.add(format("%d thread pinning events detected in the last %d minutes", 
            recentEvents.size(), RECENT_EVENT_WINDOW.toMinutes()));
      }
    }
    
    if (healthIssues.isEmpty()) {
      return Result.healthy();
    } else {
      return Result.unhealthy(String.join("; ", healthIssues))
          .withDetail("virtualThreadsEnabled", scheduler.get().isVirtualThreadsEnabled())
          .withDetail("virtualThreadsCreated", virtualThreadStats.getVirtualThreadsCreated())
          .withDetail("virtualThreadsTerminated", virtualThreadStats.getVirtualThreadsTerminated())
          .withDetail("activeVirtualThreads", virtualThreadStats.getActiveVirtualThreads())
          .withDetail("pinnedThreadsCount", virtualThreadStats.getPinnedThreadsCount())
          .withDetail("maxPinningDurationMs", virtualThreadStats.getMaxPinningDurationMs())
          .withDetail("avgPinningDurationMs", virtualThreadStats.getAveragePinningDurationMs())
          .withDetail("recentPinningEvents", recentEvents.size());
    }
  }

  private String reasonForMissingTriggers(final List<String> missingTaskDescriptions) {
    String taskDescriptions = String.join(", ", missingTaskDescriptions);
    return format("%s tasks require frequency updates: %s", missingTaskDescriptions.size(), taskDescriptions);
  }
  
  /**
   * Returns pinning events that occurred within the recent event window.
   */
  private List<ThreadPinningEvent> getRecentPinningEvents() {
    List<ThreadPinningEvent> recentEvents = new ArrayList<>();
    for (ThreadPinningEvent event : virtualThreadStats.getRecentPinningEvents()) {
      if (event.getAge().compareTo(RECENT_EVENT_WINDOW) <= 0) {
        recentEvents.add(event);
      }
    }
    return recentEvents;
  }
}