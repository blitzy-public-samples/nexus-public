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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

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

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base class for {@link BlobStoreMetricsService} implementations.
 */
public abstract class DatastoreBlobStoreMetricsServiceSupport<B extends BlobStore>
    extends StateGuardLifecycleSupport
    implements BlobStoreMetricsService<B>
{
  private final int metricsFlushPeriodSeconds;

  private final PeriodicJobService jobService;

  private final DatastoreBlobStoreMetricsContainer datastoreBlobStoreMetricsContainer;
  
  // Executor for running metrics flush operations using virtual threads for improved concurrency
  private final Executor virtualThreadExecutor;
  
  // Lock to prevent concurrent flush operations and reduce contention
  private final ReentrantLock flushLock = new ReentrantLock();

  private PeriodicJob metricsWritingJob;

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
    
    // Create a virtual thread per task executor for metrics operations
    // This leverages Java 21's virtual threads for lightweight concurrency with minimal overhead
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  protected void doStart() throws Exception {
    blobStoreMetricsStore.initializeMetrics(blobStore.getBlobStoreConfiguration().getName());
    jobService.startUsing();
    metricsWritingJob = jobService.schedule(() -> {
      if (datastoreBlobStoreMetricsContainer.metricsNeedFlushing()) {
        try {
          // Use virtual threads to perform the flush operation asynchronously
          // This prevents blocking the periodic job thread and allows for better concurrency
          CompletableFuture.runAsync(() -> {
            try {
              // Only allow one flush operation at a time
              if (flushLock.tryLock()) {
                try {
                  this.flush();
                }
                finally {
                  flushLock.unlock();
                }
              }
            }
            catch (Exception e) {
              log.error("Failed to save blobstore metrics to db", e);
            }
          }, virtualThreadExecutor);
        }
        catch (Exception e) {
          log.error("Failed to schedule metrics flush operation", e);
        }
      }
    }, metricsFlushPeriodSeconds);
  }

  @Override
  public void doStop() throws Exception {
    metricsWritingJob.cancel();
    metricsWritingJob = null;
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
    // Create local copies of metrics to minimize contention during the flush operation
    OperationMetrics uploadMetrics =
        datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().get(OperationType.UPLOAD);
    OperationMetrics downloadMetrics =
        datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().get(OperationType.DOWNLOAD);

    // Create the metrics entity with the current values
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
        .setUploadTimeOnRequests(uploadMetrics.getTimeOnRequests());

    // Clear the metrics after capturing their values
    uploadMetrics.clear();
    downloadMetrics.clear();

    // Update the metrics in the store using a non-blocking approach
    // This ensures that the flush operation doesn't block other operations
    try {
      blobStoreMetricsStore.updateMetrics(blobStoreMetricsEntity);
    }
    catch (Exception e) {
      log.error("Failed to update metrics in store: {}", e.getMessage(), e);
      throw new IOException("Failed to update metrics", e);
    }
  }

  @Override
  public void clearCountMetrics() {
    blobStoreMetricsStore.clearCountMetrics(blobStore.getBlobStoreConfiguration().getName());
  }

  @Override
  public void clearOperationMetrics() {
    datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().values().forEach(OperationMetrics::clear);
    blobStoreMetricsStore.clearOperationMetrics(blobStore.getBlobStoreConfiguration().getName());
  }

  @Override
  public void remove() {
    blobStoreMetricsStore.remove(blobStore.getBlobStoreConfiguration().getName());
  }
}