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
package org.sonatype.nexus.testsuite.testsupport.blobstore.restore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.sonatype.nexus.scheduling.TaskState.OK;

/**
 * Support class for blobstore restoration testing that leverages Java 21 virtual threads
 * for improved concurrency and performance during task execution.
 */
public abstract class BlobstoreRestoreTestHelperSupport
    implements BlobstoreRestoreTestHelper
{
  @Inject
  private TaskScheduler taskScheduler;

  /**
   * Runs a restore metadata task with the specified timeout using virtual threads for improved concurrency.
   * 
   * @param blobstoreName the name of the blobstore to restore
   * @param timeout the maximum time to wait for task completion in seconds
   * @param dryRun whether to perform a dry run
   */
  @Override
  public void runRestoreMetadataTaskWithTimeout(final String blobstoreName, final long timeout, final boolean dryRun) {
    // Create task configuration
    TaskConfiguration config = taskScheduler.createTaskConfigurationInstance(TYPE_ID);
    config.setEnabled(true);
    config.setName("restore");
    config.setString(BLOB_STORE_NAME_FIELD_ID, blobstoreName);
    config.setBoolean(DRY_RUN, dryRun);
    config.setBoolean(RESTORE_BLOBS, true);
    config.setBoolean(UNDELETE_BLOBS, false);
    config.setBoolean(INTEGRITY_CHECK, false);
    
    // Submit task and wait for completion using virtual thread
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        TaskInfo taskInfo = taskScheduler.submit(config);
        await().pollDelay(100, SECONDS.MILLISECONDS)
            .atMost(timeout, SECONDS)
            .until(() -> taskInfo.getLastRunState() != null && 
                   taskInfo.getLastRunState().getEndState().equals(OK));
      }).get(); // Ensure the virtual thread completes
    } catch (Exception e) {
      throw new RuntimeException("Error executing restore metadata task", e);
    }
  }

  /**
   * Runs a reconcile task with the specified timeout using virtual threads for improved concurrency.
   * This method executes both planning and execution phases of reconciliation.
   * 
   * @param blobstoreName the name of the blobstore to reconcile
   * @param timeout the maximum time to wait for task completion in seconds
   */
  @Override
  public void runReconcileTaskWithTimeout(final String blobstoreName, final long timeout) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Run planning phase
      executor.submit(() -> {
        TaskConfiguration planingConfig = taskScheduler.createTaskConfigurationInstance(PLAN_RECONCILE_TYPE_ID);
        planingConfig.setString(BLOB_STORE_NAME_FIELD_ID, blobstoreName);
        TaskInfo planingTaskInfo = taskScheduler.submit(planingConfig);
        
        await().pollDelay(100, SECONDS.MILLISECONDS)
            .atMost(timeout, SECONDS)
            .until(() -> planingTaskInfo.getLastRunState() != null &&
                   planingTaskInfo.getLastRunState().getEndState().equals(OK));
      }).get(); // Ensure the planning phase completes before execution phase
      
      // Run execution phase
      executor.submit(() -> {
        TaskConfiguration executeConfig = taskScheduler.createTaskConfigurationInstance(EXECUTE_RECONCILE_TYPE_ID);
        executeConfig.setString(BLOB_STORE_NAME_FIELD_ID, blobstoreName);
        TaskInfo executeTaskInfo = taskScheduler.submit(executeConfig);
        
        await().pollDelay(100, SECONDS.MILLISECONDS)
            .atMost(timeout, SECONDS)
            .until(() -> executeTaskInfo.getLastRunState() != null &&
                   executeTaskInfo.getLastRunState().getEndState().equals(OK));
      }).get(); // Ensure the execution phase completes
    } catch (Exception e) {
      throw new RuntimeException("Error executing reconcile task", e);
    }
  }
}