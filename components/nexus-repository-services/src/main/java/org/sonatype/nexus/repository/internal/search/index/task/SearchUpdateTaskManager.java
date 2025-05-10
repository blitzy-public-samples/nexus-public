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
package org.sonatype.nexus.repository.internal.search.index.task;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.index.SearchUpdateService;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskScheduler;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.TASKS;
import static org.sonatype.nexus.repository.internal.search.index.task.SearchUpdateTaskDescriptor.REPOSITORY_NAMES_FIELD_ID;

/**
 * Ad-hoc "manager" class that checks to see if any repository indexes are out of date, missed or need to be updated and
 * schedules a task to do so.
 *
 * @since 3.37
 */
@Named
@ManagedLifecycle(phase = TASKS)
@Singleton
public class SearchUpdateTaskManager
    extends StateGuardLifecycleSupport
{
  private final TaskScheduler taskScheduler;

  private final RepositoryManager repositoryManager;

  private final boolean enabled;

  private final SearchUpdateService searchUpdateService;

  private PeriodicJobService periodicJobService;

  @Inject
  public SearchUpdateTaskManager(
      final TaskScheduler taskScheduler,
      final RepositoryManager repositoryManager,
      final SearchUpdateService searchUpdateService,
      final PeriodicJobService periodicJobService,
      @Named("${nexus.search.updateIndexesOnStartup.enabled:-true}") final boolean enabled)
  {
    this.taskScheduler = requireNonNull(taskScheduler);
    this.repositoryManager = requireNonNull(repositoryManager);
    this.searchUpdateService = requireNonNull(searchUpdateService);
    this.periodicJobService = checkNotNull(periodicJobService);
    this.enabled = enabled;
  }

  @Override
  protected void doStart() {
    if (!enabled) {
      return;
    }
    periodicJobService.runOnce(this::maybeScheduleReIndex, 0);
  }

  private void maybeScheduleReIndex() {
    try {
      // Use a thread-safe collection to gather repositories that need reindexing
      CopyOnWriteArrayList<String> reindexList = new CopyOnWriteArrayList<>();
      
      // Create a virtual thread executor for parallel processing
      try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Use parallel stream with virtual threads for repository scanning
        repositoryManager.browse().forEach(repository -> {
          virtualExecutor.submit(() -> {
            try {
              // Log with virtual thread context information
              if (log.isDebugEnabled()) {
                log.debug("Checking if repository {} needs reindexing on {}", 
                    repository.getName(), Thread.currentThread());
              }
              
              // Check if repository needs reindexing
              if (searchUpdateService.needsReindex(repository)) {
                reindexList.add(repository.getName());
                if (log.isDebugEnabled()) {
                  log.debug("Repository {} needs reindexing", repository.getName());
                }
              }
            } catch (Exception e) {
              // Handle exceptions in virtual thread processing
              log.error("Error checking if repository {} needs reindexing: {}", 
                  repository.getName(), e.getMessage(), e);
            }
          });
        });
        
        // Wait for all virtual threads to complete (with timeout)
        virtualExecutor.shutdown();
        if (!virtualExecutor.awaitTermination(5, TimeUnit.MINUTES)) {
          log.warn("Repository scanning timed out after 5 minutes");
          virtualExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Repository scanning was interrupted", e);
      } catch (Exception e) {
        log.error("Error during parallel repository scanning", e);
      }

      if (!reindexList.isEmpty()) {
        boolean existingTask = taskScheduler.findAndSubmit(SearchUpdateTaskDescriptor.TYPE_ID);
        if (!existingTask) {
          runSearchUpdateTaskForRepositories(reindexList);
        }
      }
    }
    catch (Exception e) {
      log.error("Failed to determine if any repository indexes needed to be updated", e);
    }
  }

  private void runSearchUpdateTaskForRepositories(final List<String> repositories) {
    String repositoriesCsv = String.join(",", repositories);
    TaskConfiguration configuration = taskScheduler
        .createTaskConfigurationInstance(SearchUpdateTaskDescriptor.TYPE_ID);
    configuration.setString(REPOSITORY_NAMES_FIELD_ID, repositoriesCsv);
    configuration.setName("Update repository indexes - (" + repositoriesCsv + ")");
    taskScheduler.submit(configuration);
  }
}