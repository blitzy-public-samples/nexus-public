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
import java.util.concurrent.Executors;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating hashing input streams.
 * <p>
 * When parallel is enabled (default on), provides {@link ParallelMultiHashingInputStream} which uses
 * Java 21 Virtual Threads for I/O-bound operations. When disabled, provides a standard {@link MultiHashingInputStream}.
 * <p>
 * Virtual Threads are lightweight threads that dramatically reduce the effort of writing, maintaining, and observing
 * high-throughput concurrent applications, making them ideal for I/O-bound operations like hashing.
 */
public final class MultiHashingInputStreamFactory
{
  public static final Logger log = LoggerFactory.getLogger(MultiHashingInputStreamFactory.class);

  private static final String ENABLED_ENV_VAR = "NEXUS_HASHING_PARALLELISM";

  private static final String ENABLED_SYS_PROP = "nexus.hashing.parallism";

  private static final String THRESHOLD_ENV_VAR = "NEXUS_HASHING_THRESHOLD";

  private static final String THRESHOLD_SYS_PROP = "nexus.hashing.threshold";

  private static final String MAX_CONCURRENT_ENV_VAR = "NEXUS_HASHING_MAX_CONCURRENT";

  private static final String MAX_CONCURRENT_SYS_PROP = "nexus.hashing.max.concurrent";

  /*
   * Maximum number of concurrent hashing operations to allow
   * Default is -1 (unlimited) since Virtual Threads are designed to handle many concurrent operations
   */
  private static int maxConcurrent;

  /*
   * See belowThreshold()
   */
  private static int threshold;

  private static boolean enabled;

  /*
   * Tracks the current number of active parallel hashing operations
   */
  private static volatile int activeHashingOperations = 0;

  static {
    enabled = Boolean.valueOf(Optional.ofNullable(System.getenv(ENABLED_ENV_VAR))
        .orElseGet(() -> System.getProperty(ENABLED_SYS_PROP, Boolean.TRUE.toString())));

    threshold = Integer.valueOf(Optional.ofNullable(System.getenv(THRESHOLD_ENV_VAR))
        .orElseGet(() -> System.getProperty(THRESHOLD_SYS_PROP, "-1")));

    maxConcurrent = Integer.valueOf(Optional.ofNullable(System.getenv(MAX_CONCURRENT_ENV_VAR))
        .orElseGet(() -> System.getProperty(MAX_CONCURRENT_SYS_PROP, "-1")));

    if (!enabled || threshold != -1 || maxConcurrent != -1) {
      // log only for non-default settings
      log.info("Configured with enabled={} threshold={} maxConcurrent={}", enabled, threshold, maxConcurrent);
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
    log.info("Enabling parallel input stream hashing. Threshold {} maxConcurrent {}", threshold, maxConcurrent);

    enabled = true;
  }

  /*
   * Exists for use by Groovy scripting if necessary
   */
  @VisibleForTesting
  public static void disableParallel() {
    log.info("Disabling parallel input stream hashing");

    enabled = false;
  }

  /*
   * Exists for use by Groovy scripting if necessary
   */
  @VisibleForTesting
  public static void setThreshold(final int threshold) {
    log.info("Setting threshold to {}. Parallel input stream hashing enabled={}", threshold, enabled);

    MultiHashingInputStreamFactory.threshold = threshold;
  }

  /*
   * Exists for use by Groovy scripting if necessary
   */
  @VisibleForTesting
  public static void setMaxConcurrent(final int maxConcurrent) {
    log.info("Setting maxConcurrent to {}. Parallel input stream hashing enabled={}", maxConcurrent, enabled);

    MultiHashingInputStreamFactory.maxConcurrent = maxConcurrent;
  }

  /**
   * Increments the count of active hashing operations.
   */
  static synchronized void incrementActiveOperations() {
    activeHashingOperations++;
  }

  /**
   * Decrements the count of active hashing operations.
   */
  static synchronized void decrementActiveOperations() {
    if (activeHashingOperations > 0) {
      activeHashingOperations--;
    }
  }

  /**
   * Creates a new {@link MultiHashingInputStream} for the given algorithms and input stream.
   * <p>
   * If parallel hashing is enabled and system conditions allow, a {@link ParallelMultiHashingInputStream}
   * using Virtual Threads will be returned. Otherwise, a standard {@link MultiHashingInputStream} will be returned.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to hash
   * @return a new hashing input stream
   */
  public static MultiHashingInputStream input(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream) {
    if (enabled && belowThreshold()) {
      incrementActiveOperations();
      return new ParallelMultiHashingInputStream(algorithms, inputStream);
    }
    return new MultiHashingInputStream(algorithms, inputStream);
  }

  /**
   * Determines if we should use parallel hashing based on current system conditions.
   * <p>
   * With Virtual Threads, we can handle many more concurrent operations than with platform threads,
   * so the threshold logic is primarily focused on preventing excessive resource usage in extreme cases.
   *
   * @return true if parallel hashing should be used, false otherwise
   */
  private static boolean belowThreshold() {
    // Check if we're below the maximum concurrent operations threshold (if set)
    if (maxConcurrent > 0 && activeHashingOperations >= maxConcurrent) {
      if (log.isTraceEnabled()) {
        log.trace("Max concurrent operations reached: {} >= {}", activeHashingOperations, maxConcurrent);
      }
      return false;
    }

    // Check if we're below the system load threshold (if set)
    if (threshold > 0) {
      // For Java 21 Virtual Threads, we use system load average instead of ForkJoinPool queue size
      // as Virtual Threads are designed to handle many more concurrent operations efficiently
      double systemLoadAverage = getSystemLoadAverage();
      int availableProcessors = Runtime.getRuntime().availableProcessors();
      double normalizedLoad = systemLoadAverage / availableProcessors;
      boolean belowMax = normalizedLoad < threshold;

      if (log.isTraceEnabled()) {
        log.trace("Threshold {}. System load {}. Available processors {}. Normalized load {}. Below max {}", 
            threshold, systemLoadAverage, availableProcessors, normalizedLoad, belowMax);
      }

      return belowMax;
    }

    // If no thresholds are set or they're negative, always use parallel hashing
    return true;
  }

  /**
   * Gets the system load average, or 0.0 if not available.
   */
  private static double getSystemLoadAverage() {
    double systemLoadAverage = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    return systemLoadAverage >= 0 ? systemLoadAverage : 0.0;
  }
}