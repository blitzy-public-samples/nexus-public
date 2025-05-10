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

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.common.scheduling.PeriodicJobService.PeriodicJob;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.thread.internal.MDCAwareRunnable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.createQuotaCheckJob;

/**
 * BlobStore quota usage checker that leverages Java 21 Virtual Threads for improved performance
 * and resource utilization when performing periodic quota checks.
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

  protected BlobStore blobStore;

  protected PeriodicJob quotaCheckingJob;

  /**
   * ExecutorService that creates virtual threads for quota check operations.
   * Virtual threads are lightweight and efficient for I/O-bound operations like quota checks.
   */
  protected ExecutorService virtualThreadExecutor;

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
    // Create a virtual thread executor for quota check operations
    // Virtual threads are lightweight and efficient for I/O-bound operations
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    jobService.startUsing();
    
    // Create a quota check job that will run on a virtual thread
    Runnable quotaCheckTask = () -> {
      // Submit the quota check to run on a virtual thread
      // Wrap in MDCAwareRunnable to ensure proper logging context propagation
      virtualThreadExecutor.execute(new MDCAwareRunnable(createQuotaCheckJob(blobStore, quotaService, log)));
    };
    
    quotaCheckingJob = jobService.schedule(quotaCheckTask, quotaCheckInterval);
  }

  @Override
  protected void doStop() throws Exception {
    blobStore = null;
    
    if (quotaCheckingJob != null) {
      quotaCheckingJob.cancel();
      quotaCheckingJob = null;
    }
    
    // Shutdown the virtual thread executor gracefully
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        // Wait for any in-progress quota checks to complete
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          log.warn("Virtual thread executor did not terminate in time");
          virtualThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        log.warn("Interrupted while waiting for virtual thread executor to shutdown", e);
        Thread.currentThread().interrupt();
        virtualThreadExecutor.shutdownNow();
      }
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