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
package org.sonatype.nexus.common.hash;

import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * When virtual thread hashing is enabled (default on), provides
 * {@link VirtualThreadMultiHashingInputStream} for parallel hashing operations.
 * When disabled, falls back to {@link MultiHashingInputStream}.
 * 
 * @since 3.60
 */
public final class MultiHashingInputStreamFactory
{
  public static final Logger log = LoggerFactory.getLogger(MultiHashingInputStreamFactory.class);

  private static final String ENABLED_ENV_VAR = "NEXUS_VIRTUAL_THREAD_HASHING";

  private static final String ENABLED_SYS_PROP = "nexus.virtualthread.hashing";

  private static final String THRESHOLD_ENV_VAR = "NEXUS_VIRTUAL_THREAD_THRESHOLD";

  private static final String THRESHOLD_SYS_PROP = "nexus.virtualthread.threshold";

  /*
   * See belowThreshold()
   */
  private static int threshold;

  private static boolean enabled;

  /**
   * Check if the current Java runtime supports Virtual Threads.
   * This is available in Java 21 and later.
   *
   * @return true if Virtual Threads are supported
   */
  private static boolean isVirtualThreadSupported() {
    try {
      // Check if the Thread class has the isVirtual method (Java 21+)
      Thread.class.getMethod("isVirtual");
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }
  
  static {
    boolean virtualThreadsSupported = isVirtualThreadSupported();
    
    enabled = virtualThreadsSupported && Boolean.valueOf(Optional.ofNullable(System.getenv(ENABLED_ENV_VAR))
        .orElseGet(() -> System.getProperty(ENABLED_SYS_PROP, Boolean.TRUE.toString())));

    threshold = Integer.valueOf(Optional.ofNullable(System.getenv(THRESHOLD_ENV_VAR))
        .orElseGet(() -> System.getProperty(THRESHOLD_SYS_PROP, "-1")));

    // Log configuration status with String Templates for improved observability
    if (!virtualThreadsSupported) {
      log.info("Virtual Threads not supported in this Java version. Using standard MultiHashingInputStream.");
    } else if (!enabled || threshold != -1) {
      // Log non-default settings
      log.info(STR."Configured with enabled=\{enabled} threshold=\{threshold} (using Virtual Threads)");
    }
  }

  private MultiHashingInputStreamFactory() {
    // private
  }

  /*
   * Exists for use by Groovy scripting if necessary
   */
  @VisibleForTesting
  public static void enableParallel() {
    log.info(STR."Enabling virtual thread input stream hashing. Threshold \{threshold}");

    enabled = true;
  }

  /*
   * Exists for use by Groovy scripting if necessary
   */
  @VisibleForTesting
  public static void disableParallel() {
    log.info("Disabling virtual thread input stream hashing");

    enabled = false;
  }

  /*
   * Exists for use by Groovy scripting if necessary
   */
  @VisibleForTesting
  public static void setThreshold(final int threshold) {
    log.info(STR."Setting threshold to \{threshold}. Virtual thread input stream hashing enabled=\{enabled}");

    MultiHashingInputStreamFactory.threshold = threshold;
  }

  /**
   * Creates a MultiHashingInputStream for the given algorithms and input stream.
   * If virtual thread hashing is enabled and we're below the threshold, a VirtualThreadMultiHashingInputStream
   * will be created. Otherwise, a standard MultiHashingInputStream will be used.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to hash
   * @return a MultiHashingInputStream instance
   */
  public static MultiHashingInputStream input(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream) {
    if (enabled && belowThreshold()) {
      if (log.isDebugEnabled()) {
        log.debug(STR."Creating VirtualThreadMultiHashingInputStream (active: \{getActiveThreadCount()})");
      }
      return new VirtualThreadMultiHashingInputStream(algorithms, inputStream);
    }
    
    if (log.isTraceEnabled()) {
      String reason = !enabled ? "virtual thread hashing disabled" : "above threshold";
      log.trace(STR."Creating standard MultiHashingInputStream (\{reason})");
    }
    return new MultiHashingInputStream(algorithms, inputStream);
  }

  /*
   * For Virtual Threads, the threshold has a different meaning than with ForkJoinPool.
   * Since Virtual Threads are lightweight and managed by the JVM, we use the threshold
   * to limit the number of concurrent hashing operations based on system load or other factors.
   * 
   * Values below zero disable limits, allowing unlimited virtual threads for hashing.
   */
  // Counter to track active virtual thread hashing operations
  private static final AtomicInteger activeVirtualThreads = new AtomicInteger(0);
  
  /**
   * Increment the count of active virtual thread hashing operations.
   */
  static void incrementActiveThreads() {
    activeVirtualThreads.incrementAndGet();
  }
  
  /**
   * Decrement the count of active virtual thread hashing operations.
   */
  static void decrementActiveThreads() {
    activeVirtualThreads.decrementAndGet();
  }
  
  /**
   * Get the current count of active virtual thread hashing operations.
   */
  static int getActiveThreadCount() {
    return activeVirtualThreads.get();
  }
  
  /**
   * Determines if we should use virtual threads for hashing based on the configured threshold.
   * For Virtual Threads, the threshold represents the maximum number of concurrent hashing
   * operations allowed.
   * 
   * @return true if we should use virtual threads, false otherwise
   */
  private static boolean belowThreshold() {
    if (threshold < 0) {
      // negative values disable limits
      return true;
    }

    // For Virtual Threads, we check if we're below the configured threshold of concurrent operations
    int active = activeVirtualThreads.get();
    boolean belowMax = active < threshold;

    if (log.isTraceEnabled()) {
      Thread currentThread = Thread.currentThread();
      boolean isVirtual = currentThread.isVirtual();
      log.trace(STR."Threshold: \{threshold}. Active virtual threads: \{active}. Current thread is virtual: \{isVirtual}. Below max: \{belowMax}");
    }

    return belowMax;
  }
}