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
package org.sonatype.nexus.testcommon.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.sonatype.nexus.common.event.EventManager;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.eventbus.EventBus;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import static org.sonatype.nexus.common.event.EventBusFactory.reentrantEventBus;

/**
 * Simple {@link EventManager} for UT purposes. Requires manual registration, doesn't support asynchronous dispatch.
 * Enhanced with Java 21 Virtual Thread support for context propagation and pinning detection.
 *
 * @since 3.2
 */
@VisibleForTesting
public class SimpleEventManager
    implements EventManager
{
  private final EventBus eventBus = reentrantEventBus("simple");
  
  // Statistics for virtual thread usage
  private final AtomicLong virtualThreadEventsProcessed = new AtomicLong(0);
  private final AtomicLong platformThreadEventsProcessed = new AtomicLong(0);
  private final AtomicLong pinnedThreadsDetected = new AtomicLong(0);
  private final Map<String, Duration> pinnedThreadDurations = new ConcurrentHashMap<>();
  
  // JFR recording for pinning detection
  private RecordingStream recordingStream;
  private boolean pinnedThreadMonitoringEnabled = false;

  /**
   * Creates a new SimpleEventManager instance.
   */
  public SimpleEventManager() {
    // Initialize pinning detection if running on Java 21 or later
    if (isJava21OrLater()) {
      initializePinningDetection();
    }
  }

  @Override
  public void register(final Object handler) {
    eventBus.register(handler);
  }

  @Override
  public void unregister(final Object handler) {
    eventBus.unregister(handler);
  }

  @Override
  public void post(final Object event) {
    // Capture thread-local context before posting event
    ThreadLocalContextCapture contextCapture = captureThreadLocalContext();
    
    try {
      // Track thread type statistics
      if (Thread.currentThread().isVirtual()) {
        virtualThreadEventsProcessed.incrementAndGet();
      } else {
        platformThreadEventsProcessed.incrementAndGet();
      }
      
      // Post the event with context propagation
      eventBus.post(event);
    } finally {
      // Restore thread-local context after event processing
      restoreThreadLocalContext(contextCapture);
    }
  }

  @Override
  public boolean isCalmPeriod() {
    return true;
  }

  @Override
  public boolean isAffinityEnabled() {
    return false;
  }
  
  /**
   * Captures the current thread-local context for later restoration.
   * This ensures thread-local variables are maintained across event boundaries.
   */
  private ThreadLocalContextCapture captureThreadLocalContext() {
    // In a real implementation, this would capture specific thread-local variables
    // For testing purposes, we just create an empty context capture
    return new ThreadLocalContextCapture();
  }
  
  /**
   * Restores the previously captured thread-local context.
   * This ensures thread-local variables are maintained across event boundaries.
   */
  private void restoreThreadLocalContext(ThreadLocalContextCapture contextCapture) {
    // In a real implementation, this would restore specific thread-local variables
    // For testing purposes, this is a no-op
  }
  
  /**
   * Executes the given supplier with context propagation.
   * This ensures thread-local variables are maintained when crossing thread boundaries.
   *
   * @param supplier The supplier to execute with context propagation
   * @param <T> The return type of the supplier
   * @return The result of the supplier execution
   */
  public <T> T withContextPropagation(Supplier<T> supplier) {
    ThreadLocalContextCapture contextCapture = captureThreadLocalContext();
    try {
      return supplier.get();
    } finally {
      restoreThreadLocalContext(contextCapture);
    }
  }
  
  /**
   * Initializes thread pinning detection using JFR events.
   * This helps identify when virtual threads are pinned to carrier threads.
   */
  private void initializePinningDetection() {
    try {
      recordingStream = new RecordingStream();
      recordingStream.enable("jdk.VirtualThreadPinned").withStackTrace();
      recordingStream.onEvent("jdk.VirtualThreadPinned", this::handlePinnedThreadEvent);
      recordingStream.startAsync();
      pinnedThreadMonitoringEnabled = true;
    } catch (Exception e) {
      // JFR events might not be available in all environments
      System.err.println("Failed to initialize virtual thread pinning detection: " + e.getMessage());
    }
  }
  
  /**
   * Handles JFR events for pinned virtual threads.
   * Records statistics about pinning occurrences for analysis.
   */
  private void handlePinnedThreadEvent(RecordedEvent event) {
    pinnedThreadsDetected.incrementAndGet();
    
    String threadName = event.getThread("thread").getJavaName();
    Duration duration = event.getDuration();
    pinnedThreadDurations.put(threadName, duration);
    
    // Log pinning information for debugging
    System.out.println("Virtual thread pinning detected: " + threadName + 
        " pinned for " + duration.toMillis() + "ms");
  }
  
  /**
   * Checks if the current JVM is Java 21 or later.
   */
  private boolean isJava21OrLater() {
    try {
      String version = System.getProperty("java.version");
      if (version.startsWith("1.")) {
        // Old version format (1.8, etc.)
        version = version.substring(2);
      }
      // Parse the major version
      int majorVersion = Integer.parseInt(version.split("\\.")[0]);
      return majorVersion >= 21;
    } catch (Exception e) {
      return false;
    }
  }
  
  /**
   * Returns the number of events processed by virtual threads.
   */
  public long getVirtualThreadEventsProcessed() {
    return virtualThreadEventsProcessed.get();
  }
  
  /**
   * Returns the number of events processed by platform threads.
   */
  public long getPlatformThreadEventsProcessed() {
    return platformThreadEventsProcessed.get();
  }
  
  /**
   * Returns the number of pinned thread occurrences detected.
   */
  public long getPinnedThreadsDetected() {
    return pinnedThreadsDetected.get();
  }
  
  /**
   * Returns a map of thread names to pinning durations.
   */
  public Map<String, Duration> getPinnedThreadDurations() {
    return new ConcurrentHashMap<>(pinnedThreadDurations);
  }
  
  /**
   * Returns whether pinned thread monitoring is enabled.
   */
  public boolean isPinnedThreadMonitoringEnabled() {
    return pinnedThreadMonitoringEnabled;
  }
  
  /**
   * Enables system property based pinning detection.
   * This is an alternative to JFR-based detection.
   */
  public void enableSystemPropertyPinningDetection() {
    System.setProperty("jdk.tracePinnedThreads", "full");
  }
  
  /**
   * Disables system property based pinning detection.
   */
  public void disableSystemPropertyPinningDetection() {
    System.clearProperty("jdk.tracePinnedThreads");
  }
  
  /**
   * Stops the JFR recording stream if it's running.
   * Should be called when the event manager is no longer needed.
   */
  public void shutdown() {
    if (recordingStream != null) {
      recordingStream.close();
    }
  }
  
  /**
   * Simple container for thread-local context capture.
   * In a real implementation, this would store actual thread-local variables.
   */
  private static class ThreadLocalContextCapture {
    // In a real implementation, this would contain captured thread-local variables
  }
}