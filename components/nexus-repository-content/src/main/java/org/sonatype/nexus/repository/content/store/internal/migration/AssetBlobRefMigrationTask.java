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
package org.sonatype.nexus.repository.content.store.internal.migration;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.store.AssetBlobData;
import org.sonatype.nexus.repository.content.store.AssetBlobStore;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.CancelableHelper;
import org.sonatype.nexus.scheduling.TaskSupport;

import com.google.common.annotations.VisibleForTesting;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.repository.content.store.internal.migration.AssetBlobRefMigrationTaskDescriptor.CONTENT_STORE_FIELD_ID;
import static org.sonatype.nexus.repository.content.store.internal.migration.AssetBlobRefMigrationTaskDescriptor.FORMAT_FIELD_ID;

/**
 * Migrate asset blob's blobRef field from {@code store-name:blob-id@node-id} to {@code store-name@blob-id} format.
 * 
 * This implementation uses Java 21 Virtual Threads for improved I/O operation concurrency and
 * String Templates for more readable logging.
 */
@Named
public class AssetBlobRefMigrationTask
    extends TaskSupport
    implements Cancelable
{
  private final Map<String, FormatStoreManager> formatStoreManagers;

  private final int readAssetsBatchSize;

  @Inject
  public AssetBlobRefMigrationTask(
      final Map<String, FormatStoreManager> formatStoreManagers,
      @Named("${nexus.assetBlobRef.migration.read.batchSize:-100}") final int readAssetsBatchSize)
  {
    this.formatStoreManagers = checkNotNull(formatStoreManagers);

    checkArgument(readAssetsBatchSize >= 0, "Must use a non-negative readAssetsBatchSize");
    this.readAssetsBatchSize = readAssetsBatchSize;
  }

  @Override
  protected Void execute() throws Exception {
    String format = getConfiguration().getString(FORMAT_FIELD_ID);
    String contentStore = getConfiguration().getString(CONTENT_STORE_FIELD_ID);

    FormatStoreManager formatStoreManager = formatStoreManagers.get(format);
    if (formatStoreManager != null) {
      AssetBlobStore<?> assetBlobStore = formatStoreManager.assetBlobStore(contentStore);

      int updatedCount = migrate(assetBlobStore, format);
      if (updatedCount > 0) {
        log.info(STR."Updated \{updatedCount} \{format} blobs with new blob ref fields from \{contentStore}");
      }
    }
    else {
      log.warn(STR."Unknown format \{format}");
    }

    return null;
  }

  private int migrate(final AssetBlobStore<?> assetBlobStore, final String format) {
    CancelableHelper.checkCancellation();

    int updateCount = 0;

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Continuation<AssetBlob> assetBlobs = assetBlobStore.browseAssetsWithLegacyBlobRef(readAssetsBatchSize, null);
      List<Future<Integer>> migrationTasks = new ArrayList<>();

      while (!isCanceled() && !assetBlobs.isEmpty()) {
        // Check for cancellation before each batch
        CancelableHelper.checkCancellation();
        
        // Create a copy of the current batch to avoid concurrent modification
        Collection<AssetBlob> currentBatch = List.copyOf(assetBlobs);
        
        // Submit the batch migration as a virtual thread task
        Future<Integer> migrationTask = executor.submit(() -> migrateAssetBlobs(assetBlobStore, format, currentBatch));
        migrationTasks.add(migrationTask);
        
        // Get the next batch
        assetBlobs = assetBlobStore.browseAssetsWithLegacyBlobRef(readAssetsBatchSize, assetBlobs.nextContinuationToken());
      }

      // Collect results from all migration tasks
      for (Future<Integer> task : migrationTasks) {
        if (!isCanceled()) {
          updateCount += task.get();
        } else {
          break;
        }
      }
    } catch (Exception e) {
      log.error(STR."Error during asset blob migration: \{e.getMessage()}");
      if (log.isDebugEnabled()) {
        e.printStackTrace();
      }
    }
    
    return updateCount;
  }

  private int migrateAssetBlobs(final AssetBlobStore<?> assetBlobStore,
                                final String format,
                                final Collection<AssetBlob> assetBlobs) {
    int migratedAssetsCount = 0;

    // Check for cancellation before processing
    try {
      CancelableHelper.checkCancellation();
    } catch (Exception e) {
      return 0;
    }

    // Thread-safe update of duplicated blob references
    updateDuplicatedBlobRef(assetBlobs);

    try {
      if (assetBlobStore.updateBlobRefs(assetBlobs)) {
        migratedAssetsCount = assetBlobs.size();
        log.info(STR."Migrated \{format} \{migratedAssetsCount} to the new blob ref fields");
      }
      else {
        log.info(STR."Could not migrate \{format} \{assetBlobs.size()} blobs with new blob ref fields");
      }
    }
    catch (DuplicateKeyException e) {
      log.error(STR."Error updating asset blobs in batch fashion. Error \{e.getMessage()}");
      if (log.isDebugEnabled()) {
        e.printStackTrace();
      }
      // try to migrate in row-by-row fashion
      migratedAssetsCount = migrateRowByRow(assetBlobStore, format, assetBlobs);
    }

    return migratedAssetsCount;
  }

  @VisibleForTesting
  synchronized void updateDuplicatedBlobRef(Collection<AssetBlob> assetBlobs) {
    assetBlobs.stream()
        .collect(Collectors.groupingBy(AssetBlob::blobRef))
        .values()
        .stream()
        .filter(blobs -> blobs.size() > 1)
        .flatMap(Collection::stream)
        .filter(a -> a instanceof AssetBlobData)
        .map(assetBlob -> (AssetBlobData) assetBlob)
        .forEach(assetBlobData -> {
          BlobRef oldBlobRef = assetBlobData.blobRef();
          BlobRef newBlobRef = new BlobRef(
              null, oldBlobRef.getStore(), UUID.randomUUID().toString(), oldBlobRef.getDateBasedRef());
          assetBlobData.setBlobRef(newBlobRef);
        });
  }

  private int migrateRowByRow(final AssetBlobStore<?> assetBlobStore,
                              final String format,
                              final Collection<AssetBlob> assetBlobs) {
    AtomicInteger updated = new AtomicInteger();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> tasks = new ArrayList<>();

      for (AssetBlob assetBlob : assetBlobs) {
        // Check for cancellation before submitting each task
        if (isCanceled()) {
          break;
        }
        
        Future<?> task = executor.submit(() -> {
          try {
            CancelableHelper.checkCancellation();
            if (assetBlobStore.updateBlobRef(assetBlob)) {
              log.info(STR."Asset blob \{format} \{assetBlob} updated with new blob ref format");
              updated.getAndIncrement();
            }
            else {
              log.info(STR."Could not migrate \{format} \{assetBlob} blobs with new blob ref fields");
            }
          }
          catch (DuplicateKeyException e) {
            log.error(STR."Error migration \{format} \{assetBlob} asset blob to new blob ref format");
          }
          catch (Exception e) {
            log.error(STR."Unexpected error during row migration: \{e.getMessage()}");
            if (log.isDebugEnabled()) {
              e.printStackTrace();
            }
          }
        });
        
        tasks.add(task);
      }

      // Wait for all tasks to complete if not canceled
      for (Future<?> task : tasks) {
        if (!isCanceled()) {
          try {
            task.get();
          } catch (Exception e) {
            log.debug(STR."Task execution error: \{e.getMessage()}");
          }
        }
      }
    } catch (Exception e) {
      log.error(STR."Error during row-by-row migration: \{e.getMessage()}");
      if (log.isDebugEnabled()) {
        e.printStackTrace();
      }
    }
    
    return updated.get();
  }

  @Override
  public String getMessage() {
    return getName();
  }
}