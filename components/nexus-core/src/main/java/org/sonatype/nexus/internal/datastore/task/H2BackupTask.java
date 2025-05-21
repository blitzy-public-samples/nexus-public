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
package org.sonatype.nexus.internal.datastore.task;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.scheduling.Task;
import org.sonatype.nexus.scheduling.TaskSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * A {@link Task} for backing up an embedded H2 datastore.
 * <p>
 * Uses Java 21 Virtual Threads for improved I/O performance during backup operations.
 * Compatible with H2 database driver version 2.2.224+ for Java 21.
 *
 * @since 3.21
 */
@Named
public class H2BackupTask
    extends TaskSupport
{
  private final DataStoreManager dataStoreManager;

  private final ApplicationDirectories applicationDirectories;

  @Inject
  public H2BackupTask(final DataStoreManager dataStoreManager, final ApplicationDirectories applicationDirectories) {
    this.dataStoreManager = checkNotNull(dataStoreManager);
    this.applicationDirectories = checkNotNull(applicationDirectories);
  }

  @Override
  public String getMessage() {
    return "Backup embedded h2 database to the specified location";
  }

  @Override
  protected Object execute() throws Exception {
    Optional<DataStore<?>> dataStore = dataStoreManager.get(DEFAULT_DATASTORE_NAME);

    if (!dataStore.isPresent()) {
      throw new RuntimeException(STR."Unable to locate datastore with name \{DEFAULT_DATASTORE_NAME}");
    }

    File backupFolder = applicationDirectories.getWorkDirectory(checkNotNull(
        getConfiguration().getString(H2BackupTaskDescriptor.LOCATION), 
        "Backup location not configured"));
    File backupFile = new File(backupFolder, getBackupFileName());

    if(backupFile.isFile()){
      throw new IOException(STR."File already exists at backup file location: \{backupFile.getAbsolutePath()}");
    }

    log.info(STR."Starting backup of \{DEFAULT_DATASTORE_NAME} to \{backupFile.getAbsolutePath()}");

    long start = System.currentTimeMillis();

    // Use structured concurrency with virtual threads for the backup operation
    try (ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Fork a virtual thread to perform the backup operation
      scope.fork(() -> {
        dataStore.get().backup(backupFile.getAbsolutePath());
        return null;
      });
      
      // Wait for the backup to complete or fail
      scope.join();
      // Propagate any exceptions that occurred during backup
      scope.throwIfFailed(e -> new RuntimeException(STR."Backup failed: \{e.getMessage()}", e));
    }

    long duration = System.currentTimeMillis() - start;
    log.info(STR."Completed backup of \{DEFAULT_DATASTORE_NAME} in \{duration} ms");

    return null;
  }

  private String getBackupFileName() {
    return STR."\{DEFAULT_DATASTORE_NAME}-\{String.format(TIMESTAMP_FORMAT, LocalDateTime.now())}.zip";
  }
}