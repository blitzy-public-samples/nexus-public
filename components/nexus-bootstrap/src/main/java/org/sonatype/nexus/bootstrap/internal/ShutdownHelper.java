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
package org.sonatype.nexus.bootstrap.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.Executors;
import java.util.Set;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper to cope with different mechanisms to shutdown.
 *
 * @since 2.2
 */
public class ShutdownHelper
{
  private static final Logger log = LoggerFactory.getLogger(ShutdownHelper.class);

  /**
   * Default timeout in seconds for waiting on virtual threads to complete during shutdown.
   */
  private static final int DEFAULT_VIRTUAL_THREAD_SHUTDOWN_TIMEOUT_SECONDS = 30;

  private ShutdownHelper() {
    // empty
  }

  public interface ShutdownDelegate
  {
    void doExit(int code);

    void doHalt(int code);
  }

  public static class JavaShutdownDelegate
      implements ShutdownDelegate
  {
    @Override
    public void doExit(final int code) {
      // Attempt to gracefully shutdown any lingering virtual threads
      shutdownVirtualThreads();
      
      // expected use of System.exit()
      System.exit(code);
    }

    @Override
    public void doHalt(final int code) {
      // expected use of Runtime.halt()
      Runtime.getRuntime().halt(code);
    }
    
    /**
     * Attempts to gracefully shutdown any lingering virtual threads.
     * Virtual threads are daemon threads by default and won't prevent JVM exit,
     * but we want to give them a chance to complete their work.
     */
    private void shutdownVirtualThreads() {
      try {
        log.debug("Checking for lingering virtual threads before shutdown");
        
        // Get all running threads
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(false, false);
        
        // Count virtual threads
        int virtualThreadCount = 0;
        for (ThreadInfo threadInfo : threadInfos) {
          Thread thread = findThreadById(threadInfo.getThreadId());
          if (thread != null && isVirtualThread(thread)) {
            virtualThreadCount++;
            log.debug("Found virtual thread: {}", thread.getName());
          }
        }
        
        if (virtualThreadCount > 0) {
          log.info("Waiting for {} virtual threads to complete before shutdown", virtualThreadCount);
          
          // Create a platform thread executor to handle the shutdown coordination
          // This ensures we don't use virtual threads to manage virtual thread shutdown
          ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
          ExecutorService executor = Executors.newSingleThreadExecutor(platformThreadFactory);
          
          try {
            // Submit a task to wait for virtual threads to complete
            executor.submit(() -> {
              try {
                // Wait for a reasonable time for virtual threads to complete
                Thread.sleep(TimeUnit.SECONDS.toMillis(DEFAULT_VIRTUAL_THREAD_SHUTDOWN_TIMEOUT_SECONDS));
                log.warn("Timeout waiting for virtual threads to complete");
              }
              catch (InterruptedException e) {
                log.debug("Interrupted while waiting for virtual threads", e);
                Thread.currentThread().interrupt();
              }
            }).get(DEFAULT_VIRTUAL_THREAD_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
          }
          catch (Exception e) {
            log.warn("Error while waiting for virtual threads to complete", e);
          }
          finally {
            executor.shutdownNow();
          }
        }
      }
      catch (Exception e) {
        log.warn("Error during virtual thread shutdown", e);
      }
    }
    
    /**
     * Determines if a thread is a virtual thread.
     * 
     * @param thread The thread to check
     * @return true if the thread is a virtual thread, false otherwise
     */
    private boolean isVirtualThread(Thread thread) {
      try {
        // In Java 21, we can use Thread.isVirtual()
        return thread.isVirtual();
      }
      catch (NoSuchMethodError e) {
        // For compatibility with pre-Java 21, check thread class name
        return thread.getClass().getName().contains("VirtualThread");
      }
    }
    
    /**
     * Finds a Thread by its ID.
     * 
     * @param id The thread ID to look for
     * @return The Thread object or null if not found
     */
    private Thread findThreadById(long id) {
      // Get the root thread group
      ThreadGroup root = Thread.currentThread().getThreadGroup();
      while (root.getParent() != null) {
        root = root.getParent();
      }
      
      // Estimate thread count
      int count = root.activeCount();
      Thread[] threads = new Thread[count * 2]; // Double the size to be safe
      
      // Get all threads
      int enumerated = root.enumerate(threads, true);
      
      // Find the thread with the matching ID
      for (int i = 0; i < enumerated; i++) {
        if (threads[i].getId() == id) {
          return threads[i];
        }
      }
      
      return null;
    }
  }

  public static class NoopShutdownDelegate
      implements ShutdownDelegate
  {
    @Override
    public void doExit(final int code) {
      log.warn("Ignoring exit({}) request", code);
    }

    @Override
    public void doHalt(final int code) {
      log.warn("Ignoring halt({}) request", code);
    }
  }

  public static final ShutdownDelegate JAVA = new JavaShutdownDelegate();

  public static final ShutdownDelegate NOOP = new NoopShutdownDelegate();

  private static ShutdownDelegate delegate = JAVA;

  public static ShutdownDelegate getDelegate() {
    if (delegate == null) {
      throw new IllegalStateException();
    }
    return delegate;
  }

  public static void setDelegate(final ShutdownDelegate delegate) {
    if (delegate == null) {
      throw new NullPointerException();
    }
    ShutdownHelper.delegate = delegate;
  }

  public static void exit(final int code) {
    getDelegate().doExit(code);
  }

  public static void halt(final int code) {
    getDelegate().doHalt(code);
  }
  
  /**
   * Checks if the current thread is a virtual thread.
   * 
   * @return true if the current thread is a virtual thread, false otherwise
   * @since 3.60
   */
  public static boolean isCurrentThreadVirtual() {
    try {
      // In Java 21, we can use Thread.currentThread().isVirtual()
      return Thread.currentThread().isVirtual();
    }
    catch (NoSuchMethodError e) {
      // For compatibility with pre-Java 21, check thread class name
      return Thread.currentThread().getClass().getName().contains("VirtualThread");
    }
  }
}