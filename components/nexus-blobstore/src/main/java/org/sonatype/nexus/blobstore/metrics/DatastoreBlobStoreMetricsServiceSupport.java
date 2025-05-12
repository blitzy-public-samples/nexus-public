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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.OperationMetrics;
import org.sonatype.nexus.blobstore.api.OperationType;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsEntity;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsStore;
import org.sonatype.nexus.blobstore.api.metrics.DatastoreBlobStoreMetricsContainer;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.common.scheduling.PeriodicJobService.PeriodicJob;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.common.thread.TcclBlock;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base class for {@link BlobStoreMetricsService} implementations.
 * 
 * @since 3.0
 */
public abstract class DatastoreBlobStoreMetricsServiceSupport<B extends BlobStore>
    extends StateGuardLifecycleSupport
    implements BlobStoreMetricsService<B>
{
  private final int metricsFlushPeriodSeconds;

  private final PeriodicJobService jobService;

  private final DatastoreBlobStoreMetricsContainer datastoreBlobStoreMetricsContainer;

  private PeriodicJob metricsWritingJob;
  
  // Virtual thread executor for metrics flushing
  private ExecutorService virtualThreadExecutor;
  
  // Scheduler for periodic metrics flushing
  private ScheduledExecutorService scheduler;
  
  // Future for the scheduled metrics flushing task
  private ScheduledFuture<?> scheduledFlushTask;

  protected final BlobStoreMetricsStore blobStoreMetricsStore;

  protected B blobStore;

  protected DatastoreBlobStoreMetricsServiceSupport(
      final int metricsFlushPeriodSeconds,
      final PeriodicJobService jobService,
      final BlobStoreMetricsStore blobStoreMetricsStore)
  {
    this.metricsFlushPeriodSeconds = metricsFlushPeriodSeconds;
    this.jobService = checkNotNull(jobService);
    this.blobStoreMetricsStore = checkNotNull(blobStoreMetricsStore);

    this.datastoreBlobStoreMetricsContainer = new DatastoreBlobStoreMetricsContainer();
  }

  @Override
  protected void doStart() throws Exception {
    blobStoreMetricsStore.initializeMetrics(blobStore.getBlobStoreConfiguration().getName());
    
    // Initialize virtual thread executor for I/O operations
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Initialize scheduler for periodic tasks
    scheduler = Executors.newSingleThreadScheduledExecutor();
    
    // Schedule metrics flushing using virtual threads
    scheduledFlushTask = scheduler.scheduleAtFixedRate(() -> {
      if (datastoreBlobStoreMetricsContainer.metricsNeedFlushing()) {
        // Submit the flush task to the virtual thread executor
        virtualThreadExecutor.execute(() -> {
          // Capture the current thread context class loader
          try (TcclBlock tccl = TcclBlock.begin(this.getClass().getClassLoader())) {
            try {
              this.flush();
            }
            catch (Exception e) {
              log.error("Failed to save blobstore metrics to db", e);
            }
          }
        });
      }
    }, 0, metricsFlushPeriodSeconds, TimeUnit.SECONDS);
    
    // Keep using the jobService for backward compatibility
    jobService.startUsing();
  }

  @Override
  public void doStop() throws Exception {
    // Cancel the scheduled task
    if (scheduledFlushTask != null && !scheduledFlushTask.isCancelled()) {
      scheduledFlushTask.cancel(false);
      scheduledFlushTask = null;
    }
    
    // Shutdown the scheduler
    if (scheduler != null && !scheduler.isShutdown()) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        scheduler.shutdownNow();
      }
      scheduler = null;
    }
    
    // Shutdown the virtual thread executor
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        virtualThreadExecutor.shutdownNow();
      }
      virtualThreadExecutor = null;
    }
    
    // For backward compatibility
    if (metricsWritingJob != null) {
      metricsWritingJob.cancel();
      metricsWritingJob = null;
    }
    jobService.stopUsing();
  }

  @Override
  public void init(final B blobStore) throws Exception {
    this.blobStore = blobStore;
    this.start();
  }

  @Override
  public void recordAddition(final long size) {
    datastoreBlobStoreMetricsContainer.recordAddition(size);
  }

  @Override
  public void recordDeletion(final long size) {
    datastoreBlobStoreMetricsContainer.recordDeletion(size);
  }

  @Override
  public Map<OperationType, OperationMetrics> getOperationMetrics() {
    BlobStoreMetricsEntity metricsEntity =
        blobStoreMetricsStore.get(this.blobStore.getBlobStoreConfiguration().getName());

    Map<OperationType, OperationMetrics> delta = getOperationMetricsDelta();

    OperationMetrics uploadMetrics = new OperationMetrics();
    uploadMetrics.setBlobSize(metricsEntity.getUploadBlobSize());
    uploadMetrics.setErrorRequests(metricsEntity.getUploadErrorRequests());
    uploadMetrics.setSuccessfulRequests(metricsEntity.getUploadSuccessfulRequests());
    uploadMetrics.setTimeOnRequests(metricsEntity.getUploadTimeOnRequests());
    uploadMetrics.add(delta.get(OperationType.UPLOAD));

    OperationMetrics downloadMetrics = new OperationMetrics();
    downloadMetrics.setBlobSize(metricsEntity.getDownloadBlobSize());
    downloadMetrics.setErrorRequests(metricsEntity.getDownloadErrorRequests());
    downloadMetrics.setSuccessfulRequests(metricsEntity.getDownloadSuccessfulRequests());
    downloadMetrics.setTimeOnRequests(metricsEntity.getDownloadTimeOnRequests());
    downloadMetrics.add(delta.get(OperationType.DOWNLOAD));

    Map<OperationType, OperationMetrics> operationMetricsMap = new HashMap<>();
    operationMetricsMap.put(OperationType.UPLOAD, uploadMetrics);
    operationMetricsMap.put(OperationType.DOWNLOAD, downloadMetrics);

    return Collections.unmodifiableMap(operationMetricsMap);
  }

  @Override
  public Map<OperationType, OperationMetrics> getOperationMetricsDelta() {
    return datastoreBlobStoreMetricsContainer.getOperationMetricsDelta();
  }

  @Override
  public void flush() throws IOException {
    // This method is now optimized to run in a virtual thread
    OperationMetrics uploadMetrics =
        datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().get(OperationType.UPLOAD);
    OperationMetrics downloadMetrics =
        datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().get(OperationType.DOWNLOAD);

    BlobStoreMetricsEntity blobStoreMetricsEntity = new BlobStoreMetricsEntity()
        .setBlobStoreName(blobStore.getBlobStoreConfiguration().getName())
        .setBlobCount(datastoreBlobStoreMetricsContainer.blobCountDelta.getAndSet(0L))
        .setTotalSize(datastoreBlobStoreMetricsContainer.blobstoreUsageDelta.getAndSet(0L))
        .setDownloadBlobSize(downloadMetrics.getBlobSize())
        .setDownloadErrorRequests(downloadMetrics.getErrorRequests())
        .setDownloadSuccessfulRequests(downloadMetrics.getSuccessfulRequests())
        .setDownloadTimeOnRequests(downloadMetrics.getTimeOnRequests())
        .setUploadBlobSize(uploadMetrics.getBlobSize())
        .setUploadErrorRequests(uploadMetrics.getErrorRequests())
        .setUploadSuccessfulRequests(uploadMetrics.getSuccessfulRequests())
        .setDownloadTimeOnRequests(uploadMetrics.getTimeOnRequests());

    uploadMetrics.clear();
    downloadMetrics.clear();

    // This I/O operation is now running in a virtual thread
    blobStoreMetricsStore.updateMetrics(blobStoreMetricsEntity);
  }

  @Override
  public void clearCountMetrics() {
    // Submit to virtual thread executor for I/O operations
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.execute(() -> {
        try (TcclBlock tccl = TcclBlock.begin(this.getClass().getClassLoader())) {
          blobStoreMetricsStore.clearCountMetrics(blobStore.getBlobStoreConfiguration().getName());
        }
      });
    } else {
      // Fallback if executor is not available
      blobStoreMetricsStore.clearCountMetrics(blobStore.getBlobStoreConfiguration().getName());
    }
  }

  @Override
  public void clearOperationMetrics() {
    datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().values().forEach(OperationMetrics::clear);
    
    // Submit to virtual thread executor for I/O operations
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.execute(() -> {
        try (TcclBlock tccl = TcclBlock.begin(this.getClass().getClassLoader())) {
          blobStoreMetricsStore.clearOperationMetrics(blobStore.getBlobStoreConfiguration().getName());
        }
      });
    } else {
      // Fallback if executor is not available
      blobStoreMetricsStore.clearOperationMetrics(blobStore.getBlobStoreConfiguration().getName());
    }
  }

  @Override
  public void remove() {
    // Submit to virtual thread executor for I/O operations
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.execute(() -> {
        try (TcclBlock tccl = TcclBlock.begin(this.getClass().getClassLoader())) {
          blobStoreMetricsStore.remove(blobStore.getBlobStoreConfiguration().getName());
        }
      });
    } else {
      // Fallback if executor is not available
      blobStoreMetricsStore.remove(blobStore.getBlobStoreConfiguration().getName());
    }
  }
}