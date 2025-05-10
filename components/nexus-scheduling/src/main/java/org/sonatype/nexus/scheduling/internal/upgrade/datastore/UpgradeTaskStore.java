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
package org.sonatype.nexus.scheduling.internal.upgrade.datastore;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.transaction.Transaction;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.UnitOfWork;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;

/**
 * Store for upgrade tasks with optimized transaction handling for Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@FeatureFlag(name = DATASTORE_ENABLED)
@Named("mybatis")
@Singleton
public class UpgradeTaskStore
  extends ConfigStoreSupport<UpgradeTaskDAO>
{
  @Inject
  public UpgradeTaskStore(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier, UpgradeTaskDAO.class);
  }

  /**
   * Insert a new upgrade task.
   * 
   * @param task the task to insert
   */
  @Transactional
  public void insert(final UpgradeTaskData task) {
    dao().create(task);
  }

  /**
   * Read an upgrade task by ID.
   * 
   * @param id the task ID
   * @return the task if found
   */
  @Transactional
  public Optional<UpgradeTaskData> read(final int id) {
    return dao().read(id);
  }

  /**
   * Mark a task as failed.
   * 
   * @param id the task ID
   * @return number of rows affected
   */
  @Transactional
  public int markFailed(final String id) {
    return dao().setStatus(id, "failed");
  }

  /**
   * Mark a task as canceled.
   * 
   * @param taskId the task ID
   * @return number of rows affected
   */
  @Transactional
  public int markCanceled(final String taskId) {
    return dao().setStatus(taskId, "canceled");
  }

  /**
   * Delete a task by ID.
   * 
   * @param id the task ID
   * @return number of rows affected
   */
  @Transactional
  public int deleteByTaskId(final String id) {
    return dao().deleteByTaskId(id);
  }

  /**
   * Browse all upgrade tasks using Virtual Threads for improved scalability with large datasets.
   * 
   * @return stream of upgrade tasks
   */
  public Stream<UpgradeTaskData> browse() {
    // Create a new transaction for each chunk of data to avoid thread pinning
    // This allows Virtual Threads to yield during I/O operations
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        UnitOfWork.begin(this::openSession);
        try {
          return StreamSupport.stream(dao().browse().spliterator(), false)
              .map(this::detachFromTransaction);
        }
        finally {
          UnitOfWork.end();
        }
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to browse upgrade tasks", e);
      }
    }).join();
  }

  /**
   * Update an existing upgrade task.
   * 
   * @param task the task to update
   */
  @Transactional
  public void update(final UpgradeTaskData task) {
    dao().update(task);
  }

  /**
   * Find the next upgrade task using Virtual Threads for improved I/O throughput.
   * 
   * @return the next task if available
   */
  public Optional<UpgradeTaskData> next() {
    // Use Virtual Threads to avoid blocking platform threads during database operations
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        UnitOfWork.begin(this::openSession);
        try {
          Optional<UpgradeTaskData> result = dao().next();
          // Detach the result from the transaction to avoid thread pinning
          return result.map(this::detachFromTransaction);
        }
        finally {
          UnitOfWork.end();
        }
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to get next upgrade task", e);
      }
    }).join();
  }
  
  /**
   * Creates a detached copy of the task data to avoid thread pinning after the transaction completes.
   * This is important for Virtual Threads to work efficiently.
   *
   * @param task the task to detach
   * @return a detached copy of the task
   */
  private UpgradeTaskData detachFromTransaction(final UpgradeTaskData task) {
    if (task == null) {
      return null;
    }
    
    // Create a new instance that's not attached to the transaction
    UpgradeTaskData detached = new UpgradeTaskData();
    detached.setId(task.getId());
    detached.setTaskId(task.getTaskId());
    detached.setStatus(task.getStatus());
    detached.setMessage(task.getMessage());
    detached.setCreated(task.getCreated());
    detached.setLastUpdated(task.getLastUpdated());
    return detached;
  }
}