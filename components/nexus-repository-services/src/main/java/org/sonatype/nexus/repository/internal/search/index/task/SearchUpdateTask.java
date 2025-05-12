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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.index.RebuildIndexTaskDescriptor;
import org.sonatype.nexus.repository.search.index.SearchIndexFacet;
import org.sonatype.nexus.repository.search.index.SearchUpdateService;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.TaskSupport;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.ElasticsearchException;

import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.repository.RepositoryTaskSupport.ALL_REPOSITORIES;
import static org.sonatype.nexus.repository.internal.search.index.task.SearchUpdateTaskDescriptor.REPOSITORY_NAMES_FIELD_ID;

/**
 * Task that updates repository search indexes that are out of date.
 *
 * @since 3.37
 */
@Named
public class SearchUpdateTask
    extends TaskSupport
    implements Cancelable
{
  private final RepositoryManager repositoryManager;

  private final SearchUpdateService searchUpdateService;

  private final TaskScheduler taskScheduler;

  @Inject
  public SearchUpdateTask(final RepositoryManager repositoryManager,
                          final SearchUpdateService searchUpdateService,
                          final TaskScheduler taskScheduler) {
    this.repositoryManager = requireNonNull(repositoryManager);
    this.searchUpdateService = requireNonNull(searchUpdateService);
    this.taskScheduler = taskScheduler;
  }

  @Override
  protected Object execute() {
    if (taskScheduler.findWaitingTask(RebuildIndexTaskDescriptor.TYPE_ID,
        ImmutableMap.of(RebuildIndexTaskDescriptor.REPOSITORY_NAME_FIELD_ID, ALL_REPOSITORIES))) {
      log.info("A task of type {} is already scheduled for all repositories, cancelling task {} ",
          RebuildIndexTaskDescriptor.TYPE_ID, SearchUpdateTaskDescriptor.TYPE_ID);
      taskScheduler.cancel(SearchUpdateTaskDescriptor.TYPE_ID, true);

      return null;
    }

    String[] repositoryNames = getRepositoryNamesField();

    // Use try-with-resources for proper virtual thread executor lifecycle management
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      AtomicInteger completedCount = new AtomicInteger(0);
      AtomicInteger totalCount = new AtomicInteger(repositoryNames.length);
      
      // Create an array to hold all the futures
      CompletableFuture<?>[] futures = new CompletableFuture[repositoryNames.length];
      
      // Process each repository concurrently
      for (int i = 0; i < repositoryNames.length; i++) {
        final String name = repositoryNames[i];
        
        // Use runAsync to execute each repository indexing task in a separate virtual thread
        futures[i] = CompletableFuture.runAsync(() -> {
          Repository repository = repositoryManager.get(name);
          if (repository != null) {
            try {
              // Capture thread-local context for logging
              log.info("Updating search index for repo {}", name);
              SearchIndexFacet searchIndexFacet = repository.facet(SearchIndexFacet.class);
              searchIndexFacet.rebuildIndex();
              searchUpdateService.doneReindexing(repository);
              log.info("Completed update of search index for repo {} ({}/{})", 
                  name, completedCount.incrementAndGet(), totalCount.get());
            } catch (ElasticsearchException e) {
              log.error("Could not perform search index update for repo {}, {}", name, e.getMessage());
            } catch (Exception e) {
              log.error("Unexpected error during search index update for repo {}", name, e);
            }
          }
        }, executor).exceptionally(throwable -> {
          // Enhanced exception handling for concurrent execution contexts
          log.error("Failed to process repository {}: {}", name, throwable.getMessage(), throwable);
          completedCount.incrementAndGet();
          return null;
        });
      }
      
      // Wait for all futures to complete using CompletableFuture.allOf
      CompletableFuture.allOf(futures).join();
      
      log.info("Completed all search index updates for {} repositories", completedCount.get());
    }

    return null;
  }

  @Override
  public String getMessage() {
    return "Updating search indexes of " + String.join(",", getRepositoryNamesField());
  }

  /**
   * Extract repository field out of configuration.
   */
  private String[] getRepositoryNamesField() {
    String value = getConfiguration().getString(REPOSITORY_NAMES_FIELD_ID);
    if (value != null) {
      return value.split(",");
    } else {
      return new String[]{};
    }
  }
}