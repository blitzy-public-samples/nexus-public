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
package org.sonatype.nexus.blobstore.s3.internal.datastore;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.AccumulatingBlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.OperationMetrics;
import org.sonatype.nexus.blobstore.api.OperationType;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsEntity;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsStore;

import org.sonatype.nexus.blobstore.metrics.DatastoreBlobStoreMetricsServiceSupport;
import org.sonatype.nexus.blobstore.s3.internal.S3BlobStore;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;

import com.google.common.collect.ImmutableMap;

/**
 * S3 BlobStore metrics service implementation that leverages Java 21 Virtual Threads
 * for improved I/O performance during metrics collection and flushing operations.
 */
@Named(S3BlobStore.TYPE)
@Priority(Integer.MAX_VALUE)
public class DatastoreS3BlobStoreMetricsService
    extends DatastoreBlobStoreMetricsServiceSupport<S3BlobStore>
{
  private static final ImmutableMap<String, Long> AVAILABLE_SPACE_BY_FILE_STORE = ImmutableMap.of("s3", Long.MAX_VALUE);

  @Inject
  public DatastoreS3BlobStoreMetricsService(
      @Named("${nexus.blobstore.metrics.flushInterval:-2}") final int metricsFlushPeriodSeconds,
      final BlobStoreMetricsStore blobStoreMetricsStore,
      final PeriodicJobService jobService)
  {
    super(metricsFlushPeriodSeconds, jobService, blobStoreMetricsStore);
  }


  /**
   * Overrides the getMetrics method to use Java 21 Virtual Threads for improved I/O performance.
   * This implementation creates a virtual thread to retrieve metrics from the database,
   * allowing the carrier thread to be used for other tasks during I/O operations.
   */
  @Override
  public BlobStoreMetrics getMetrics() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> {
        BlobStoreMetricsEntity metricsEntity =
            blobStoreMetricsStore.get(blobStore.getBlobStoreConfiguration().getName());

        return new AccumulatingBlobStoreMetrics(
            metricsEntity.getBlobCount(),
            metricsEntity.getTotalSize(),
            AVAILABLE_SPACE_BY_FILE_STORE,
            true);
      }).get(); // Wait for completion
    } catch (Exception e) {
      log.error("Error retrieving metrics using virtual thread", e);
      // Fallback to direct retrieval in case of error
      BlobStoreMetricsEntity metricsEntity =
          blobStoreMetricsStore.get(blobStore.getBlobStoreConfiguration().getName());

      return new AccumulatingBlobStoreMetrics(
          metricsEntity.getBlobCount(),
          metricsEntity.getTotalSize(),
          AVAILABLE_SPACE_BY_FILE_STORE,
          true);
    }
  }
  
  /**
   * Overrides the flush method to use Java 21 Virtual Threads for improved I/O performance.
   * This implementation creates a virtual thread to perform the database operation,
   * allowing the carrier thread to be used for other tasks during I/O operations.
   */
  @Override
  public void flush() throws IOException {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          // Get metrics from the metrics container
          OperationMetrics uploadMetrics =
              datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().get(OperationType.UPLOAD);
          OperationMetrics downloadMetrics =
              datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().get(OperationType.DOWNLOAD);

          // Create the metrics entity
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

          // Clear metrics after capturing them
          uploadMetrics.clear();
          downloadMetrics.clear();

          // Update metrics in the store (I/O operation that benefits from Virtual Threads)
          blobStoreMetricsStore.updateMetrics(blobStoreMetricsEntity);
          return null;
        } catch (Exception e) {
          log.error("Failed to flush metrics using virtual thread", e);
          throw new RuntimeException(e);
        }
      }).get(); // Wait for completion
    } catch (Exception e) {
      log.error("Error during metrics flush operation", e);
      throw new IOException("Failed to flush metrics", e);
    }
  }
  
  /**
   * Overrides the clearCountMetrics method to use Java 21 Virtual Threads for improved I/O performance.
   * This implementation creates a virtual thread to clear count metrics from the database,
   * allowing the carrier thread to be used for other tasks during I/O operations.
   */
  @Override
  public void clearCountMetrics() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        blobStoreMetricsStore.clearCountMetrics(blobStore.getBlobStoreConfiguration().getName());
        return null;
      }).get(); // Wait for completion
    } catch (Exception e) {
      log.error("Error clearing count metrics using virtual thread", e);
      // Fallback to direct operation in case of error
      blobStoreMetricsStore.clearCountMetrics(blobStore.getBlobStoreConfiguration().getName());
    }
  }
  
  /**
   * Overrides the clearOperationMetrics method to use Java 21 Virtual Threads for improved I/O performance.
   * This implementation creates a virtual thread to clear operation metrics from the database,
   * allowing the carrier thread to be used for other tasks during I/O operations.
   */
  @Override
  public void clearOperationMetrics() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().values().forEach(OperationMetrics::clear);
        blobStoreMetricsStore.clearOperationMetrics(blobStore.getBlobStoreConfiguration().getName());
        return null;
      }).get(); // Wait for completion
    } catch (Exception e) {
      log.error("Error clearing operation metrics using virtual thread", e);
      // Fallback to direct operation in case of error
      datastoreBlobStoreMetricsContainer.getOperationMetricsDelta().values().forEach(OperationMetrics::clear);
      blobStoreMetricsStore.clearOperationMetrics(blobStore.getBlobStoreConfiguration().getName());
    }
  }
  
  /**
   * Overrides the remove method to use Java 21 Virtual Threads for improved I/O performance.
   * This implementation creates a virtual thread to remove metrics from the database,
   * allowing the carrier thread to be used for other tasks during I/O operations.
   */
  @Override
  public void remove() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        blobStoreMetricsStore.remove(blobStore.getBlobStoreConfiguration().getName());
        return null;
      }).get(); // Wait for completion
    } catch (Exception e) {
      log.error("Error removing metrics using virtual thread", e);
      // Fallback to direct operation in case of error
      blobStoreMetricsStore.remove(blobStore.getBlobStoreConfiguration().getName());
    }
  }
}