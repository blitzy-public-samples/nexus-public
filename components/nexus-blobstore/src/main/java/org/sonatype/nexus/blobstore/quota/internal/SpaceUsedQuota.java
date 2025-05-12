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
package org.sonatype.nexus.blobstore.quota.internal;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.text.UnitFormatter.formatStorage;
import static java.lang.String.format;

/**
 * A {@link BlobStoreQuota} which checks that a blob store isn't using more space the limit.
 *
 * @since 3.14
 */
@Named(SpaceUsedQuota.ID)
@Singleton
public class SpaceUsedQuota
    extends BlobStoreQuotaSupport
{
  private static final Logger log = LoggerFactory.getLogger(SpaceUsedQuota.class);
  
  public static final String ID = "spaceUsedQuota";

  private static final String DISPLAY_NAME = "Space Used";
  
  // Default timeout for storage size calculation (in seconds)
  private static final long DEFAULT_TIMEOUT_SECONDS = 60;

  @Override
  public void validateConfig(final BlobStoreConfiguration config) {
    if (getLimit(config) <= 0) {
      throw new ValidationErrorsException(DISPLAY_NAME + " quotas must have a Quota Limit greater than 0");
    }
  }

  @Override
  public BlobStoreQuotaResult check(final BlobStore blobStore) {
    checkNotNull(blobStore);
    
    String blobStoreName = blobStore.getBlobStoreConfiguration().getName();
    long limit = getLimit(blobStore.getBlobStoreConfiguration());
    long startTime = System.currentTimeMillis();
    
    log.debug("Starting storage size calculation for blob store {} using Virtual Thread", blobStoreName);
    
    try {
      // Create a virtual thread to perform the storage size calculation
      // This leverages Java 21's Virtual Threads for optimized I/O operations
      long usedSpace = calculateStorageSizeWithVirtualThread(blobStore);
      
      long elapsedTime = System.currentTimeMillis() - startTime;
      log.debug("Storage size calculation for blob store {} completed in {}ms: {} used of {} limit", 
          blobStoreName, elapsedTime, formatStorage(usedSpace), formatStorage(limit));
      
      String msg = format("Blob store %s is using %s space and has a limit of %s", blobStoreName,
          formatStorage(usedSpace),
          formatStorage(limit));

      return new BlobStoreQuotaResult(usedSpace > limit, blobStoreName, msg);
    } 
    catch (Exception e) {
      log.error("Error calculating storage size for blob store {} using Virtual Thread", blobStoreName, e);
      throw new RuntimeException("Failed to calculate storage size for blob store: " + blobStoreName, e);
    }
  }
  
  /**
   * Calculates the storage size using a Virtual Thread for optimized I/O throughput.
   * Virtual Threads are lightweight threads that are managed by the JVM rather than the OS,
   * making them ideal for I/O-bound operations like storage calculations.
   *
   * @param blobStore the blob store to calculate size for
   * @return the total size in bytes
   * @throws ExecutionException if the calculation fails
   * @throws InterruptedException if the thread is interrupted
   * @throws TimeoutException if the calculation times out
   */
  private long calculateStorageSizeWithVirtualThread(final BlobStore blobStore) 
      throws ExecutionException, InterruptedException, TimeoutException {
    
    // Create a thread factory that produces virtual threads
    // Virtual threads are lightweight and managed by the JVM, making them ideal for I/O operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      
      // Submit the storage calculation task to run on a virtual thread
      Future<Long> future = executor.submit(() -> {
        log.debug("Virtual Thread started for blob store {}", blobStore.getBlobStoreConfiguration().getName());
        try {
          // Get the metrics which includes the total size calculation
          // This is likely an I/O-bound operation that benefits from Virtual Threads
          BlobStoreMetrics metrics = blobStore.getMetrics();
          return metrics.getTotalSize();
        }
        catch (Exception e) {
          log.error("Exception in Virtual Thread while calculating storage size", e);
          throw e;
        }
        finally {
          log.debug("Virtual Thread completed for blob store {}", blobStore.getBlobStoreConfiguration().getName());
        }
      });
      
      // Wait for the result with a timeout to prevent hanging indefinitely
      return future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  @Override
  public String getDisplayName() {
    return DISPLAY_NAME;
  }

  @Override
  public String getId() {
    return ID;
  }
}