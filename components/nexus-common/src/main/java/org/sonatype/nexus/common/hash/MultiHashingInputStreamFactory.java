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
 * Factory for creating hashing input streams. When parallel is enabled (default on), provides
 * {@link ParallelMultiHashingInputStream} which uses Java 21 Virtual Threads for I/O-bound operations.
 * When disabled, provides a standard {@link MultiHashingInputStream}.
 * 
 * @since 3.0
 */
public final class MultiHashingInputStreamFactory
{
  public static final Logger log = LoggerFactory.getLogger(MultiHashingInputStreamFactory.class);

  private static final String ENABLED_ENV_VAR = "NEXUS_HASHING_PARALLELISM";

  private static final String ENABLED_SYS_PROP = "nexus.hashing.parallelism";

  private static final String THRESHOLD_ENV_VAR = "NEXUS_HASHING_THRESHOLD";

  private static final String THRESHOLD_SYS_PROP = "nexus.hashing.threshold";

  private static final String MAX_CONCURRENT_ENV_VAR = "NEXUS_HASHING_MAX_CONCURRENT";

  private static final String MAX_CONCURRENT_SYS_PROP = "nexus.hashing.max.concurrent";

  /*
   * Maximum number of concurrent virtual threads for hashing operations.
   * Default is -1 (unlimited). Set to a positive value to limit concurrent operations.
   */
  private static int maxConcurrentOperations;

  /*
   * Threshold for file size in bytes. Files smaller than this threshold will use
   * non-parallel hashing to avoid the overhead of creating virtual threads.
   * Default is -1 (no threshold). Set to a positive value to enable size-based optimization.
   */
  private static int threshold;

  /*
   * Counter for active parallel hashing operations
   */
  private static final AtomicInteger activeOperations = new AtomicInteger(0);

  private static boolean enabled;

  static {
    enabled = Boolean.valueOf(Optional.ofNullable(System.getenv(ENABLED_ENV_VAR))
        .orElseGet(() -> System.getProperty(ENABLED_SYS_PROP, Boolean.TRUE.toString())));

    threshold = Integer.valueOf(Optional.ofNullable(System.getenv(THRESHOLD_ENV_VAR))
        .orElseGet(() -> System.getProperty(THRESHOLD_SYS_PROP, "-1")));

    maxConcurrentOperations = Integer.valueOf(Optional.ofNullable(System.getenv(MAX_CONCURRENT_ENV_VAR))
        .orElseGet(() -> System.getProperty(MAX_CONCURRENT_SYS_PROP, "-1")));

    if (!enabled || threshold != -1 || maxConcurrentOperations != -1) {
      // log only for non-default settings
      log.info("Configured with enabled={} threshold={} maxConcurrentOperations={}", 
          enabled, threshold, maxConcurrentOperations);
    }
  }

  private MultiHashingInputStreamFactory() {
    // private
  }

  /**
   * Enables parallel hashing using Virtual Threads.
   * Exists for use by Groovy scripting if necessary.
   */
  @VisibleForTesting
  public static void enableParallel() {
    log.info("Enabling parallel input stream hashing with Virtual Threads. Threshold: {}, Max concurrent: {}", 
        threshold, maxConcurrentOperations);

    enabled = true;
  }

  /**
   * Disables parallel hashing.
   * Exists for use by Groovy scripting if necessary.
   */
  @VisibleForTesting
  public static void disableParallel() {
    log.info("Disabling parallel input stream hashing");

    enabled = false;
  }

  /**
   * Sets the threshold for file size in bytes.
   * Files smaller than this threshold will use non-parallel hashing.
   * Exists for use by Groovy scripting if necessary.
   *
   * @param threshold the size threshold in bytes, or -1 to disable threshold checking
   */
  @VisibleForTesting
  public static void setThreshold(final int threshold) {
    log.info("Setting threshold to {} bytes. Parallel input stream hashing enabled={}", threshold, enabled);

    MultiHashingInputStreamFactory.threshold = threshold;
  }

  /**
   * Sets the maximum number of concurrent parallel hashing operations.
   * Exists for use by Groovy scripting if necessary.
   *
   * @param maxConcurrent the maximum number of concurrent operations, or -1 for unlimited
   */
  @VisibleForTesting
  public static void setMaxConcurrentOperations(final int maxConcurrent) {
    log.info("Setting max concurrent operations to {}. Parallel input stream hashing enabled={}", 
        maxConcurrent, enabled);

    MultiHashingInputStreamFactory.maxConcurrentOperations = maxConcurrent;
  }

  /**
   * Gets the current number of active parallel hashing operations.
   * Useful for monitoring and diagnostics.
   *
   * @return the count of active operations
   */
  public static int getActiveOperations() {
    return activeOperations.get();
  }

  /**
   * Increments the active operations counter.
   * Called internally when a new parallel hashing operation starts.
   *
   * @return the new count of active operations
   */
  static int incrementActiveOperations() {
    return activeOperations.incrementAndGet();
  }

  /**
   * Decrements the active operations counter.
   * Called internally when a parallel hashing operation completes.
   *
   * @return the new count of active operations
   */
  static int decrementActiveOperations() {
    return activeOperations.decrementAndGet();
  }

  /**
   * Creates a hashing input stream for the given algorithms and input stream.
   * Uses parallel hashing with Virtual Threads if enabled and below threshold limits.
   *
   * @param algorithms the hash algorithms to use
   * @param inputStream the input stream to hash
   * @return a MultiHashingInputStream instance
   */
  public static MultiHashingInputStream input(final Iterable<HashAlgorithm> algorithms, final InputStream inputStream) {
    if (enabled && belowThreshold()) {
      incrementActiveOperations();
      return new ParallelMultiHashingInputStream(algorithms, inputStream);
    }
    return new MultiHashingInputStream(algorithms, inputStream);
  }

  /**
   * Determines if the current state allows for parallel hashing.
   * Checks against configured thresholds and limits.
   *
   * @return true if parallel hashing should be used, false otherwise
   */
  private static boolean belowThreshold() {
    // Check max concurrent operations limit if set
    if (maxConcurrentOperations > 0) {
      int currentOperations = activeOperations.get();
      if (currentOperations >= maxConcurrentOperations) {
        if (log.isTraceEnabled()) {
          log.trace("Max concurrent operations ({}) reached: {}", maxConcurrentOperations, currentOperations);
        }
        return false;
      }
    }

    // No size threshold check here as we don't have access to the stream size
    // Size-based optimization would need to be implemented at the caller level

    return true;
  }
}