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

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import jdk.jfr.consumer.RecordingStream;

import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.TASKS;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;

/**
 * Monitors virtual thread pinning events using JDK Flight Recorder (JFR).
 * 
 * This component listens for the jdk.VirtualThreadPinned JFR event, which is emitted
 * when a virtual thread is pinned to its carrier thread for longer than a threshold
 * duration (default 20ms). When such events are detected, they are recorded in the
 * {@link VirtualThreadStatistics} for health monitoring.
 * 
 * @since 3.60
 */
@Named
@Singleton
@ManagedLifecycle(phase = TASKS)
public class VirtualThreadPinningMonitor
    extends StateGuardLifecycleSupport
{
  private static final String JFR_VIRTUAL_THREAD_PINNED_EVENT = "jdk.VirtualThreadPinned";
  
  private final VirtualThreadStatistics statistics;
  private final ExecutorService monitoringExecutor;
  private RecordingStream eventStream;
  
  @Inject
  public VirtualThreadPinningMonitor(final VirtualThreadStatistics statistics) {
    this.statistics = statistics;
    this.monitoringExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "virtual-thread-pinning-monitor");
      thread.setDaemon(true);
      return thread;
    });
  }
  
  @PostConstruct
  public void initialize() {
    try {
      start();
    }
    catch (Exception e) {
      log.error("Failed to start virtual thread pinning monitor", e);
    }
  }
  
  @Override
  @Guarded(by = STARTED)
  protected void doStart() throws Exception {
    try {
      eventStream = new RecordingStream();
      
      // Enable the VirtualThreadPinned event with stack trace
      eventStream.enable(JFR_VIRTUAL_THREAD_PINNED_EVENT).withStackTrace();
      
      // Register event handler
      eventStream.onEvent(JFR_VIRTUAL_THREAD_PINNED_EVENT, event -> {
        try {
          String threadName = event.getString("eventThread");
          long durationMs = event.getDuration().toMillis();
          String stackTrace = event.getStackTrace() != null ? event.getStackTrace().toString() : "<no stack trace>";
          
          statistics.recordThreadPinning(threadName, durationMs, stackTrace);
          
          if (log.isDebugEnabled()) {
            log.debug("Virtual thread pinning detected: {} was pinned for {}ms", threadName, durationMs);
          }
        }
        catch (Exception e) {
          log.warn("Error processing virtual thread pinning event", e);
        }
      });
      
      // Start the event stream in a separate thread
      monitoringExecutor.submit(() -> {
        try {
          log.info("Starting virtual thread pinning monitoring");
          eventStream.start();
        }
        catch (Exception e) {
          log.error("Error in virtual thread pinning monitoring", e);
        }
      });
    }
    catch (Exception e) {
      log.error("Failed to initialize virtual thread pinning monitoring", e);
      throw e;
    }
  }
  
  @Override
  @Guarded(by = STARTED)
  protected void doStop() throws Exception {
    if (eventStream != null) {
      try {
        eventStream.close();
      }
      catch (IOException e) {
        log.warn("Error closing JFR recording stream", e);
      }
      eventStream = null;
    }
    
    monitoringExecutor.shutdownNow();
  }
  
  @PreDestroy
  public void shutdown() {
    try {
      stop();
    }
    catch (Exception e) {
      log.error("Error stopping virtual thread pinning monitor", e);
    }
  }
}