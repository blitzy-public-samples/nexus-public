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
package org.sonatype.nexus.blobstore.compact.internal;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreConfiguration;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskSupport;
import org.sonatype.nexus.scheduling.TaskUtils;

import com.google.common.annotations.VisibleForTesting;

import static java.lang.String.format;
import static java.lang.StringTemplate.STR;
import static java.util.List.of;
import static java.util.Objects.checkNotNull;
import static org.sonatype.nexus.blobstore.compact.internal.CompactBlobStoreTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.TASK_LOG_ONLY;

/**
 * Task to compact a given blob store.
 *
 * @since 3.0
 */
@Named
public class CompactBlobStoreTask
    extends TaskSupport
    implements Cancelable
{
  private final BlobStoreManager blobStoreManager;

  private final Optional<ChangeRepositoryBlobStoreStore> changeBlobstoreStore;

  private final BlobStoreUsageChecker blobStoreUsageChecker;

  private final TaskUtils taskUtils;


  @Inject
  public CompactBlobStoreTask(
      final BlobStoreManager blobStoreManager,
      final ChangeRepositoryBlobStoreStore changeBlobstoreStore,
      final BlobStoreUsageChecker blobStoreUsageChecker,
      final TaskUtils taskUtils)
  {
    this.blobStoreManager = checkNotNull(blobStoreManager);
    this.changeBlobstoreStore = Optional.ofNullable(changeBlobstoreStore);
    this.blobStoreUsageChecker = checkNotNull(blobStoreUsageChecker);
    this.taskUtils = checkNotNull(taskUtils);
  }

  @VisibleForTesting
  void checkForConflicts() {
    String blobStoreName = checkNotNull(getBlobStoreField());

    taskUtils.checkForConflictingTasks(getId(), getName(), of("repository.move"), 
        Map.of("moveInitialBlobstore", of(blobStoreName), "moveTargetBlobstore", of(blobStoreName)));

    checkForUnfinishedMoveTask(blobStoreName);
  }

  private void checkForUnfinishedMoveTask(String blobStoreName) {
    List<ChangeRepositoryBlobStoreConfiguration> existingMoves = changeBlobstoreStore
        .map(store -> store.findByBlobStoreName(blobStoreName))
        .orElseGet(Collections::emptyList);

    if (!existingMoves.isEmpty()) {
      log.info(TASK_LOG_ONLY, STR."found \{existingMoves.size()} unfinished move tasks using blobstore '\{blobStoreName}', unable to run task '\{getName()}'";

      throw new IllegalStateException(
          STR."found unfinished move task(s) using blobstore '\{blobStoreName}', task can't be executed");
    }
  }

  @Override
  protected Object execute() throws Exception {
    checkForConflicts();

    String blobStoreName = getBlobStoreField();
    if (blobStoreManager.get(blobStoreName) instanceof BlobStore blobStore) {
      // Use virtual threads for I/O-bound compaction operation
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        executor.submit(() -> blobStore.compact(blobStoreUsageChecker)).get();
      }
    }
    else {
      log.warn(STR."Unable to find blob store: \{blobStoreName}");
    }
    return null;
  }

  @Override
  public String getMessage() {
    return STR."Compacting \{getBlobStoreField()} blob store";
  }

  private String getBlobStoreField() {
    return getConfiguration().getString(BLOB_STORE_NAME_FIELD_ID);
  }
}
