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
package org.sonatype.nexus.repository.content.internal;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.lifecycle.LifecycleSupport;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.UPGRADE;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Checks database integrity at application startup and configures automated repairs
 * using Java 21 Virtual Threads for concurrent execution.
 */
@Named
@Singleton
@ManagedLifecycle(phase = UPGRADE)
public class DatabaseIntegrityCheckService
    extends LifecycleSupport
{
  private final DataSessionSupplier dataSessionSupplier;

  private final List<DatabaseIntegrityChecker> databaseIntegrityCheckers;

  @Inject
  public DatabaseIntegrityCheckService(
      final DataSessionSupplier dataSessionSupplier,
      final List<DatabaseIntegrityChecker> databaseIntegrityCheckers)
  {
    this.dataSessionSupplier = checkNotNull(dataSessionSupplier);
    this.databaseIntegrityCheckers = checkNotNull(databaseIntegrityCheckers);
  }

  @Override
  protected void doStart() throws Exception {
    log.info(STR."Starting database integrity checks with \{databaseIntegrityCheckers.size()} checkers");
    
    // Use Virtual Threads executor for concurrent execution of database integrity checks
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      List<Exception> exceptions = new ArrayList<>();
      
      // Submit each checker as a separate task to the executor
      for (DatabaseIntegrityChecker checker : databaseIntegrityCheckers) {
        futures.add(executor.submit(() -> {
          // Each Virtual Thread gets its own connection to avoid transaction conflicts
          try (Connection connection = dataSessionSupplier.openConnection(DEFAULT_DATASTORE_NAME)) {
            log.debug(STR."Running integrity check with \{checker.getClass().getSimpleName()}");
            checker.checkAndRepair(connection);
            log.debug(STR."Completed integrity check with \{checker.getClass().getSimpleName()}");
          } catch (Exception e) {
            log.error(STR."Error during database integrity check with \{checker.getClass().getSimpleName()}: \{e.getMessage()}", e);
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (ExecutionException e) {
          // Unwrap the actual exception
          Throwable cause = e.getCause();
          if (cause instanceof Exception) {
            synchronized (exceptions) {
              exceptions.add((Exception) cause);
            }
          } else {
            synchronized (exceptions) {
              exceptions.add(new Exception(STR."Unexpected error: \{cause}", cause));
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new Exception("Database integrity check interrupted", e);
        }
      }
      
      // If any exceptions occurred, throw a combined exception
      if (!exceptions.isEmpty()) {
        if (exceptions.size() == 1) {
          throw exceptions.get(0);
        } else {
          Exception combined = new Exception(STR."Multiple database integrity check failures (\{exceptions.size()})");
          for (Exception e : exceptions) {
            combined.addSuppressed(e);
          }
          throw combined;
        }
      }
      
      log.info(STR."Successfully completed all \{databaseIntegrityCheckers.size()} database integrity checks");
    }
  }
}