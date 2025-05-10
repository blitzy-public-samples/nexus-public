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
package org.sonatype.nexus.blobstore.metrics;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.sonatype.nexus.blobstore.AccumulatingBlobStoreMetrics;
import org.sonatype.nexus.blobstore.BlobStoreMetricsNotAvailableException;
import org.sonatype.nexus.blobstore.UnavailableBlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.OperationMetrics;
import org.sonatype.nexus.blobstore.api.OperationType;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsPropertiesReader;
import org.sonatype.nexus.common.property.ImplicitSourcePropertiesFile;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import com.google.common.annotations.VisibleForTesting;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Long.parseLong;
import static java.util.stream.Stream.iterate;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.NEW;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;

/**
 * @deprecated legacy method for metrics stored in the blob store, only used for migrating data to the db
 */
@Deprecated
public abstract class BlobStoreMetricsPropertiesReaderSupport<B extends BlobStore, T extends ImplicitSourcePropertiesFile>
    extends StateGuardLifecycleSupport
    implements BlobStoreMetricsPropertiesReader<B>
{
  // Base delay for exponential backoff strategy
  private static final int BASE_DELAY_MILLIS = 50;
  
  // Maximum delay for exponential backoff strategy
  private static final int MAX_DELAY_MILLIS = 1000;
  
  // Jitter factor for randomizing delays (0.0-1.0)
  private static final double JITTER_FACTOR = 0.2;

  // Random number generator for jitter
  private static final Random RANDOM = new Random();

  public static final int MAXIMUM_TRIES = 3;

  public static final String METRICS_FILENAME = "metrics.properties";

  @VisibleForTesting
  public static final String TOTAL_SIZE_PROP_NAME = "totalSize";

  @VisibleForTesting
  public static final String BLOB_COUNT_PROP_NAME = "blobCount";

  protected B blobStore;

  private Map<OperationType, OperationMetrics> operationMetrics;

  @Override
  @Guarded(by = NEW)
  public final void init(final B blobStore) throws Exception {
    checkState(this.blobStore == null, "Do not initialize twice");

    this.blobStore = checkNotNull(blobStore);
    doInit(blobStore);

    this.start();
  }

  /**
   * Called during {@link init} for subclasses to derive necessary values from the configured blobstore
   */
  protected abstract void doInit(B blobstore) throws Exception;

  @Override
  protected void doStart() throws Exception {
    operationMetrics = new EnumMap<>(OperationType.class);
    for (OperationType type : OperationType.values()) {
      operationMetrics.put(type, new OperationMetrics());
    }
  }

  @Override
  protected void doStop() throws Exception {
    blobStore = null;

    operationMetrics.clear();
  }

  protected abstract AccumulatingBlobStoreMetrics getAccumulatingBlobStoreMetrics() throws BlobStoreMetricsNotAvailableException;

  protected abstract Stream<T> backingFiles() throws BlobStoreMetricsNotAvailableException;

  /**
   * Calculates the delay for a retry attempt using exponential backoff with jitter.
   * This approach is optimized for virtual threads by providing better distribution of retries.
   *
   * @param attempt The current retry attempt (1-based)
   * @return The delay duration in milliseconds
   */
  private long calculateBackoffDelayMillis(final int attempt) {
    // Calculate exponential backoff: baseDelay * 2^(attempt-1)
    long exponentialDelay = BASE_DELAY_MILLIS * (1L << (attempt - 1));
    
    // Cap the delay at the maximum
    long cappedDelay = Math.min(exponentialDelay, MAX_DELAY_MILLIS);
    
    // Apply jitter: delay = delay * (1 ± jitterFactor)
    double jitter = 1.0 + JITTER_FACTOR * (RANDOM.nextDouble() * 2 - 1);
    
    return Math.round(cappedDelay * jitter);
  }

  protected BlobStoreMetrics getCombinedMetrics(
      final Stream<T> blobStoreMetricsFiles) throws BlobStoreMetricsNotAvailableException
  {
    AccumulatingBlobStoreMetrics blobStoreMetrics = getAccumulatingBlobStoreMetrics();
    blobStoreMetricsFiles.forEach(metricsFile -> {
      iterate(1, i -> i + 1)
          .limit(MAXIMUM_TRIES)
          .forEach(currentTry -> {
            try {
              metricsFile.load();
            }
            catch (IOException e) {
              log.debug("Unable to load properties file {}. Try number {} of {}.", metricsFile, currentTry,
                  MAXIMUM_TRIES, e);
              if (currentTry >= MAXIMUM_TRIES) {
                throw new RuntimeException("Failed to load blob store metrics from " + metricsFile, e);
              }
              try {
                // Use Thread.sleep instead of TimeUnit.MILLISECONDS.sleep for better virtual thread compatibility
                // Apply exponential backoff with jitter for better distribution of retries
                Thread.sleep(calculateBackoffDelayMillis(currentTry));
              }
              catch (InterruptedException e1) {
                log.warn("Interrupted while waiting to retry loading properties file", e1);
                // Preserve interrupt status for proper virtual thread handling
                Thread.currentThread().interrupt();
                // Break out of the retry loop when interrupted
                return;
              }
            }
          });

      blobStoreMetrics.addBlobCount(parseLong(metricsFile.getProperty(BLOB_COUNT_PROP_NAME, "0")));
      blobStoreMetrics.addTotalSize(parseLong(metricsFile.getProperty(TOTAL_SIZE_PROP_NAME, "0")));
    });
    return blobStoreMetrics;
  }

  @Override
  @Guarded(by = STARTED)
  public BlobStoreMetrics getMetrics() {
    try {
      return getCombinedMetrics(backingFiles());
    }
    catch (BlobStoreMetricsNotAvailableException e) {
      log.error("Blob store metrics cannot be accessed", e);
      return UnavailableBlobStoreMetrics.getInstance();
    }
  }

  @Override
  public Map<OperationType, OperationMetrics> getOperationMetrics() {
    return operationMetrics;
  }

}