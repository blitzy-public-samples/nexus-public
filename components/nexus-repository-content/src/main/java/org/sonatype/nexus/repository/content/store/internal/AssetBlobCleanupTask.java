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
package org.sonatype.nexus.repository.content.store.internal;

import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.logging.task.TaskLogging;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.store.AssetBlobStore;
import org.sonatype.nexus.repository.content.store.BlobRefTypeHandler;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.sonatype.nexus.common.property.SystemPropertiesHelper.getBoolean;
import static org.sonatype.nexus.common.property.SystemPropertiesHelper.getInteger;
import static org.sonatype.nexus.common.property.SystemPropertiesHelper.getString;
import static org.sonatype.nexus.logging.task.TaskLogType.NEXUS_LOG_ONLY;
import static org.sonatype.nexus.repository.content.store.internal.AssetBlobCleanupTaskDescriptor.CONTENT_STORE_FIELD_ID;
import static org.sonatype.nexus.repository.content.store.internal.AssetBlobCleanupTaskDescriptor.FORMAT_FIELD_ID;

/**
 * Background task that cleans up unused {@link AssetBlob}s.
 *
 * @since 3.24
 */
@Named
@TaskLogging(NEXUS_LOG_ONLY)
public class AssetBlobCleanupTask
    extends TaskSupport
    implements Cancelable
{
  static final String PROPERTY_PREFIX = "nexus.assetBlobCleanupTask.";

  static final String CRON_SCHEDULE = getString(PROPERTY_PREFIX + "cronSchedule", "0 */30 * * * ?");

  static final int BATCH_SIZE = getInteger(PROPERTY_PREFIX + "batchSize", 1000);

  static final boolean HARD_DELETE = getBoolean(PROPERTY_PREFIX + "hardDelete", false);

  static final int BLOB_CREATED_DELAY_MINUTE =
      getInteger(PROPERTY_PREFIX + "blobCreatedDelayMinute", 60);

  static final int BATCH_DELETE_POOL_SIZE = getInteger(
      PROPERTY_PREFIX + "batchDeleteThreadPoolSize", 8);

  private final Map<String, FormatStoreManager> formatStoreManagers;

  private final BlobStoreManager blobStoreManager;

  private Boolean batchDeleteEnabled = true;

  private ExecutorService batchDeleteExecutorService;

  @Inject
  public AssetBlobCleanupTask(
      final Map<String, FormatStoreManager> formatStoreManagers,
      final BlobStoreManager blobStoreManager)
  {
    this.formatStoreManagers = checkNotNull(formatStoreManagers);
    this.blobStoreManager = checkNotNull(blobStoreManager);
  }

  protected void initBatchDeleteIfEnabled(final String format) {
    String batchDeleteIgnoreFormats =
        getString(PROPERTY_PREFIX + "batchDeleteIgnoreForFormat", null);

    if (batchDeleteIgnoreFormats != null && format != null
        && batchDeleteIgnoreFormats.contains(format)) {
      batchDeleteEnabled = false;
    } else {
      batchDeleteExecutorService = newVirtualThreadPerTaskExecutor();
    }
  }

  @Override
  protected Void execute() throws Exception {
    String format = getConfiguration().getString(FORMAT_FIELD_ID);
    String contentStore = getConfiguration().getString(CONTENT_STORE_FIELD_ID);
    initBatchDeleteIfEnabled(format);

    FormatStoreManager formatStoreManager = formatStoreManagers.get(format);
    if (formatStoreManager != null) {
      log.debug(STR."Checking for unused \{format} blobs from \{contentStore}");
      AssetBlobStore<?> assetBlobStore = formatStoreManager.assetBlobStore(contentStore);
      int deleteCount;
      if (batchDeleteEnabled) {
        try {
          deleteCount = deleteUnusedAssetBlobsBatch(assetBlobStore, format, contentStore);
        } finally {
          if (!batchDeleteExecutorService.isShutdown()) {
            batchDeleteExecutorService.shutdown();
          }
        }
      }
      else {
        deleteCount = deleteUnusedAssetBlobs(assetBlobStore, format, contentStore);
      }
      if (deleteCount > 0) {
        log.info(STR."Deleted \{deleteCount} unused \{format} blobs from \{contentStore}");
      }
    }
    else {
      log.warn(STR."Unknown format \{format}");
    }

    return null;
  }

  /**
   * Deletes unused {@link AssetBlob}s for the given format and content store.
   *
   * @return count of deleted asset blobs
   * @deprecated use {@link #deleteUnusedAssetBlobsBatch(AssetBlobStore, String, String)}} instead
   */
  @Deprecated
  private int deleteUnusedAssetBlobs(
      final AssetBlobStore<?> assetBlobStore,
      final String format,
      final String contentStore)
  {
    int deleteCount = 0;

    Continuation<AssetBlob> unusedAssetBlobs =
        assetBlobStore.browseUnusedAssetBlobs(BATCH_SIZE, BLOB_CREATED_DELAY_MINUTE, null);
    while (!isCanceled() && !unusedAssetBlobs.isEmpty()) {
      log.debug(STR."Found \{unusedAssetBlobs.size()} unused \{format} blobs in \{contentStore}");
      for (AssetBlob assetBlob : unusedAssetBlobs) {
        if (isCanceled()) {
          break;
        }
        try {
          if (deleteAssetBlob(assetBlobStore, assetBlob.blobRef())) {
            deleteCount++;
          }
          else {
            // this doesn't necessarily indicate a problem, could be this particular blob is no longer unused
            log.debug(STR."Could not delete \{format} blob \{assetBlob.blobRef()} from \{contentStore}");
          }
        }
        catch (RuntimeException e) {
          // this doesn't necessarily indicate a problem, could be this particular blob is no longer unused
          log.debug(STR."Could not delete \{format} blob \{assetBlob.blobRef()} from \{contentStore}", e);
        }
      }
      unusedAssetBlobs = assetBlobStore.browseUnusedAssetBlobs(
          BATCH_SIZE, BLOB_CREATED_DELAY_MINUTE, unusedAssetBlobs.nextContinuationToken());
    }
    return deleteCount;
  }

  /**
   * Deletes unused {@link AssetBlob}s for the given format and content store in a batch manner.
   *
   * @return count of deleted asset blobs
   */
  @VisibleForTesting
  int deleteUnusedAssetBlobsBatch(
      final AssetBlobStore<?> assetBlobStore,
      final String format,
      final String contentStore)
  {
    int deleteCount = 0;
    Continuation<AssetBlob> unusedAssetBlobs =
        assetBlobStore.browseUnusedAssetBlobs(BATCH_SIZE, BLOB_CREATED_DELAY_MINUTE, null);
    while (!isCanceled() && !unusedAssetBlobs.isEmpty()) {
      if (isCanceled()) {
        break;
      }
      log.debug(STR."Found \{unusedAssetBlobs.size()} unused \{format} blobs in \{contentStore}");
      List<BlobRef> blobRefAll = extractBlobRefsFromAssetBlobs(unusedAssetBlobs);
      deleteAssetBlobsExecutorService(blobRefAll);

      String[] blobRefIds = blobRefAll.stream()
          .map(BlobRefTypeHandler::toPersistableString)
          .toArray(String[]::new);
      assetBlobStore.deleteAssetBlobBatch(blobRefIds);
      deleteCount += blobRefAll.size();

      unusedAssetBlobs = assetBlobStore.browseUnusedAssetBlobs(
          BATCH_SIZE, BLOB_CREATED_DELAY_MINUTE, unusedAssetBlobs.nextContinuationToken());
    }
    return deleteCount;
  }

  /**
   * Deletes the {@link AssetBlob} and associated {@link Blob}.
   *
   * @return {@code true} if the asset blob was deleted; otherwise {@code false}
   */
  private boolean deleteAssetBlob(final AssetBlobStore<?> assetBlobStore, final BlobRef blobRef) {
    boolean assetBlobDeleted = false;

    BlobStore blobStore = blobStoreManager.get(blobRef.getStore());
    if (blobStore == null) {
      // postpone delete if the store is temporarily AWOL
      log.warn(STR."Could not find blob store for \{blobRef}");
    }
    else {
      assetBlobDeleted = assetBlobStore.deleteAssetBlob(blobRef);
      if (assetBlobDeleted && !deleteBlobContent(blobStore, blobRef)) {
        log.warn(STR."Could not delete blob content under \{blobRef}");
        // still report asset blob as deleted...
      }
    }

    return assetBlobDeleted;
  }

  /**
   * Deletes batch of {@link Blob}.
   */
  private void deleteAssetBlobsExecutorService(final List<BlobRef> blobRefs) {
    CountDownLatch latch = new CountDownLatch(blobRefs.size());
    for (BlobRef blobRef : blobRefs) {
      batchDeleteExecutorService.submit(() -> {
        try {
          BlobStore blobStore = blobStoreManager.get(blobRef.getStore());
          if (blobStore == null) {
            // postpone delete if the store is temporarily AWOL
            log.warn(STR."Could not find blob store for \{blobRef}");
          }
          else if (!deleteBlobContent(blobStore, blobRef)) {
            // still report asset blob as deleted...
            log.warn(STR."Could not delete blob content under \{blobRef}");
          }
        } finally {
          latch.countDown();
        }
      });
    }
    try {
      latch.await();
    }
    catch (InterruptedException ex) {
      log.debug(STR."CountDownLatch interrupted: \{ex.getMessage()}");
      Thread.currentThread().interrupt(); // Preserve interrupt status
    }
  }

  /**
   * Deletes the {@link Blob} from its {@link BlobStore}.
   *
   * @return {@code true} if the asset blob was deleted; otherwise {@code false}
   */
  private boolean deleteBlobContent(BlobStore blobStore, final BlobRef blobRef) {
    return switch (HARD_DELETE) {
      case true -> blobStore.deleteHard(blobRef.getBlobId());
      case false -> blobStore.delete(blobRef.getBlobId(), "Removing unused asset blob");
    };
  }

  private List<BlobRef> extractBlobRefsFromAssetBlobs(final Continuation<AssetBlob> assetBlobs) {
    return assetBlobs.stream()
        .map(AssetBlob::blobRef)
        .filter(blobRef -> blobStoreManager.get(blobRef.getStore()) != null)
        .collect(Collectors.toList());
  }

  @Override
  public String getMessage() {
    return getName();
  }

  @Override
  public void cancel() {
    super.cancel();
    if (batchDeleteExecutorService != null) {
      batchDeleteExecutorService.shutdown();
    }
  }
}