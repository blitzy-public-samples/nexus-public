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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

/**
 * Implementation of {@link BlobStoreMetricsStore} that uses Java 21 Virtual Threads for I/O-bound operations.
 * Virtual Threads provide improved scalability for I/O-bound operations with minimal resource usage.
 * 
 * <p>Note: Write operations (updateMetrics, remove, clearOperationMetrics, clearCountMetrics, initializeMetrics)
 * are executed asynchronously using Virtual Threads. These methods return immediately without waiting for
 * the operation to complete. Read operations (get) are executed synchronously in the current thread.</p>
 * 
 * @since 3.60
 */
@Named
@Singleton
public class BlobStoreMetricsStoreImpl
    extends ConfigStoreSupport<BlobStoreMetricsDAO>
    implements BlobStoreMetricsStore
{
  private final ExecutorService virtualThreadExecutor;

  @Inject
  protected BlobStoreMetricsStoreImpl(
      final DataSessionSupplier sessionSupplier)
  {
    super(sessionSupplier, BlobStoreMetricsDAO.class);
    // Create a virtual thread executor for I/O-bound operations
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @PreDestroy
  public void shutdown() {
    virtualThreadExecutor.close();
  }

  @Override
  public void updateMetrics(final BlobStoreMetricsEntity blobStoreMetricsEntity) {
    // Execute I/O-bound operation in a virtual thread
    CompletableFuture.runAsync(() -> {
      doUpdateMetrics(blobStoreMetricsEntity);
    }, virtualThreadExecutor);
  }

  @Transactional
  protected void doUpdateMetrics(final BlobStoreMetricsEntity blobStoreMetricsEntity) {
    dao().updateMetrics(blobStoreMetricsEntity);
  }

  @Override
  @Transactional
  public BlobStoreMetricsEntity get(final String blobStoreName) {
    // Read operations are executed in the current thread with @Transactional
    return dao().get(blobStoreName);
  }

  @Override
  public void remove(final String blobStoreName) {
    // Execute I/O-bound operation in a virtual thread
    CompletableFuture.runAsync(() -> {
      doRemove(blobStoreName);
    }, virtualThreadExecutor);
  }

  @Transactional
  protected void doRemove(final String blobStoreName) {
    dao().remove(blobStoreName);
  }

  @Override
  public void clearOperationMetrics(final String blobStoreName) {
    // Execute I/O-bound operation in a virtual thread
    CompletableFuture.runAsync(() -> {
      doClearOperationMetrics(blobStoreName);
    }, virtualThreadExecutor);
  }

  @Transactional
  protected void doClearOperationMetrics(final String blobStoreName) {
    dao().clearOperationMetrics(blobStoreName);
  }

  @Override
  public void clearCountMetrics(final String blobStoreName) {
    // Execute I/O-bound operation in a virtual thread
    CompletableFuture.runAsync(() -> {
      doClearCountMetrics(blobStoreName);
    }, virtualThreadExecutor);
  }

  @Transactional
  protected void doClearCountMetrics(final String blobStoreName) {
    dao().clearCountMetrics(blobStoreName);
  }

  @Override
  public void initializeMetrics(String blobStoreName) {
    // Execute I/O-bound operation in a virtual thread
    CompletableFuture.runAsync(() -> {
      doInitializeMetrics(blobStoreName);
    }, virtualThreadExecutor);
  }

  @Transactional
  protected void doInitializeMetrics(String blobStoreName) {
    try {
      if (dao().get(blobStoreName) == null) {
        dao().initializeMetrics(blobStoreName);
      }
    }
    catch (DuplicateKeyException e) {
      // Using Java 21 String Templates for improved structured logging
      log.debug(STR."Failed to initialize blobstore metrics for '\{blobStoreName}' as they are already initialized.", 
          e); // this is likely an HA race condition between multiple nodes - this is not a problem
    }
  }
}