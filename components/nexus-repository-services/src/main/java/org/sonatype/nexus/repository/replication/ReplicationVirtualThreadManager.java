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
package org.sonatype.nexus.repository.replication;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Manager for Virtual Threads used in replication operations.
 * 
 * @since 3.60
 */
@Named
@Singleton
public class ReplicationVirtualThreadManager
    extends ComponentSupport
{
  private final Executor virtualThreadExecutor;

  public ReplicationVirtualThreadManager() {
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    log.info("Initialized ReplicationVirtualThreadManager with Virtual Thread executor");
  }

  /**
   * Executes the given task asynchronously using a Virtual Thread.
   *
   * @param task the task to execute
   */
  public void executeAsync(Runnable task) {
    checkNotNull(task);
    virtualThreadExecutor.execute(task);
  }

  /**
   * Executes the given supplier asynchronously using a Virtual Thread.
   * This is useful when you need to return a value from the async operation.
   *
   * @param supplier the supplier to execute
   * @param <T> the type of result
   */
  public <T> void executeAsync(Supplier<T> supplier) {
    checkNotNull(supplier);
    virtualThreadExecutor.execute(() -> supplier.get());
  }

  /**
   * Executes the given task with the provided blob ID asynchronously using a Virtual Thread.
   * This method logs the start and completion of the task with the blob ID for better traceability.
   *
   * @param blobId the blob ID associated with the task
   * @param task the task to execute
   */
  public void executeReplicationTask(String blobId, Runnable task) {
    checkNotNull(blobId);
    checkNotNull(task);
    
    virtualThreadExecutor.execute(() -> {
      try {
        log.debug("Starting async replication task for blob {}", blobId);
        task.run();
        log.debug("Completed async replication task for blob {}", blobId);
      }
      catch (Exception e) {
        log.error("Error in async replication task for blob {}: {}", blobId, e.getMessage(), e);
        throw e;
      }
    });
  }
}