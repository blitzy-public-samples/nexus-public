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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.scheduling.spi.SchedulerSPI;

import com.codahale.metrics.health.HealthCheck;

import jdk.jfr.consumer.RecordingStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

/**
 * Scheduler health checks that reports a list of task descriptions that were recovered and had manual triggers created
 * automatically. This indicates to a user that those tasks should be reconfigured to their desired specification.
 * 
 * Also monitors virtual thread pool operations and detects thread-pinning issues that could block carrier threads.
 *
 * @since 3.17
 */
@Named("Scheduler")
@Singleton
public class SchedulerHealthCheck
    extends HealthCheck
{
  private final Provider<SchedulerSPI> scheduler;
  
  // Virtual thread metrics
  private final AtomicLong virtualThreadsCreated = new AtomicLong(0);
  private final AtomicLong virtualThreadsActive = new AtomicLong(0);
  private final AtomicLong virtualThreadsPinned = new AtomicLong(0);
  private final List<VirtualThreadPinningEvent> recentPinningEvents = new ArrayList<>();
  private final int MAX_PINNING_EVENTS = 10; // Store at most 10 recent pinning events
  private final Duration PINNING_THRESHOLD = Duration.ofMillis(100); // Consider pinning significant if > 100ms
  
  // Thread for monitoring virtual thread pinning events
  private Thread pinningMonitorThread;
  private volatile boolean monitoringActive = false;

  @Inject
  public SchedulerHealthCheck(final Provider<SchedulerSPI> scheduler) {
    this.scheduler = checkNotNull(scheduler);
    startVirtualThreadMonitoring();
  }

  /**
   * Start monitoring virtual thread pinning events using JFR
   */
  private void startVirtualThreadMonitoring() {
    // Only start monitoring if we're running on Java 21 or later
    if (!isJava21OrLater()) {
      return;
    }
    
    monitoringActive = true;
    pinningMonitorThread = new Thread(() -> {
      try (RecordingStream rs = new RecordingStream()) {
        // Configure and enable VirtualThreadPinned event
        rs.enable("jdk.VirtualThreadPinned").withStackTrace();
        
        // Listen for VirtualThreadStart and VirtualThreadEnd events to track counts
        rs.enable("jdk.VirtualThreadStart");
        rs.enable("jdk.VirtualThreadEnd");
        
        // Handle VirtualThreadPinned events
        rs.onEvent("jdk.VirtualThreadPinned", event -> {
          virtualThreadsPinned.incrementAndGet();
          
          // Extract event details
          String threadName = event.getString("eventThread");
          Duration duration = Duration.ofNanos(event.getLong("duration"));
          String stackTrace = event.getStackTrace() != null ? 
              event.getStackTrace().toString() : "<stack trace unavailable>";
          
          // Only record significant pinning events (longer than threshold)
          if (duration.compareTo(PINNING_THRESHOLD) > 0) {
            synchronized (recentPinningEvents) {
              // Keep only the most recent events
              if (recentPinningEvents.size() >= MAX_PINNING_EVENTS) {
                recentPinningEvents.remove(0);
              }
              recentPinningEvents.add(new VirtualThreadPinningEvent(threadName, duration, stackTrace));
            }
          }
        });
        
        // Track virtual thread creation and termination
        rs.onEvent("jdk.VirtualThreadStart", event -> {
          virtualThreadsCreated.incrementAndGet();
          virtualThreadsActive.incrementAndGet();
        });
        
        rs.onEvent("jdk.VirtualThreadEnd", event -> {
          virtualThreadsActive.decrementAndGet();
        });
        
        // Start the recording
        rs.start();
      } catch (Exception e) {
        // Log the exception but don't fail the health check
        System.err.println("Error monitoring virtual thread events: " + e.getMessage());
      } finally {
        monitoringActive = false;
      }
    }, "virtual-thread-monitor");
    
    pinningMonitorThread.setDaemon(true);
    pinningMonitorThread.start();
  }
  
  /**
   * Check if we're running on Java 21 or later
   */
  private boolean isJava21OrLater() {
    try {
      String version = System.getProperty("java.version");
      if (version.startsWith("1.")) {
        // Old version format (1.8, etc.)
        return false;
      } else {
        // New version format (9, 10, 11, etc.)
        int majorVersion = Integer.parseInt(version.split("\\.")[0]);
        return majorVersion >= 21;
      }
    } catch (Exception e) {
      // If we can't determine the version, assume we're not on Java 21
      return false;
    }
  }

  @Override
  protected Result check() {
    List<String> missingTaskDescriptions = scheduler.get().getMissingTriggerDescriptions();
    
    // Check for virtual thread issues if we're monitoring them
    if (monitoringActive) {
      List<String> virtualThreadIssues = checkVirtualThreadIssues();
      
      // If we have both missing task descriptions and virtual thread issues, combine them
      if (!missingTaskDescriptions.isEmpty() && !virtualThreadIssues.isEmpty()) {
        return Result.unhealthy(reasonForMissingTasks(missingTaskDescriptions) + 
            "\n" + reasonForVirtualThreadIssues(virtualThreadIssues));
      }
      
      // If we only have virtual thread issues
      if (!virtualThreadIssues.isEmpty()) {
        return Result.unhealthy(reasonForVirtualThreadIssues(virtualThreadIssues));
      }
    }
    
    // If we only have missing task descriptions or no issues at all
    return missingTaskDescriptions.isEmpty() ? 
        Result.healthy() : 
        Result.unhealthy(reasonForMissingTasks(missingTaskDescriptions));
  }
  
  /**
   * Check for virtual thread issues
   * 
   * @return List of issue descriptions, empty if no issues
   */
  private List<String> checkVirtualThreadIssues() {
    List<String> issues = new ArrayList<>();
    
    // Check for excessive pinning events
    synchronized (recentPinningEvents) {
      if (!recentPinningEvents.isEmpty()) {
        // If we have any significant pinning events, report them
        issues.add("Virtual thread pinning detected");
      }
    }
    
    // Check for carrier thread exhaustion
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    long totalThreads = threadMXBean.getThreadCount();
    long daemonThreads = threadMXBean.getDaemonThreadCount();
    
    // Get thread info for all threads to check for blocked carrier threads
    ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 0);
    long blockedThreads = 0;
    if (threadInfos != null) {
      for (ThreadInfo info : threadInfos) {
        if (info != null && info.getThreadState() == Thread.State.BLOCKED) {
          blockedThreads++;
        }
      }
    }
    
    // If more than 50% of carrier threads are blocked, report an issue
    int availableProcessors = Runtime.getRuntime().availableProcessors();
    if (blockedThreads > availableProcessors / 2) {
      issues.add("Excessive carrier thread blocking detected");
    }
    
    return issues;
  }

  private String reasonForMissingTasks(final List<String> missingTaskDescriptions) {
    String taskDescriptions = String.join(", ", missingTaskDescriptions);
    return format("%s tasks require frequency updates: %s", missingTaskDescriptions.size(), taskDescriptions);
  }
  
  private String reasonForVirtualThreadIssues(final List<String> issues) {
    StringBuilder sb = new StringBuilder("Virtual thread issues detected: ");
    sb.append(String.join(", ", issues));
    
    // Add virtual thread statistics
    sb.append("\nVirtual thread statistics: ")
      .append("created=").append(virtualThreadsCreated.get())
      .append(", active=").append(virtualThreadsActive.get())
      .append(", pinned=").append(virtualThreadsPinned.get());
    
    // Add details about recent pinning events
    synchronized (recentPinningEvents) {
      if (!recentPinningEvents.isEmpty()) {
        sb.append("\nRecent pinning events:");
        for (VirtualThreadPinningEvent event : recentPinningEvents) {
          sb.append("\n  Thread: ").append(event.threadName)
            .append(", Duration: ").append(event.duration.toMillis()).append("ms")
            .append("\n  Stack trace: ").append(event.stackTrace);
        }
      }
    }
    
    return sb.toString();
  }
  
  /**
   * Class to store information about virtual thread pinning events
   */
  private static class VirtualThreadPinningEvent {
    final String threadName;
    final Duration duration;
    final String stackTrace;
    
    VirtualThreadPinningEvent(String threadName, Duration duration, String stackTrace) {
      this.threadName = threadName;
      this.duration = duration;
      this.stackTrace = stackTrace;
    }
  }
}