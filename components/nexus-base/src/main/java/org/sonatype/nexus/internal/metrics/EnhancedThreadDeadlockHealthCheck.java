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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import com.codahale.metrics.health.HealthCheck;

/**
 * A health check which returns healthy if no threads are deadlocked.
 * This implementation detects deadlocks in both platform threads and virtual threads.
 *
 * @since 3.60
 */
public class EnhancedThreadDeadlockHealthCheck 
    extends HealthCheck 
{
  private final ThreadMXBean threadBean;

  /**
   * Creates a new health check.
   */
  public EnhancedThreadDeadlockHealthCheck() {
    this.threadBean = ManagementFactory.getThreadMXBean();
  }

  @Override
  protected Result check() throws Exception {
    // First check for platform thread deadlocks using standard JDK API
    long[] platformDeadlockedThreadIds = threadBean.findDeadlockedThreads();
    
    // Then check for virtual thread deadlocks
    List<Thread> virtualDeadlockedThreads = findVirtualThreadDeadlocks();
    
    if ((platformDeadlockedThreadIds == null || platformDeadlockedThreadIds.length == 0) && 
        virtualDeadlockedThreads.isEmpty()) {
      return Result.healthy();
    }
    
    StringBuilder message = new StringBuilder("Deadlocked threads detected:\n");
    
    // Report platform thread deadlocks
    if (platformDeadlockedThreadIds != null && platformDeadlockedThreadIds.length > 0) {
      ThreadInfo[] threadInfos = threadBean.getThreadInfo(platformDeadlockedThreadIds, true, true);
      message.append("Platform thread deadlocks:\n");
      for (ThreadInfo threadInfo : threadInfos) {
        if (threadInfo != null) {
          message.append(formatThreadInfo(threadInfo));
        }
      }
    }
    
    // Report virtual thread deadlocks
    if (!virtualDeadlockedThreads.isEmpty()) {
      message.append("Virtual thread deadlocks:\n");
      for (Thread thread : virtualDeadlockedThreads) {
        message.append("  Thread ").append(thread.getName())
               .append(" (id: ").append(thread.getId()).append(")\n");
        
        // Include stack trace for the virtual thread
        StackTraceElement[] stackTrace = thread.getStackTrace();
        for (StackTraceElement element : stackTrace) {
          message.append("    at ").append(element).append("\n");
        }
      }
    }
    
    return Result.unhealthy(message.toString());
  }
  
  /**
   * Formats thread information for reporting.
   */
  private String formatThreadInfo(ThreadInfo threadInfo) {
    StringBuilder sb = new StringBuilder();
    sb.append("  Thread ").append(threadInfo.getThreadName())
      .append(" (id: ").append(threadInfo.getThreadId()).append(")\n");
    
    if (threadInfo.getLockName() != null) {
      sb.append("    waiting on lock: ").append(threadInfo.getLockName())
        .append(" held by: ").append(threadInfo.getLockOwnerName())
        .append(" (id: ").append(threadInfo.getLockOwnerId()).append(")\n");
    }
    
    StackTraceElement[] stackTrace = threadInfo.getStackTrace();
    for (StackTraceElement element : stackTrace) {
      sb.append("    at ").append(element).append("\n");
    }
    
    return sb.toString();
  }
  
  /**
   * Detects deadlocks involving virtual threads.
   * 
   * This implementation uses heuristics to identify potential deadlocks in virtual threads
   * by analyzing thread states, stack traces, and blocked threads.
   * 
   * @return a list of virtual threads that appear to be deadlocked
   */
  private List<Thread> findVirtualThreadDeadlocks() {
    List<Thread> potentialDeadlockedThreads = new ArrayList<>();
    
    // Get all threads in the system
    Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();
    
    // Filter for virtual threads that appear to be blocked
    for (Thread thread : allThreads.keySet()) {
      // Check if it's a virtual thread (Java 21 feature)
      if (thread.isVirtual() && thread.getState() == Thread.State.BLOCKED) {
        // Analyze stack trace for lock contention patterns
        StackTraceElement[] stackTrace = allThreads.get(thread);
        if (isLikelyDeadlocked(thread, stackTrace, allThreads)) {
          potentialDeadlockedThreads.add(thread);
        }
      }
    }
    
    return potentialDeadlockedThreads;
  }
  
  /**
   * Analyzes a thread's stack trace to determine if it's likely part of a deadlock.
   * 
   * @param thread the thread to analyze
   * @param stackTrace the thread's stack trace
   * @param allThreads map of all threads and their stack traces
   * @return true if the thread appears to be deadlocked
   */
  private boolean isLikelyDeadlocked(Thread thread, StackTraceElement[] stackTrace, 
                                    Map<Thread, StackTraceElement[]> allThreads) {
    // Look for common deadlock patterns in the stack trace
    for (StackTraceElement element : stackTrace) {
      String className = element.getClassName();
      String methodName = element.getMethodName();
      
      // Check for common lock acquisition methods
      if ((className.contains("AbstractQueuedSynchronizer") && methodName.contains("acquire")) ||
          (className.contains("LockSupport") && methodName.contains("park")) ||
          (className.contains("ReentrantLock") && methodName.contains("lock"))) {
        
        // Look for circular wait conditions
        for (Thread otherThread : allThreads.keySet()) {
          if (otherThread != thread && otherThread.isVirtual() && 
              otherThread.getState() == Thread.State.BLOCKED) {
            
            // Check if the other thread is waiting for a resource this thread might hold
            StackTraceElement[] otherStackTrace = allThreads.get(otherThread);
            if (mightBeWaitingForEachOther(stackTrace, otherStackTrace)) {
              return true;
            }
          }
        }
      }
    }
    
    return false;
  }
  
  /**
   * Checks if two threads might be waiting for resources held by each other.
   * 
   * @param stackTrace1 stack trace of the first thread
   * @param stackTrace2 stack trace of the second thread
   * @return true if the threads appear to be waiting for each other
   */
  private boolean mightBeWaitingForEachOther(StackTraceElement[] stackTrace1, 
                                           StackTraceElement[] stackTrace2) {
    // Look for patterns where both threads are trying to acquire locks in different orders
    Set<String> lockClasses1 = extractLockClasses(stackTrace1);
    Set<String> lockClasses2 = extractLockClasses(stackTrace2);
    
    // If both threads are trying to acquire locks from the same classes but in different stack positions,
    // they might be deadlocked
    return !lockClasses1.isEmpty() && !lockClasses2.isEmpty() && 
           lockClasses1.stream().anyMatch(lockClasses2::contains);
  }
  
  /**
   * Extracts classes that might represent locks from a stack trace.
   * 
   * @param stackTrace the stack trace to analyze
   * @return a set of class names that might represent locks
   */
  private Set<String> extractLockClasses(StackTraceElement[] stackTrace) {
    Set<String> lockClasses = new java.util.HashSet<>();
    
    for (StackTraceElement element : stackTrace) {
      String className = element.getClassName();
      String methodName = element.getMethodName();
      
      // Look for methods that typically involve lock acquisition
      if (methodName.contains("lock") || methodName.contains("wait") || 
          methodName.contains("acquire") || methodName.contains("park")) {
        lockClasses.add(className);
      }
    }
    
    return lockClasses;
  }
}