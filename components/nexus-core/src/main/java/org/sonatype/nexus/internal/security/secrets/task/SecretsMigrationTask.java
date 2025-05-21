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
package org.sonatype.nexus.internal.security.secrets.task;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.logging.task.TaskLogType;
import org.sonatype.nexus.logging.task.TaskLogging;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskSupport;
import org.sonatype.nexus.security.secrets.SecretsMigrator;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Task to migrate secrets from external sources to a single source of truth
 * using Java 21 Virtual Threads for parallel migration operations.
 */
@Named
@TaskLogging(TaskLogType.TASK_LOG_ONLY)
public class SecretsMigrationTask
    extends TaskSupport
    implements Cancelable
{
  private final List<SecretsMigrator> migrators;
  private final AtomicBoolean canceled = new AtomicBoolean(false);

  @Inject
  public SecretsMigrationTask(final List<SecretsMigrator> migrators) {
    this.migrators = checkNotNull(migrators);
  }

  @Override
  public String getMessage() {
    return STR."Migrate existing secrets into a single source (secrets table). Processing \{migrators.size()} migrators.";
  }

  @Override
  protected Object execute() throws Exception {
    if (migrators.isEmpty()) {
      log.info(STR."No secret migrators found, skipping migration.");
      return null;
    }
    
    log.info(STR."Starting secrets migration with \{migrators.size()} migrators using Virtual Threads.");
    
    // Use Java 21 Virtual Threads for I/O-bound migration operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit all migrators to run in parallel
      List<Future<?>> futures = migrators.stream()
          .map(migrator -> executor.submit(() -> {
            try {
              if (!canceled.get()) {
                String migratorName = migrator.getClass().getSimpleName();
                log.info(STR."Starting migration using \{migratorName}");
                migrator.migrate();
                log.info(STR."Completed migration using \{migratorName}");
              }
            } 
            catch (InterruptedException e) {
              // Preserve interruption status for proper cancellation
              Thread.currentThread().interrupt();
              log.warn(STR."Migration interrupted: \{e.getMessage()}");
              throw e;
            }
            catch (Exception e) {
              log.error(STR."Migration failed: \{e.getMessage()}", e);
              throw e;
            }
          }))
          .toList();
      
      // Wait for all migrations to complete
      for (Future<?> future : futures) {
        try {
          future.get();
        }
        catch (InterruptedException e) {
          // Preserve interruption status
          Thread.currentThread().interrupt();
          log.warn(STR."Task interrupted, cancelling remaining migrations.");
          cancelMigrations();
          throw e;
        }
        catch (Exception e) {
          log.error(STR."Migration task failed: \{e.getMessage()}", e);
          // Continue with other migrations even if one fails
        }
      }
    }
    
    log.info(STR."Secrets migration completed.");
    return null;
  }
  
  @Override
  public boolean cancel() {
    cancelMigrations();
    return true;
  }
  
  private void cancelMigrations() {
    canceled.set(true);
    log.info(STR."Cancelling secrets migration task.");
  }
}