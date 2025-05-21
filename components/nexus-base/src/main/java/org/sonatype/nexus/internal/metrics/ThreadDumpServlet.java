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
package org.sonatype.nexus.internal.metrics;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.Thread.State;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static com.google.common.net.HttpHeaders.CONTENT_DISPOSITION;

/**
 * Enhanced {@link com.codahale.metrics.servlets.ThreadDumpServlet} to support Java 21 Virtual Threads.
 * Provides thread dump information with additional details for virtual threads, including pinning detection.
 *
 * @since 3.0
 */
public class ThreadDumpServlet
    extends HttpServlet
{
  private static final String THREAD_TYPE_PARAM = "type";
  private static final String THREAD_TYPE_ALL = "all";
  private static final String THREAD_TYPE_PLATFORM = "platform";
  private static final String THREAD_TYPE_VIRTUAL = "virtual";

  @Override
  protected void doGet(
      final HttpServletRequest req,
      final HttpServletResponse resp) throws ServletException, IOException
  {
    boolean download = Boolean.parseBoolean(req.getParameter("download"));
    if (download) {
      resp.addHeader(CONTENT_DISPOSITION, "attachment; filename='threads.txt'");
    }

    resp.setContentType("text/plain");
    resp.setHeader("Cache-Control", "must-revalidate,no-cache,no-store");

    final PrintWriter writer = resp.getWriter();
    try {
      writeThreadDump(writer, req.getParameter(THREAD_TYPE_PARAM));
    } catch (Exception e) {
      resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      writer.println("Error generating thread dump: " + e.getMessage());
      e.printStackTrace(writer);
    }
  }

  /**
   * Writes a thread dump to the given {@link PrintWriter}.
   *
   * @param writer the destination for the thread dump
   * @param threadType filter threads by type: "platform", "virtual", or "all" (default)
   */
  private void writeThreadDump(PrintWriter writer, String threadType) {
    if (threadType == null || threadType.isEmpty()) {
      threadType = THREAD_TYPE_ALL;
    }

    writer.println("Thread dump generated at " + new java.util.Date());
    writer.println();

    // Get all thread stack traces
    Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    
    // Get thread management bean for additional thread info
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    
    // Print summary counts
    int platformThreadCount = 0;
    int virtualThreadCount = 0;
    int pinnedVirtualThreadCount = 0;
    
    for (Thread thread : stackTraces.keySet()) {
      if (isVirtualThread(thread)) {
        virtualThreadCount++;
        if (isPinnedVirtualThread(thread)) {
          pinnedVirtualThreadCount++;
        }
      } else {
        platformThreadCount++;
      }
    }
    
    writer.println("Total threads: " + stackTraces.size());
    writer.println("Platform threads: " + platformThreadCount);
    writer.println("Virtual threads: " + virtualThreadCount);
    writer.println("Pinned virtual threads: " + pinnedVirtualThreadCount);
    writer.println();

    // Print thread details
    for (Entry<Thread, StackTraceElement[]> entry : stackTraces.entrySet()) {
      Thread thread = entry.getKey();
      StackTraceElement[] stack = entry.getValue();
      
      boolean isVirtual = isVirtualThread(thread);
      boolean isPinned = isVirtual && isPinnedVirtualThread(thread);
      
      // Apply thread type filter
      if (THREAD_TYPE_PLATFORM.equals(threadType) && isVirtual) {
        continue;
      }
      if (THREAD_TYPE_VIRTUAL.equals(threadType) && !isVirtual) {
        continue;
      }
      
      // Print thread information
      printThreadInfo(writer, thread, stack, threadMXBean, isVirtual, isPinned);
    }
  }

  /**
   * Prints detailed information about a thread.
   */
  private void printThreadInfo(PrintWriter writer, Thread thread, StackTraceElement[] stack, 
                              ThreadMXBean threadMXBean, boolean isVirtual, boolean isPinned) {
    // Basic thread info
    writer.println(getThreadHeader(thread, isVirtual, isPinned));
    
    // Get additional thread info if available (not available for virtual threads)
    if (!isVirtual) {
      ThreadInfo threadInfo = threadMXBean.getThreadInfo(thread.getId(), Integer.MAX_VALUE);
      if (threadInfo != null) {
        printThreadDetails(writer, threadInfo);
      }
    }
    
    // Print stack trace
    for (StackTraceElement element : stack) {
      writer.println("    at " + element);
    }
    
    writer.println();
  }

  /**
   * Creates a header string for thread information.
   */
  private String getThreadHeader(Thread thread, boolean isVirtual, boolean isPinned) {
    StringBuilder sb = new StringBuilder();
    sb.append('"').append(thread.getName()).append('"');
    
    if (isVirtual) {
      sb.append(" Virtual Thread");
      if (isPinned) {
        sb.append(" [PINNED]");
      }
    } else {
      sb.append(" Platform Thread");
      sb.append(" #").append(thread.getId());
      
      ThreadGroup group = thread.getThreadGroup();
      if (group != null) {
        sb.append(" in group \"").append(group.getName()).append('"');
      }
    }
    
    sb.append(" priority=").append(thread.getPriority());
    sb.append(" state=").append(thread.getState());
    
    return sb.toString();
  }

  /**
   * Prints detailed information from ThreadInfo.
   */
  private void printThreadDetails(PrintWriter writer, ThreadInfo threadInfo) {
    if (threadInfo.getLockName() != null) {
      writer.println("    - waiting on " + threadInfo.getLockName());
    }
    
    if (threadInfo.getLockOwnerName() != null) {
      writer.println("    - lock held by \"" + threadInfo.getLockOwnerName() + 
                     "\" id=" + threadInfo.getLockOwnerId());
    }
    
    MonitorInfo[] monitors = threadInfo.getLockedMonitors();
    if (monitors != null && monitors.length > 0) {
      writer.println("    - locked monitors: " + monitors.length);
      for (MonitorInfo monitor : monitors) {
        writer.println("      - " + monitor.getClassName() + "@" + 
                       Integer.toHexString(monitor.getIdentityHashCode()) + 
                       " at " + monitor.getLockedStackFrame());
      }
    }
    
    LockInfo[] locks = threadInfo.getLockedSynchronizers();
    if (locks != null && locks.length > 0) {
      writer.println("    - locked synchronizers: " + locks.length);
      for (LockInfo lock : locks) {
        writer.println("      - " + lock.getClassName() + "@" + 
                       Integer.toHexString(lock.getIdentityHashCode()));
      }
    }
  }

  /**
   * Determines if a thread is a virtual thread.
   * Uses reflection to check for isVirtual() method introduced in Java 21.
   */
  private boolean isVirtualThread(Thread thread) {
    try {
      // Use reflection to call Thread.isVirtual() method (Java 21+)
      return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
    } catch (Exception e) {
      // If method doesn't exist (pre-Java 21), it's not a virtual thread
      return false;
    }
  }

  /**
   * Detects if a virtual thread is pinned to its carrier thread.
   * This is a heuristic approach as there's no direct API to check for pinning.
   * 
   * A virtual thread can be pinned when:
   * 1. It's running code inside a synchronized block/method
   * 2. It's running a native method or foreign function
   */
  private boolean isPinnedVirtualThread(Thread thread) {
    if (!isVirtualThread(thread)) {
      return false;
    }
    
    // Check stack trace for indicators of pinning
    StackTraceElement[] stack = thread.getStackTrace();
    for (StackTraceElement element : stack) {
      // Look for JDK internal classes that indicate pinning
      String className = element.getClassName();
      String methodName = element.getMethodName();
      
      // Check for pinning indicators in stack trace
      if (className.equals("java.lang.VirtualThread") && 
          (methodName.equals("parkOnCarrierThread") || methodName.equals("enterBlockingCriticalSection"))) {
        return true;
      }
      
      // Check for synchronized method indicators
      if (methodName.contains("$synchronized")) {
        return true;
      }
    }
    
    return false;
  }
}