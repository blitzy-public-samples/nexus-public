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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport;
import org.sonatype.nexus.rest.ValidationErrorsException;

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
  public static final String ID = "spaceUsedQuota";

  private static final String DISPLAY_NAME = "Space Used";
  
  // Timeout for storage size calculation operations (in milliseconds)
  private static final long STORAGE_SIZE_CALCULATION_TIMEOUT_MS = 30_000;

  @Override
  public void validateConfig(final BlobStoreConfiguration config) {
    if (getLimit(config) <= 0) {
      throw new ValidationErrorsException(DISPLAY_NAME + " quotas must have a Quota Limit greater than 0");
    }
  }

  @Override
  public BlobStoreQuotaResult check(final BlobStore blobStore) {
    checkNotNull(blobStore);

    // Use Virtual Threads for storage size calculation
    long usedSpace = calculateStorageSizeWithVirtualThread(blobStore);
    long limit = getLimit(blobStore.getBlobStoreConfiguration());

    String name = blobStore.getBlobStoreConfiguration().getName();
    String msg = format("Blob store %s is using %s space and has a limit of %s", name,
        formatStorage(usedSpace),
        formatStorage(limit));

    return new BlobStoreQuotaResult(usedSpace > limit, name, msg);
  }

  /**
   * Calculates the storage size using a Virtual Thread for optimized I/O throughput.
   * Virtual Threads are lightweight threads that are managed by the JVM rather than the OS,
   * making them ideal for I/O-bound operations like storage size calculation.
   *
   * @param blobStore the blob store to calculate storage size for
   * @return the total size of the blob store in bytes
   */
  private long calculateStorageSizeWithVirtualThread(final BlobStore blobStore) {
    // Create an AtomicReference to store the result in a thread-safe manner
    AtomicReference<Long> sizeRef = new AtomicReference<>(0L);
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    
    // Use try-with-resources to ensure the executor is properly closed
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      log.debug("Starting Virtual Thread for storage size calculation on blob store: {}", 
          blobStore.getBlobStoreConfiguration().getName());
      
      // Submit the storage size calculation task to the Virtual Thread executor
      Future<?> future = executor.submit(() -> {
        try {
          // Get the metrics from the blob store
          BlobStoreMetrics metrics = blobStore.getMetrics();
          // Store the total size in the AtomicReference
          sizeRef.set(metrics.getTotalSize());
          
          log.debug("Virtual Thread completed storage size calculation for blob store: {}, size: {}", 
              blobStore.getBlobStoreConfiguration().getName(), formatStorage(sizeRef.get()));
        } 
        catch (Exception e) {
          // Store any exception that occurs during calculation
          exceptionRef.set(e);
          log.error("Error in Virtual Thread during storage size calculation for blob store: {}", 
              blobStore.getBlobStoreConfiguration().getName(), e);
        }
      });
      
      // Wait for the calculation to complete with a timeout
      try {
        future.get(STORAGE_SIZE_CALCULATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } 
      catch (InterruptedException e) {
        // Restore the interrupted status
        Thread.currentThread().interrupt();
        log.warn("Virtual Thread was interrupted during storage size calculation for blob store: {}", 
            blobStore.getBlobStoreConfiguration().getName(), e);
      } 
      catch (ExecutionException e) {
        log.error("Virtual Thread execution failed during storage size calculation for blob store: {}", 
            blobStore.getBlobStoreConfiguration().getName(), e);
      } 
      catch (TimeoutException e) {
        log.warn("Virtual Thread timed out after {}ms during storage size calculation for blob store: {}", 
            STORAGE_SIZE_CALCULATION_TIMEOUT_MS, blobStore.getBlobStoreConfiguration().getName(), e);
        // Cancel the task if it times out
        future.cancel(true);
      }
    }
    
    // Check if an exception occurred during calculation
    if (exceptionRef.get() != null) {
      log.error("Using fallback method for storage size calculation due to error in Virtual Thread for blob store: {}", 
          blobStore.getBlobStoreConfiguration().getName(), exceptionRef.get());
      
      // Fallback to direct calculation if Virtual Thread execution failed
      return blobStore.getMetrics().getTotalSize();
    }
    
    return sizeRef.get();
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