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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.OperationMetrics;
import org.sonatype.nexus.blobstore.api.OperationType;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsEntity;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsStore;
import org.sonatype.nexus.blobstore.api.metrics.DatastoreBlobStoreMetricsContainer;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;
import org.sonatype.nexus.security.subject.SubjectHelper;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base class for {@link BlobStoreMetricsService} implementations.
 */
public abstract class DatastoreBlobStoreMetricsServiceSupport<B extends BlobStore>
    extends StateGuardLifecycleSupport
    implements BlobStoreMetricsService<B>
{
  private final int metricsFlushPeriodSeconds;

  private ScheduledExecutorService scheduledExecutor;

  private final DatastoreBlobStoreMetricsContainer datastoreBlobStoreMetricsContainer;

  protected final BlobStoreMetricsStore blobStoreMetricsStore;

  protected B blobStore;

  /**
   * Constructor for DatastoreBlobStoreMetricsServiceSupport.
   * 
   * @param metricsFlushPeriodSeconds period in seconds between metrics flush operations
   * @param blobStoreMetricsStore the store for blob store metrics
   */
  protected DatastoreBlobStoreMetricsServiceSupport(
      final int metricsFlushPeriodSeconds,
      final BlobStoreMetricsStore blobStoreMetricsStore)
  {
    this.metricsFlushPeriodSeconds = metricsFlushPeriodSeconds;
    this.blobStoreMetricsStore = checkNotNull(blobStoreMetricsStore);

    this.datastoreBlobStoreMetricsContainer = new DatastoreBlobStoreMetricsContainer();
  }

  /**
   * Starts the metrics service by initializing metrics and scheduling periodic flush operations.
   * Uses a single scheduler thread but delegates actual I/O work to virtual threads for efficiency.
   */
  @Override
  protected void doStart() throws Exception {
    blobStoreMetricsStore.initializeMetrics(blobStore.getBlobStoreConfiguration().getName());
    
    // Create a scheduler for timing the metrics flush operations
    scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    
    // Schedule the metrics flushing task at fixed intervals
    scheduledExecutor.scheduleAtFixedRate(() -> {
      if (datastoreBlobStoreMetricsContainer.metricsNeedFlushing()) {
        try {
          // Use virtual thread for I/O-bound flush operation
          // This creates a new virtual thread for each flush operation, which is lightweight and efficient
          Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
              // Ensure thread-local context propagation for security subject
              // This is critical for maintaining security context across virtual thread boundaries
              FakeAlmightySubject.runWithSubject(() -> {
                try {
                  flush();
                }
                catch (Exception e) {
                  log.error("Failed to save blobstore metrics to db", e);
                }
              });
            } catch (Exception e) {
              log.error("Failed to execute metrics flush operation", e);
            }
          });
        }
        catch (Exception e) {
          log.error("Failed to schedule metrics flush operation", e);
        }
      }
    }, 0, metricsFlushPeriodSeconds, TimeUnit.SECONDS);
  }

  /**
   * Stops the metrics service by shutting down the scheduler.
   * Ensures graceful shutdown with timeout handling.
   */
  @Override
  public void doStop() throws Exception {
    if (scheduledExecutor != null) {
      // Attempt graceful shutdown first
      scheduledExecutor.shutdown();
      try {
        // Wait for tasks to complete with a reasonable timeout
        if (!scheduledExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
          // Force shutdown if tasks don't complete in time
          scheduledExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        // If current thread is interrupted, force shutdown and preserve interrupt status
        scheduledExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
      scheduledExecutor = null;
    }
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

  /**
   * Flushes accumulated metrics to the persistent store.
   * This method is optimized to run efficiently on virtual threads as it performs I/O operations.
   */
  @Override
  public void flush() throws IOException {
    // Get the delta metrics for upload and download operations
    OperationMetrics uploadMetrics =
        datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().get(OperationType.UPLOAD);
    OperationMetrics downloadMetrics =
        datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().get(OperationType.DOWNLOAD);

    // Create a metrics entity with all the accumulated values
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

    // Clear the metrics after capturing them
    uploadMetrics.clear();
    downloadMetrics.clear();

    // Persist the metrics to the store
    // This I/O operation benefits from running on a virtual thread
    blobStoreMetricsStore.updateMetrics(blobStoreMetricsEntity);
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