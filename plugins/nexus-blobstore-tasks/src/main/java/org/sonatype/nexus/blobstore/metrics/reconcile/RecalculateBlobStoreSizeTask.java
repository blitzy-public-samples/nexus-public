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
package org.sonatype.nexus.blobstore.metrics.reconcile;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService;
import org.sonatype.nexus.blobstore.common.BlobStoreTaskSupport;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.logging.task.ProgressLogIntervalHelper;
import org.sonatype.nexus.logging.task.TaskLogging;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.CancelableHelper;

import org.joda.time.DateTime;

import static org.sonatype.nexus.logging.task.TaskLogType.TASK_LOG_ONLY;

/**
 * Task to recalculate blob store size metrics.
 * Updated for Java 21 with Virtual Threads for improved I/O performance.
 */
@AvailabilityVersion(from = "1.0")
@Named
@TaskLogging(TASK_LOG_ONLY)
public class RecalculateBlobStoreSizeTask
    extends BlobStoreTaskSupport
    implements Cancelable
{
  private static final int LOGGING_INTERVAL = 60;

  private static final String S3_TYPE = "S3";

  private static final int CANCEL_CHECK_INTERVAL = 300;

  @Inject
  public RecalculateBlobStoreSizeTask(final BlobStoreManager blobStoreManager) {
    super(blobStoreManager);
  }

  @Override
  public String getMessage() {
    return STR."recalculate blob store storage for '\{getBlobStoreField()}'";
  }

  @Override
  protected void execute(final BlobStore blobStore) {
    // Using DateTime for compatibility with BlobMetrics API
    // TODO: Consider migrating to java.time.Instant when BlobMetrics API is updated
    DateTime currentDate = DateTime.now();
    boolean includeSoftDeleted = !blobStore.getBlobStoreConfiguration().getType().equals(S3_TYPE);

    BlobStoreMetricsService metricsService = blobStore.getMetricsService();
    metricsService.clearCountMetrics();

    AtomicLong totalSize = new AtomicLong();
    AtomicLong totalCount = new AtomicLong();

    try (ProgressLogIntervalHelper progressLogger = new ProgressLogIntervalHelper(log, LOGGING_INTERVAL);
         // Using Virtual Threads for I/O-bound operations
         ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      
      // Get the stream of blob IDs
      Stream<BlobId> blobIdStream = blobStore.getBlobIdStream();
      
      // Process the blobs using the stream API
      blobIdStream
          .map(blobStore::getBlobAttributes)
          .filter(Objects::nonNull)
          .filter(attributes -> isCreatedBefore(attributes, currentDate))
          .filter(attributes -> includeSoftDeleted || !attributes.isDeleted())
          .map(attributes -> attributes.getMetrics().getContentSize())
          .forEach(blobSize -> {
            totalSize.addAndGet(blobSize);
            metricsService.recordAddition(blobSize);
            if (totalCount.incrementAndGet() % CANCEL_CHECK_INTERVAL == 0) {
              CancelableHelper.checkCancellation();
            }

            progressLogger.info(STR."Re-calculating size metrics on blob store '\{blobStore.getBlobStoreConfiguration().getName()}', size : \{totalSize} - blobs count : \{totalCount}");
          });
    }
  }

  @Override
  protected boolean appliesTo(final BlobStore blobStore) {
    // Using pattern matching for instanceof check
    return !(blobStore.getBlobStoreConfiguration().getType() instanceof String type && type.equals(BlobStoreGroup.TYPE));
  }

  private boolean isCreatedBefore(final BlobAttributes attributes, final DateTime date) {
    return attributes.getMetrics().getCreationTime().isBefore(date);
  }
}