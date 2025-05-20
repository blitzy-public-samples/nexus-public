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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.annotation.PreDestroy;
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
  private final ExecutorService executorService;

  public ReplicationVirtualThreadManager() {
    this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    log.info("Initialized ReplicationVirtualThreadManager with Virtual Threads support");
  }

  /**
   * Submits a task to be executed asynchronously using a Virtual Thread.
   *
   * @param task the task to execute
   * @return a CompletableFuture representing the pending completion of the task
   */
  public CompletableFuture<Void> submitTask(Runnable task) {
    checkNotNull(task);
    return CompletableFuture.runAsync(task, executorService);
  }

  /**
   * Submits a task that returns a result to be executed asynchronously using a Virtual Thread.
   *
   * @param <T> the type of the task's result
   * @param supplier the function returning the result
   * @return a CompletableFuture representing the pending completion of the task
   */
  public <T> CompletableFuture<T> submitTask(Supplier<T> supplier) {
    checkNotNull(supplier);
    return CompletableFuture.supplyAsync(supplier, executorService);
  }

  /**
   * Checks if the current thread is a Virtual Thread.
   *
   * @return true if the current thread is a Virtual Thread, false otherwise
   */
  public boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Shuts down the executor service when the component is destroyed.
   */
  @PreDestroy
  public void shutdown() {
    log.info("Shutting down ReplicationVirtualThreadManager");
    executorService.shutdown();
  }
}