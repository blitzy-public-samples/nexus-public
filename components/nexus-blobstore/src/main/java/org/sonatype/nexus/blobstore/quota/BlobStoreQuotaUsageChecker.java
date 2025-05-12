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
package org.sonatype.nexus.blobstore.quota;

import javax.inject.Inject;
import javax.inject.Named;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.common.scheduling.PeriodicJobService.PeriodicJob;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.createQuotaCheckJob;

/**
 * Manages periodic quota usage checks for blob stores using Virtual Threads.
 * 
 * This class leverages Java 21 Virtual Threads to efficiently perform I/O-bound quota check operations
 * without blocking platform threads, resulting in improved scalability and resource utilization.
 *
 * @since 3.41
 */
@Named
public class BlobStoreQuotaUsageChecker
    extends StateGuardLifecycleSupport
{
  protected final PeriodicJobService jobService;

  protected final int quotaCheckInterval;

  protected final BlobStoreQuotaService quotaService;
  
  /**
   * Virtual thread executor for running quota check operations.
   * Using virtual threads allows for high concurrency with minimal resource overhead,
   * particularly beneficial for I/O-bound operations like quota checks.
   */
  protected ExecutorService virtualThreadExecutor;

  protected BlobStore blobStore;

  protected PeriodicJob quotaCheckingJob;

  @Inject
  public BlobStoreQuotaUsageChecker(
      final PeriodicJobService jobService,
      @Named("${nexus.blobstore.quota.warnIntervalSeconds:-60}") final int quotaCheckInterval,
      final BlobStoreQuotaService quotaService)
  {
    this.jobService = checkNotNull(jobService);
    checkArgument(quotaCheckInterval > 0);
    this.quotaCheckInterval = quotaCheckInterval;
    this.quotaService = checkNotNull(quotaService);
  }

  @Override
  protected void doStart() throws Exception {
    // Create a virtual thread per task executor for quota check operations
    // This provides optimal performance for I/O-bound operations without consuming platform thread resources
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    jobService.startUsing();
    
    // Schedule the quota check job to run periodically
    // The actual quota check will be executed on a virtual thread
    quotaCheckingJob = jobService.schedule(() -> {
      try {
        // Submit the quota check job to the virtual thread executor
        // This ensures the job runs on a virtual thread, which is more efficient for I/O operations
        virtualThreadExecutor.submit(() -> {
          try {
            // Execute the quota check on the virtual thread
            BlobStoreQuotaResult result = quotaService.checkQuota(blobStore);
            if (result != null && result.isViolation()) {
              log.warn(result.getMessage());
            }
          }
          catch (Exception e) {
            // Enhanced error handling for virtual thread context
            // Don't propagate, as this stops subsequent executions
            String blobStoreName = "unknown";
            try {
              blobStoreName = blobStore.getBlobStoreConfiguration().getName();
            }
            catch (Exception ex) {
              // If we can't get the blob store name, just use the default
              log.debug("Could not get blob store name for error logging", ex);
            }
            log.error("Quota check exception for {}", blobStoreName, e);
          }
        });
      }
      catch (Exception e) {
        // Handle any errors that might occur when submitting to the virtual thread executor
        log.error("Failed to schedule quota check on virtual thread for {}", 
            blobStore.getBlobStoreConfiguration().getName(), e);
      }
    }, Duration.ofSeconds(quotaCheckInterval));
  }

  @Override
  protected void doStop() throws Exception {
    blobStore = null;
    
    // Cancel the periodic job
    if (quotaCheckingJob != null) {
      quotaCheckingJob.cancel();
      quotaCheckingJob = null;
    }
    
    // Shutdown the virtual thread executor
    // Virtual threads are lightweight, so this should complete quickly
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor = null;
    }
    
    jobService.stopUsing();
  }

  public void setBlobStore(final BlobStore blobStore) {
    checkState(this.blobStore == null, "Do not initialize twice");
    checkNotNull(blobStore);
    this.blobStore = blobStore;
  }
}