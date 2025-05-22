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
package org.sonatype.nexus.testcommon.virtualthread;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import jdk.jfr.consumer.RecordingStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for detecting and analyzing when Java 21 Virtual Threads get pinned to platform threads.
 * <p>
 * Thread pinning occurs when a Virtual Thread cannot unmount from its carrier platform thread,
 * which negates many of the benefits of Virtual Threads by forcing them to occupy a carrier thread
 * for their entire execution. This class provides methods to detect pinning events, analyze stack
 * traces to identify the cause of pinning, and generate reports to help developers optimize their code.
 * <p>
 * Common causes of thread pinning include:
 * <ul>
 *   <li>Synchronized blocks or methods</li>
 *   <li>Native methods</li>
 *   <li>Thread-local variables with initial values</li>
 *   <li>JNI critical sections</li>
 * </ul>
 * <p>
 * This detector integrates with JDK's built-in pinning detection via {@code jdk.tracePinnedThreads}
 * and enhances it with Nexus-specific context.
 *
 * @since 3.60
 */
public class ThreadPinningDetector
{
  private static final Logger log = LoggerFactory.getLogger(ThreadPinningDetector.class);

  private static final String JDK_TRACE_PINNED_THREADS_PROPERTY = "jdk.tracePinnedThreads";

  private static final String JFR_VIRTUAL_THREAD_PINNED_EVENT = "jdk.VirtualThreadPinned";

  private static final AtomicBoolean jfrMonitoringActive = new AtomicBoolean(false);

  private static final ConcurrentHashMap<String, PinningInfo> pinningLocations = new ConcurrentHashMap<>();

  private ThreadPinningDetector() {
    // Utility class, no instances
  }

  /**
   * Enables thread pinning detection using the JDK's built-in mechanism.
   * <p>
   * This sets the {@code jdk.tracePinnedThreads} system property to "full" which causes
   * the JVM to log complete stack traces when virtual threads are pinned.
   * <p>
   * Note: This method must be called before the JVM starts, typically by setting
   * the system property on the command line. Calling this method after JVM startup
   * will have no effect on already running threads.
   */
  public static void enableJdkPinningDetection() {
    System.setProperty(JDK_TRACE_PINNED_THREADS_PROPERTY, "full");
    log.info("Enabled JDK thread pinning detection with full stack traces");
  }

  /**
   * Enables thread pinning detection using the JDK's built-in mechanism with concise output.
   * <p>
   * This sets the {@code jdk.tracePinnedThreads} system property to "short" which causes
   * the JVM to log only the problematic frames when virtual threads are pinned.
   * <p>
   * Note: This method must be called before the JVM starts, typically by setting
   * the system property on the command line. Calling this method after JVM startup
   * will have no effect on already running threads.
   */
  public static void enableJdkPinningDetectionConcise() {
    System.setProperty(JDK_TRACE_PINNED_THREADS_PROPERTY, "short");
    log.info("Enabled JDK thread pinning detection with concise stack traces");
  }

  /**
   * Checks if JDK thread pinning detection is enabled.
   *
   * @return true if the jdk.tracePinnedThreads property is set to either "full" or "short"
   */
  public static boolean isJdkPinningDetectionEnabled() {
    String value = System.getProperty(JDK_TRACE_PINNED_THREADS_PROPERTY);
    return "full".equals(value) || "short".equals(value);
  }

  /**
   * Starts monitoring for thread pinning events using JFR (Java Flight Recorder).
   * <p>
   * This method starts a background thread that listens for {@code jdk.VirtualThreadPinned} events
   * and processes them using the provided event handler. The monitoring continues until
   * {@link #stopJfrMonitoring()} is called.
   * <p>
   * JFR monitoring provides more detailed information than the JDK's built-in mechanism,
   * including the duration of pinning events.
   *
   * @param eventHandler the handler to process pinning events
   * @throws IllegalStateException if monitoring is already active
   */
  public static void startJfrMonitoring(Consumer<PinningEvent> eventHandler) {
    if (!jfrMonitoringActive.compareAndSet(false, true)) {
      throw new IllegalStateException("JFR thread pinning monitoring is already active");
    }

    Thread monitoringThread = new Thread(() -> {
      try (RecordingStream rs = new RecordingStream()) {
        rs.enable(JFR_VIRTUAL_THREAD_PINNED_EVENT).withStackTrace();
        rs.onEvent(JFR_VIRTUAL_THREAD_PINNED_EVENT, event -> {
          PinningEvent pinningEvent = new PinningEvent(
              event.getString("eventThread"),
              event.getDuration().toMillis(),
              event.getStackTrace() != null ? event.getStackTrace().toString() : "<no stack trace>"
          );
          
          // Track pinning locations for statistics
          String location = pinningEvent.getLocation();
          pinningLocations.compute(location, (k, v) -> {
            if (v == null) {
              return new PinningInfo(location, 1, pinningEvent.getDurationMs());
            }
            else {
              v.incrementCount();
              v.addDuration(pinningEvent.getDurationMs());
              return v;
            }
          });
          
          // Process the event with the provided handler
          eventHandler.accept(pinningEvent);
        });
        
        log.info("Started JFR monitoring for Virtual Thread pinning events");
        rs.start();
      }
      catch (Exception e) {
        log.error("Error in JFR thread pinning monitoring", e);
      }
      finally {
        jfrMonitoringActive.set(false);
      }
    }, "thread-pinning-monitor");
    
    monitoringThread.setDaemon(true);
    monitoringThread.start();
  }

  /**
   * Starts monitoring for thread pinning events using JFR with a default handler that logs events.
   * <p>
   * This is a convenience method that uses a default event handler which logs pinning events
   * at WARN level.
   */
  public static void startJfrMonitoring() {
    startJfrMonitoring(event -> {
      log.warn("Virtual Thread pinning detected: {} - Duration: {} ms\nStack trace:\n{}", 
          event.getThreadName(), event.getDurationMs(), event.getStackTrace());
    });
  }

  /**
   * Stops the JFR monitoring for thread pinning events.
   * <p>
   * This method has no effect if monitoring is not active.
   */
  public static void stopJfrMonitoring() {
    if (jfrMonitoringActive.compareAndSet(true, false)) {
      log.info("Stopping JFR monitoring for Virtual Thread pinning events");
      // The monitoring thread will detect this flag and shut down gracefully
    }
  }

  /**
   * Returns whether JFR monitoring for thread pinning is currently active.
   *
   * @return true if monitoring is active, false otherwise
   */
  public static boolean isJfrMonitoringActive() {
    return jfrMonitoringActive.get();
  }

  /**
   * Gets statistics about all detected pinning locations.
   *
   * @return a list of pinning information objects, sorted by total pinning duration (descending)
   */
  public static List<PinningInfo> getPinningStatistics() {
    List<PinningInfo> stats = new ArrayList<>(pinningLocations.values());
    stats.sort((a, b) -> Long.compare(b.getTotalDurationMs(), a.getTotalDurationMs()));
    return stats;
  }

  /**
   * Clears all collected pinning statistics.
   */
  public static void clearPinningStatistics() {
    pinningLocations.clear();
  }

  /**
   * Analyzes a stack trace to determine if it contains common causes of thread pinning.
   * <p>
   * This method examines the provided stack trace string for patterns that indicate
   * known causes of thread pinning, such as synchronized blocks, native methods, etc.
   *
   * @param stackTrace the stack trace to analyze
   * @return a list of potential pinning causes found in the stack trace
   */
  public static List<String> analyzePinningCauses(String stackTrace) {
    List<String> causes = new ArrayList<>();
    
    if (stackTrace == null || stackTrace.isEmpty()) {
      return causes;
    }
    
    // Check for synchronized blocks/methods
    if (stackTrace.contains("monitors:") || stackTrace.contains("<== monitors:")) {
      causes.add("Synchronized block or method detected");
    }
    
    // Check for native methods
    if (stackTrace.contains("(Native Method)")) {
      causes.add("Native method call detected");
    }
    
    // Check for JNI critical sections
    if (stackTrace.contains("JNI critical")) {
      causes.add("JNI critical section detected");
    }
    
    // Check for thread-local operations
    if (stackTrace.contains("ThreadLocal")) {
      causes.add("ThreadLocal operation detected");
    }
    
    return causes;
  }

  /**
   * Extracts the most likely location of thread pinning from a stack trace.
   * <p>
   * This method attempts to identify the application code that is most likely responsible
   * for the thread pinning, filtering out JDK internal frames.
   *
   * @param stackTrace the stack trace to analyze
   * @return the most likely location of thread pinning, or "Unknown location" if not found
   */
  public static String extractPinningLocation(String stackTrace) {
    if (stackTrace == null || stackTrace.isEmpty()) {
      return "Unknown location";
    }
    
    // Look for the first non-JDK frame that might be causing pinning
    String[] lines = stackTrace.split("\n");
    for (String line : lines) {
      line = line.trim();
      
      // Skip JDK internal frames
      if (line.startsWith("java.base/") || line.startsWith("jdk.internal.")) {
        continue;
      }
      
      // Look for application code, especially with monitor indicators
      if (line.contains("monitors:") || 
          (!line.startsWith("java.") && !line.startsWith("sun.") && line.contains("(")) ||
          line.contains("<== monitors:")) {
        return line;
      }
    }
    
    return "Unknown location";
  }

  /**
   * Generates a report of the JVM's thread configuration relevant to Virtual Threads.
   *
   * @return a string containing the report
   */
  public static String generateThreadConfigReport() {
    StringBuilder report = new StringBuilder();
    report.append("=== Virtual Thread Configuration Report ===\n");
    
    // JVM version information
    report.append("Java Version: ").append(System.getProperty("java.version")).append("\n");
    report.append("VM Name: ").append(System.getProperty("java.vm.name")).append("\n");
    report.append("VM Version: ").append(System.getProperty("java.vm.version")).append("\n");
    
    // Thread pinning detection configuration
    report.append("\nThread Pinning Detection:\n");
    report.append("  jdk.tracePinnedThreads: ").append(System.getProperty(JDK_TRACE_PINNED_THREADS_PROPERTY, "not set")).append("\n");
    report.append("  JFR Monitoring Active: ").append(isJfrMonitoringActive()).append("\n");
    
    // Thread-related JVM flags
    report.append("\nRelevant JVM Flags:\n");
    List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
    for (String arg : inputArguments) {
      if (arg.contains("Thread") || arg.contains("thread") || 
          arg.contains("Virtual") || arg.contains("virtual") || 
          arg.contains("Carrier") || arg.contains("carrier") ||
          arg.contains("jdk.trace")) {
        report.append("  ").append(arg).append("\n");
      }
    }
    
    // Thread statistics
    report.append("\nThread Statistics:\n");
    report.append("  Available Processors: ").append(Runtime.getRuntime().availableProcessors()).append("\n");
    
    return report.toString();
  }

  /**
   * Represents a thread pinning event detected by JFR.
   */
  public static class PinningEvent
  {
    private final String threadName;
    private final long durationMs;
    private final String stackTrace;
    private final String location;
    private final List<String> causes;

    public PinningEvent(String threadName, long durationMs, String stackTrace) {
      this.threadName = threadName;
      this.durationMs = durationMs;
      this.stackTrace = stackTrace;
      this.location = extractPinningLocation(stackTrace);
      this.causes = analyzePinningCauses(stackTrace);
    }

    public String getThreadName() {
      return threadName;
    }

    public long getDurationMs() {
      return durationMs;
    }

    public String getStackTrace() {
      return stackTrace;
    }

    public String getLocation() {
      return location;
    }

    public List<String> getCauses() {
      return causes;
    }

    @Override
    public String toString() {
      return "PinningEvent{" +
          "threadName='" + threadName + '\'' +
          ", durationMs=" + durationMs +
          ", location='" + location + '\'' +
          ", causes=" + causes +
          '}';
    }
  }

  /**
   * Contains statistical information about pinning at a specific code location.
   */
  public static class PinningInfo
  {
    private final String location;
    private int count;
    private long totalDurationMs;

    public PinningInfo(String location, int count, long initialDurationMs) {
      this.location = location;
      this.count = count;
      this.totalDurationMs = initialDurationMs;
    }

    public String getLocation() {
      return location;
    }

    public int getCount() {
      return count;
    }

    public long getTotalDurationMs() {
      return totalDurationMs;
    }

    public double getAverageDurationMs() {
      return count > 0 ? (double) totalDurationMs / count : 0;
    }

    void incrementCount() {
      count++;
    }

    void addDuration(long durationMs) {
      totalDurationMs += durationMs;
    }

    @Override
    public String toString() {
      return "PinningInfo{" +
          "location='" + location + '\'' +
          ", count=" + count +
          ", totalDurationMs=" + totalDurationMs +
          ", averageDurationMs=" + getAverageDurationMs() +
          '}';
    }
  }
}