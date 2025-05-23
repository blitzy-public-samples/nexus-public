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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Manages Virtual Thread executors for replication operations.
 * 
 * @since 3.60
 */
@Named
@Singleton
public class ReplicationVirtualThreadManager
    extends ComponentSupport
{
  private ExecutorService virtualThreadExecutor;
  
  private final AtomicInteger activeTaskCount = new AtomicInteger(0);
  
  /**
   * Initialize the Virtual Thread executor.
   */
  @PostConstruct
  public void start() {
    log.info("Starting ReplicationVirtualThreadManager with Java 21 Virtual Threads");
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Shutdown the Virtual Thread executor.
   */
  @PreDestroy
  public void stop() {
    log.info("Stopping ReplicationVirtualThreadManager");
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
  }
  
  /**
   * Submit a task to be executed on a Virtual Thread.
   * 
   * @param task the task to execute
   * @param <T> the type of the task's result
   * @return a Future representing pending completion of the task
   */
  public <T> Future<T> submit(final Callable<T> task) {
    checkNotNull(task);
    activeTaskCount.incrementAndGet();
    return virtualThreadExecutor.submit(() -> {
      try {
        return task.call();
      }
      finally {
        activeTaskCount.decrementAndGet();
      }
    });
  }
  
  /**
   * Submit a task to be executed on a Virtual Thread.
   * 
   * @param task the task to execute
   * @return a Future representing pending completion of the task
   */
  public Future<?> submit(final Runnable task) {
    checkNotNull(task);
    activeTaskCount.incrementAndGet();
    return virtualThreadExecutor.submit(() -> {
      try {
        task.run();
      }
      finally {
        activeTaskCount.decrementAndGet();
      }
    });
  }
  
  /**
   * Execute a task on a Virtual Thread and return the result.
   * This method blocks until the task completes.
   * 
   * @param supplier the supplier to execute
   * @param <T> the type of the result
   * @return the result of the supplier
   */
  public <T> T execute(final Supplier<T> supplier) {
    checkNotNull(supplier);
    activeTaskCount.incrementAndGet();
    try {
      return supplier.get();
    }
    finally {
      activeTaskCount.decrementAndGet();
    }
  }
  
  /**
   * Execute a task on a Virtual Thread.
   * This method blocks until the task completes.
   * 
   * @param runnable the runnable to execute
   */
  public void execute(final Runnable runnable) {
    checkNotNull(runnable);
    activeTaskCount.incrementAndGet();
    try {
      runnable.run();
    }
    finally {
      activeTaskCount.decrementAndGet();
    }
  }
  
  /**
   * Get the current number of active replication tasks.
   * 
   * @return the number of active tasks
   */
  public int getActiveTaskCount() {
    return activeTaskCount.get();
  }
  
  /**
   * Check if the Virtual Thread executor is available.
   * 
   * @return true if the executor is available, false otherwise
   */
  public boolean isAvailable() {
    return virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown();
  }
}