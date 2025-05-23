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
package org.sonatype.nexus.repository.content.blobstore.metrics;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsEntity;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsStore;
import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.UnitOfWork;

/**
 * Implementation of {@link BlobStoreMetricsStore} that uses Java 21 Virtual Threads
 * for I/O-bound metric operations to improve scalability and performance.
 */
@Named
@Singleton
public class BlobStoreMetricsStoreImpl
    extends ConfigStoreSupport<BlobStoreMetricsDAO>
    implements BlobStoreMetricsStore
{
  /**
   * ExecutorService that creates a new virtual thread for each I/O-bound task.
   * Virtual threads are lightweight and efficient for operations that spend most of their time waiting on I/O.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  protected BlobStoreMetricsStoreImpl(
      final DataSessionSupplier sessionSupplier)
  {
    super(sessionSupplier, BlobStoreMetricsDAO.class);
  }

  /**
   * Properly shut down the virtual thread executor when the bean is destroyed.
   */
  @PreDestroy
  public void shutdown() {
    virtualThreadExecutor.shutdown();
  }

  @Override
  @Transactional
  public void updateMetrics(final BlobStoreMetricsEntity blobStoreMetricsEntity) {
    dao().updateMetrics(blobStoreMetricsEntity);
  }

  /**
   * Asynchronously updates metrics using a virtual thread to avoid blocking the caller.
   * This method does not use @Transactional directly as the transaction will be managed
   * within the virtual thread to ensure proper thread-local state handling.
   *
   * @param blobStoreMetricsEntity the metrics entity to update
   * @return a CompletableFuture that completes when the update is done
   */
  public CompletableFuture<Void> updateMetricsAsync(final BlobStoreMetricsEntity blobStoreMetricsEntity) {
    return CompletableFuture.runAsync(() -> {
      UnitOfWork.begin(sessionSupplier);
      try {
        dao().updateMetrics(blobStoreMetricsEntity);
      }
      finally {
        UnitOfWork.end();
      }
    }, virtualThreadExecutor);
  }

  @Override
  @Transactional
  public BlobStoreMetricsEntity get(final String blobStoreName) {
    return dao().get(blobStoreName);
  }

  /**
   * Asynchronously retrieves metrics using a virtual thread.
   *
   * @param blobStoreName the name of the blob store
   * @return a CompletableFuture that completes with the metrics entity
   */
  public CompletableFuture<BlobStoreMetricsEntity> getAsync(final String blobStoreName) {
    return CompletableFuture.supplyAsync(() -> {
      UnitOfWork.begin(sessionSupplier);
      try {
        return dao().get(blobStoreName);
      }
      finally {
        UnitOfWork.end();
      }
    }, virtualThreadExecutor);
  }

  @Override
  @Transactional
  public void remove(final String blobStoreName) {
    dao().remove(blobStoreName);
  }

  /**
   * Asynchronously removes metrics using a virtual thread.
   *
   * @param blobStoreName the name of the blob store
   * @return a CompletableFuture that completes when the removal is done
   */
  public CompletableFuture<Void> removeAsync(final String blobStoreName) {
    return CompletableFuture.runAsync(() -> {
      UnitOfWork.begin(sessionSupplier);
      try {
        dao().remove(blobStoreName);
      }
      finally {
        UnitOfWork.end();
      }
    }, virtualThreadExecutor);
  }

  @Override
  @Transactional
  public void clearOperationMetrics(final String blobStoreName) {
    dao().clearOperationMetrics(blobStoreName);
  }

  /**
   * Asynchronously clears operation metrics using a virtual thread.
   *
   * @param blobStoreName the name of the blob store
   * @return a CompletableFuture that completes when the operation is done
   */
  public CompletableFuture<Void> clearOperationMetricsAsync(final String blobStoreName) {
    return CompletableFuture.runAsync(() -> {
      UnitOfWork.begin(sessionSupplier);
      try {
        dao().clearOperationMetrics(blobStoreName);
      }
      finally {
        UnitOfWork.end();
      }
    }, virtualThreadExecutor);
  }

  @Override
  @Transactional
  public void clearCountMetrics(final String blobStoreName) {
    dao().clearCountMetrics(blobStoreName);
  }

  /**
   * Asynchronously clears count metrics using a virtual thread.
   *
   * @param blobStoreName the name of the blob store
   * @return a CompletableFuture that completes when the operation is done
   */
  public CompletableFuture<Void> clearCountMetricsAsync(final String blobStoreName) {
    return CompletableFuture.runAsync(() -> {
      UnitOfWork.begin(sessionSupplier);
      try {
        dao().clearCountMetrics(blobStoreName);
      }
      finally {
        UnitOfWork.end();
      }
    }, virtualThreadExecutor);
  }

  @Override
  @Transactional
  public void initializeMetrics(String blobStoreName) {
    try {
      // Use AtomicReference to ensure thread-safety when checking and initializing metrics
      AtomicReference<BlobStoreMetricsEntity> metricsRef = new AtomicReference<>(dao().get(blobStoreName));
      if (metricsRef.get() == null) {
        dao().initializeMetrics(blobStoreName);
      }
    }
    catch (DuplicateKeyException e) {
      // Using Java 21 String Templates for improved structured logging
      log.debug(STR."Failed to initialize blobstore metrics for '\{blobStoreName}' as they are already initialized.", 
          e); // this is likely an HA race condition between multiple nodes - this is not a problem
    }
  }

  /**
   * Asynchronously initializes metrics using a virtual thread with improved handling of
   * DuplicateKeyException to maintain correctness during thread handoffs.
   *
   * @param blobStoreName the name of the blob store
   * @return a CompletableFuture that completes when the initialization is done
   */
  public CompletableFuture<Void> initializeMetricsAsync(final String blobStoreName) {
    return CompletableFuture.runAsync(() -> {
      UnitOfWork.begin(sessionSupplier);
      try {
        // Use AtomicReference to ensure thread-safety when checking and initializing metrics
        AtomicReference<BlobStoreMetricsEntity> metricsRef = new AtomicReference<>();
        try {
          metricsRef.set(dao().get(blobStoreName));
          if (metricsRef.get() == null) {
            dao().initializeMetrics(blobStoreName);
          }
        }
        catch (DuplicateKeyException e) {
          // Using Java 21 String Templates for improved structured logging
          log.debug(STR."Failed to initialize blobstore metrics for '\{blobStoreName}' as they are already initialized.", 
              e); // this is likely an HA race condition between multiple nodes - this is not a problem
        }
      }
      finally {
        UnitOfWork.end();
      }
    }, virtualThreadExecutor);
  }
}