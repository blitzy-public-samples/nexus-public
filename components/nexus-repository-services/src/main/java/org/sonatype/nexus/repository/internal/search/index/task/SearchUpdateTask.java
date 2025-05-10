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

import javax.inject.Inject;
import javax.inject.Named;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.index.RebuildIndexTaskDescriptor;
import org.sonatype.nexus.repository.search.index.SearchIndexFacet;
import org.sonatype.nexus.repository.search.index.SearchUpdateService;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.TaskSupport;
import org.sonatype.nexus.thread.internal.MDCUtils;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.ElasticsearchException;

import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.repository.RepositoryTaskSupport.ALL_REPOSITORIES;
import static org.sonatype.nexus.repository.internal.search.index.task.SearchUpdateTaskDescriptor.REPOSITORY_NAMES_FIELD_ID;

/**
 * Task that updates repository search indexes that are out of date.
 * 
 * Uses Java 21 Virtual Threads for concurrent repository indexing operations.
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
    int totalRepositories = repositoryNames.length;
    
    if (totalRepositories == 0) {
      log.info("No repositories specified for index update");
      return null;
    }
    
    // Track progress with atomic counter for thread safety
    AtomicInteger completedCount = new AtomicInteger(0);
    
    // Create a list to hold all the futures
    List<CompletableFuture<Void>> futures = new ArrayList<>(totalRepositories);
    
    // Capture the current MDC context for propagation to virtual threads
    final java.util.Map<String, String> mdcContext = MDCUtils.getCopyOfContextMap();
    
    // Use structured concurrency with try-with-resources for proper executor lifecycle management
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      
      // Submit each repository indexing task as a CompletableFuture
      for (String name : repositoryNames) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          // Restore MDC context in the virtual thread
          MDCUtils.setContextMap(mdcContext);
          
          Repository repository = repositoryManager.get(name);
          if (repository != null) {
            try {
              log.info("Updating search index for repo {}", name);
              SearchIndexFacet searchIndexFacet = repository.facet(SearchIndexFacet.class);
              searchIndexFacet.rebuildIndex();
              searchUpdateService.doneReindexing(repository);
              
              // Update progress counter and log completion
              int completed = completedCount.incrementAndGet();
              log.info("Completed update of search index for repo {} ({} of {})", 
                  name, completed, totalRepositories);
            } catch (ElasticsearchException e) {
              log.error("Could not perform search index update for repo {}, {}", name, e.getMessage());
              throw e; // Re-throw to be handled by exceptionally
            }
          } else {
            log.warn("Repository {} not found, skipping index update", name);
            completedCount.incrementAndGet();
          }
        }, executor).exceptionally(ex -> {
          // Handle exceptions from the async task
          if (!(ex.getCause() instanceof ElasticsearchException)) {
            log.error("Unexpected error updating search index for repo {}", name, ex);
          }
          return null;
        });
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      log.info("Completed search index update for {} repositories", completedCount.get());
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