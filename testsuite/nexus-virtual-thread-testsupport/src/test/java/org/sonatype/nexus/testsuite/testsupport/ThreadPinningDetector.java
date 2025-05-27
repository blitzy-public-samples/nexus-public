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
package org.sonatype.nexus.testsuite.testsupport;

import java.io.StringWriter;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for detecting and monitoring thread pinning in Virtual Threads.
 * 
 * Thread pinning occurs when a Virtual Thread becomes "pinned" to its carrier thread,
 * preventing the carrier from being reused for other Virtual Threads and reducing efficiency.
 * This class uses JFR (Java Flight Recorder) to detect such events and provides mechanisms
 * to analyze and report them.
 *
 * @since 3.60
 */
public class ThreadPinningDetector
{
  private static final Logger log = LoggerFactory.getLogger(ThreadPinningDetector.class);
  
  private static final String JFR_VIRTUAL_THREAD_PINNED_EVENT = "jdk.VirtualThreadPinned";
  private static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(10);
  private static final Duration DEFAULT_THRESHOLD = Duration.ofMillis(20);
  
  private final List<ThreadPinningEvent> pinningEvents = new CopyOnWriteArrayList<>();
  private final List<Consumer<ThreadPinningEvent>> listeners = new CopyOnWriteArrayList<>();
  private final Duration threshold;
  
  private RecordingStream recordingStream;
  private volatile boolean running = false;
  
  /**
   * Creates a new thread pinning detector with the default threshold (20ms).
   */
  public ThreadPinningDetector() {
    this(DEFAULT_THRESHOLD);
  }
  
  /**
   * Creates a new thread pinning detector with the specified threshold.
   * 
   * @param threshold The minimum duration for a pinning event to be reported
   */
  public ThreadPinningDetector(final Duration threshold) {
    this.threshold = threshold;
  }
  
  /**
   * Starts the detector.
   * 
   * This initializes JFR event recording and begins monitoring for thread pinning events.
   */
  public void start() {
    if (running) {
      return;
    }
    
    try {
      recordingStream = new RecordingStream();
      
      // Configure the VirtualThreadPinned event with stack trace capture
      recordingStream.enable(JFR_VIRTUAL_THREAD_PINNED_EVENT)
          .withStackTrace()
          .withThreshold(threshold);
      
      // Set up event handling
      recordingStream.onEvent(JFR_VIRTUAL_THREAD_PINNED_EVENT, this::handlePinningEvent);
      
      // Prevent memory leaks in long-running applications
      recordingStream.setMaxAge(DEFAULT_MAX_AGE);
      
      // Start the recording asynchronously
      recordingStream.startAsync();
      running = true;
      
      log.debug("Thread pinning detector started with threshold {}", threshold);
    }
    catch (Exception e) {
      log.error("Failed to start thread pinning detector", e);
    }
  }
  
  /**
   * Stops the detector.
   * 
   * This closes the JFR recording stream and stops monitoring for thread pinning events.
   */
  public void stop() {
    if (!running) {
      return;
    }
    
    try {
      if (recordingStream != null) {
        recordingStream.close();
        recordingStream = null;
      }
      running = false;
      log.debug("Thread pinning detector stopped");
    }
    catch (Exception e) {
      log.error("Error stopping thread pinning detector", e);
    }
  }
  
  /**
   * Adds a listener to be notified when thread pinning events are detected.
   * 
   * @param listener The listener to add
   */
  public void addPinningListener(final Consumer<ThreadPinningEvent> listener) {
    listeners.add(listener);
  }
  
  /**
   * Removes a previously added listener.
   * 
   * @param listener The listener to remove
   */
  public void removePinningListener(final Consumer<ThreadPinningEvent> listener) {
    listeners.remove(listener);
  }
  
  /**
   * Gets all thread pinning events detected so far.
   * 
   * @return An unmodifiable list of thread pinning events
   */
  public List<ThreadPinningEvent> getPinningEvents() {
    return Collections.unmodifiableList(new ArrayList<>(pinningEvents));
  }
  
  /**
   * Clears all detected thread pinning events.
   */
  public void clearEvents() {
    pinningEvents.clear();
  }
  
  /**
   * Handles a thread pinning event detected by JFR.
   * 
   * @param event The JFR recorded event
   */
  private void handlePinningEvent(final RecordedEvent event) {
    try {
      // Extract event details
      Instant timestamp = event.getStartTime();
      long durationMillis = event.getDuration().toMillis();
      String threadName = event.getThread().getJavaName();
      String reason = event.getString("reason");
      
      // Extract and format the stack trace
      String stackTrace = formatStackTrace(event.getStackTrace());
      
      // Create and record the pinning event
      ThreadPinningEvent pinningEvent = new ThreadPinningEvent(
          timestamp, durationMillis, threadName, stackTrace, reason);
      
      pinningEvents.add(pinningEvent);
      
      // Log the event
      log.debug("Thread pinning detected: {} ms in thread '{}' due to {}", 
          durationMillis, threadName, reason);
      
      // Notify listeners
      for (Consumer<ThreadPinningEvent> listener : listeners) {
        try {
          listener.accept(pinningEvent);
        }
        catch (Exception e) {
          log.error("Error in pinning event listener", e);
        }
      }
    }
    catch (Exception e) {
      log.error("Error processing thread pinning event", e);
    }
  }
  
  /**
   * Formats a JFR stack trace into a readable string.
   * 
   * @param stackTrace The JFR recorded stack trace
   * @return A formatted stack trace string
   */
  private String formatStackTrace(final RecordedStackTrace stackTrace) {
    if (stackTrace == null) {
      return "<No stack trace available>";
    }
    
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    
    for (RecordedFrame frame : stackTrace.getFrames()) {
      String className = frame.getType().getName();
      String methodName = frame.getMethod().getName();
      int lineNumber = frame.getLineNumber();
      
      pw.print(className);
      pw.print("#");
      pw.print(methodName);
      pw.print(": ");
      pw.println(lineNumber);
    }
    
    return sw.toString();
  }
  
  /**
   * Checks if the detector is currently running.
   * 
   * @return true if the detector is running, false otherwise
   */
  public boolean isRunning() {
    return running;
  }
}