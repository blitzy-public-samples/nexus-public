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

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.AccumulatingBlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsEntity;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsStore;
import org.sonatype.nexus.blobstore.metrics.DatastoreBlobStoreMetricsServiceSupport;
import org.sonatype.nexus.blobstore.s3.internal.S3BlobStore;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;

import com.google.common.collect.ImmutableMap;

/**
 * S3 implementation of {@link org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService}.
 * <p>
 * This implementation leverages Java 21 Virtual Threads for metrics collection and processing,
 * providing improved performance for I/O-bound operations through the parent class
 * {@link DatastoreBlobStoreMetricsServiceSupport}.
 *
 * @since 3.6.1
 */
@Named(S3BlobStore.TYPE)
@Priority(Integer.MAX_VALUE)
public class DatastoreS3BlobStoreMetricsService
    extends DatastoreBlobStoreMetricsServiceSupport<S3BlobStore>
{
  // S3 storage is considered unlimited for metrics reporting purposes
  private static final ImmutableMap<String, Long> AVAILABLE_SPACE_BY_FILE_STORE = ImmutableMap.of("s3", Long.MAX_VALUE);

  @Inject
  public DatastoreS3BlobStoreMetricsService(
      @Named("${nexus.blobstore.metrics.flushInterval:-2}") final int metricsFlushPeriodSeconds,
      final BlobStoreMetricsStore blobStoreMetricsStore,
      final PeriodicJobService jobService)
  {
    super(metricsFlushPeriodSeconds, jobService, blobStoreMetricsStore);
  }

  @Override
  public BlobStoreMetrics getMetrics() {
    // Get metrics entity from the store - this operation benefits from Virtual Threads in the parent class
    BlobStoreMetricsEntity metricsEntity =
        blobStoreMetricsStore.get(blobStore.getBlobStoreConfiguration().getName());

    // Create metrics object with S3-specific configuration (unlimited space)
    return new AccumulatingBlobStoreMetrics(
        metricsEntity.getBlobCount(),
        metricsEntity.getTotalSize(),
        AVAILABLE_SPACE_BY_FILE_STORE,
        true);
  }
}
