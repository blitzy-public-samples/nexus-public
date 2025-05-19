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

import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.common.scheduling.PeriodicJobService.PeriodicJob;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.createQuotaCheckJob;

/**
 * Manages periodic quota usage checks for a BlobStore using Virtual Threads for improved performance.
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
  
  protected final ReentrantReadWriteLock blobStoreLock = new ReentrantReadWriteLock();

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
    jobService.startUsing();
    
    // Create a wrapper that uses Virtual Threads for the quota check job
    Runnable quotaCheckJobWithVirtualThread = () -> {
      // Use Virtual Thread to execute the quota check job
      // This improves performance for I/O-bound operations and reduces resource consumption
      Thread.startVirtualThread(() -> {
        try {
          // Use String Templates for improved log message formatting
          log.debug(STR."Starting quota check for blob store: \{getBlobStoreName()}");
          
          // Get a read lock to ensure thread safety during quota check
          blobStoreLock.readLock().lock();
          try {
            if (blobStore != null) {
              // Execute the original quota check job
              // Use the Runnable directly to avoid nesting Virtual Threads
              Runnable quotaJob = createQuotaCheckJob(blobStore, quotaService, log);
              quotaJob.run();
            }
          }
          finally {
            blobStoreLock.readLock().unlock();
          }
        }
        catch (Exception e) {
          // Use String Templates for improved log message formatting
          log.error(STR."Error during quota check for blob store: \{getBlobStoreName()}", e);
        }
      });
    };
    
    // Schedule the Virtual Thread-based quota check job
    quotaCheckingJob = jobService.schedule(quotaCheckJobWithVirtualThread, quotaCheckInterval);
  }
  
  /**
   * Gets the name of the current blob store for logging purposes.
   * 
   * @return the name of the blob store or "<unknown>" if not available
   */
  private String getBlobStoreName() {
    if (blobStore != null && blobStore.getBlobStoreConfiguration() != null) {
      return blobStore.getBlobStoreConfiguration().getName();
    }
    return "<unknown>";
  }

  @Override
  protected void doStop() throws Exception {
    blobStoreLock.writeLock().lock();
    try {
      blobStore = null;
      if (quotaCheckingJob != null) {
        quotaCheckingJob.cancel();
        quotaCheckingJob = null;
      }
      jobService.stopUsing();
    }
    finally {
      blobStoreLock.writeLock().unlock();
    }
  }

  /**
   * Sets the BlobStore to be monitored for quota usage.
   * Thread-safe implementation to ensure concurrent access safety.
   *
   * @param blobStore the BlobStore to monitor
   */
  public void setBlobStore(final BlobStore blobStore) {
    checkNotNull(blobStore);
    
    blobStoreLock.writeLock().lock();
    try {
      checkState(this.blobStore == null, "Do not initialize twice");
      this.blobStore = blobStore;
    }
    finally {
      blobStoreLock.writeLock().unlock();
    }
  }
}