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
package org.sonatype.nexus.blobstore.deletetemp;

import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.blobstore.deletetemp.DeleteBlobstoreTempFilesTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;
import static org.sonatype.nexus.blobstore.deletetemp.DeleteBlobstoreTempFilesTaskDescriptor.DAYS_OLDER_THAN;

/**
 * Task to delete temporary files from a blob store.
 * 
 * @since 3.0
 */
@Named
public class DeleteBlobstoreTempFilesTask
    extends TaskSupport
    implements Cancelable
{
  private final BlobStoreManager blobStoreManager;

  @Inject
  public DeleteBlobstoreTempFilesTask(final BlobStoreManager blobStoreManager)
  {
    this.blobStoreManager = checkNotNull(blobStoreManager);
  }

  @Override
  protected Object execute() throws Exception {
    String blobStoreName = getBlobStoreField();
    BlobStore blobStore = blobStoreManager.get(blobStoreName);
    
    if (blobStore != null) {
      String daysOlderThanStr = getDaysOlderThan();
      int daysOlderThan = switch(daysOlderThanStr) {
        case null -> 0;
        default -> Integer.parseInt(daysOlderThanStr);
      };
      
      // Use virtual threads for I/O-bound file deletion operations
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        executor.submit(() -> {
          blobStore.deleteTempFiles(daysOlderThan);
          return null;
        }).get(); // Wait for completion
      }
    }
    else {
      log.warn(STR."Unable to find blob store: \{blobStoreName}");
    }
    return null;
  }

  @Override
  public String getMessage() {
    return STR."Deleting \{getBlobStoreField()} blob store temporary files";
  }

  private String getBlobStoreField() {
    return getConfiguration().getString(BLOB_STORE_NAME_FIELD_ID);
  }

  private String getDaysOlderThan() {
    return getConfiguration().getString(DAYS_OLDER_THAN);
  }
}