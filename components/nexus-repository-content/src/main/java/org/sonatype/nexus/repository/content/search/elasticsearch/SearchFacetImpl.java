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
package org.sonatype.nexus.repository.content.search.elasticsearch;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.lang.ScopedValue;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.logging.task.ProgressLogIntervalHelper;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.FluentComponents;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.search.index.ElasticSearchIndexService;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static org.sonatype.nexus.repository.FacetSupport.State.STARTED;
import static org.sonatype.nexus.repository.content.store.InternalIds.internalComponentId;
import static org.sonatype.nexus.repository.content.store.InternalIds.toExternalId;
import static org.sonatype.nexus.repository.search.index.SearchConstants.FORMAT;
import static org.sonatype.nexus.repository.search.index.SearchConstants.REPOSITORY_NAME;
import static org.sonatype.nexus.scheduling.CancelableHelper.checkCancellation;

/**
 * The {@link SearchFacet} implementation for the Elastic Search.
 *
 * @since 3.25
 */
@Named
public class SearchFacetImpl
    extends FacetSupport
    implements SearchFacet
{
  /**
   * ScopedValue for repository context propagation to virtual threads
   */
  private static final ScopedValue<Repository> REPOSITORY_CONTEXT = ScopedValue.newInstance();
  
  /**
   * ScopedValue for repository fields propagation to virtual threads
   */
  private static final ScopedValue<Map<String, Object>> REPOSITORY_FIELDS = ScopedValue.newInstance();
  
  private final ElasticSearchIndexService elasticSearchIndexService;

  private final Map<String, SearchDocumentProducer> searchDocumentProducersByFormat;

  private final int pageSize;

  private final boolean bulkProcessing;

  private SearchDocumentProducer searchDocumentProducer;

  private Map<String, Object> repositoryFields;

  @Inject
  public SearchFacetImpl(final ElasticSearchIndexService elasticSearchIndexService,
                         final Map<String, SearchDocumentProducer> searchDocumentProducersByFormat,
                         @Named("${nexus.elasticsearch.reindex.pageSize:-1000}") final int pageSize,
                         @Named("${nexus.elasticsearch.bulkProcessing:-true}") final boolean bulkProcessing)
  {
    this.elasticSearchIndexService = checkNotNull(elasticSearchIndexService);
    this.searchDocumentProducersByFormat = checkNotNull(searchDocumentProducersByFormat);
    this.pageSize = max(pageSize, 1);
    this.bulkProcessing = bulkProcessing;
  }

  @Override
  protected void doInit(Configuration configuration) throws Exception {
    String format = getRepository().getFormat().getValue();

    searchDocumentProducer = lookupSearchDocumentProducer(format);
    repositoryFields = ImmutableMap.of(REPOSITORY_NAME, getRepository().getName(), FORMAT, format);

    super.doInit(configuration);
  }

  @Override
  protected void doStart() throws Exception {
    elasticSearchIndexService.createIndex(getRepository());
  }

  @Override
  protected void doDelete() {
    elasticSearchIndexService.deleteIndex(getRepository());
  }

  @Guarded(by = STARTED)
  @Override
  public void index(final Collection<EntityId> componentIds) {
    FluentComponents lookup = facet(ContentFacet.class).components();
    Repository repository = getRepository();
    
    if (componentIds.isEmpty()) {
      return;
    }
    
    if (bulkProcessing) {
      // Use the existing bulk processing for large collections
      Stream<FluentComponent> components = componentIds.stream()
          .map(lookup::find)
          .filter(Optional::isPresent)
          .map(Optional::get);
      
      elasticSearchIndexService.bulkPut(repository, components::iterator, this::identifier, this::document);
    }
    else {
      // Use Virtual Threads with structured concurrency for parallel processing
      try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        // Propagate repository context to all virtual threads
        ScopedValue.where(REPOSITORY_CONTEXT, repository)
            .where(REPOSITORY_FIELDS, repositoryFields)
            .run(() -> {
              // Fork a virtual thread for each component
              componentIds.stream()
                  .map(lookup::find)
                  .filter(Optional::isPresent)
                  .map(Optional::get)
                  .forEach(component -> 
                      scope.fork(() -> {
                        elasticSearchIndexService.put(
                            REPOSITORY_CONTEXT.get(), 
                            identifier(component), 
                            document(component));
                        return null; // Required for Callable interface
                      }));
              
              // Wait for all tasks to complete and propagate any exceptions
              try {
                scope.join().throwIfFailed();
              } 
              catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Indexing interrupted for repository {}", repository.getName(), e);
              }
            });
      }
    }
  }

  @Guarded(by = STARTED)
  @Override
  public void purge(final Collection<EntityId> componentIds) {
    if (componentIds.isEmpty()) {
      return;
    }
    
    Repository repository = getRepository();
    
    if (bulkProcessing) {
      // Use the existing bulk processing for large collections
      Stream<String> identifiers = componentIds.stream()
          .map(EntityId::getValue);
      
      elasticSearchIndexService.bulkDelete(repository, identifiers::iterator);
    }
    else {
      // Use Virtual Threads with structured concurrency for concurrent deletion
      try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        // Propagate repository context to all virtual threads
        ScopedValue.where(REPOSITORY_CONTEXT, repository)
            .run(() -> {
              // Fork a virtual thread for each component ID to delete
              componentIds.stream()
                  .map(EntityId::getValue)
                  .forEach(id -> 
                      scope.fork(() -> {
                        elasticSearchIndexService.delete(REPOSITORY_CONTEXT.get(), id);
                        return null; // Required for Callable interface
                      }));
              
              // Wait for all tasks to complete and propagate any exceptions
              try {
                scope.join().throwIfFailed();
              } 
              catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Purge operation interrupted for repository {}", repository.getName(), e);
              }
            });
      }
    }
  }

  @Guarded(by = STARTED)
  @Override
  public void rebuildIndex() {
    log.info("Rebuilding index of repository {}", getRepository().getName());

    elasticSearchIndexService.rebuildIndex(getRepository()); // clears out old documents

    rebuildComponentIndex();
  }

  /**
   * Re-submit search documents for every component in the repository for indexing.
   * Uses Virtual Threads for improved performance with bulk operations.
   */
  private void rebuildComponentIndex() {
    String repositoryName = getRepository().getName();
    Repository repository = getRepository();
    
    try {
      FluentComponents components = repository.facet(ContentFacet.class).components();

      long total = components.count();
      if (total <= 0) {
        return;
      }
      
      ProgressLogIntervalHelper progressLogger = new ProgressLogIntervalHelper(log, 60);
      Stopwatch sw = Stopwatch.createStarted();
      AtomicLong processed = new AtomicLong(0);
      
      // Create a virtual thread executor for processing pages in parallel
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Propagate repository context to all virtual threads
        ScopedValue.where(REPOSITORY_CONTEXT, repository)
            .where(REPOSITORY_FIELDS, repositoryFields)
            .run(() -> {
              Continuation<FluentComponent> page = components.browse(pageSize, null);
              
              while (!page.isEmpty()) {
                // Capture the current page for the lambda
                final Continuation<FluentComponent> currentPage = page;
                
                // Process each page in a separate virtual thread
                executor.submit(() -> {
                  try {
                    elasticSearchIndexService.bulkPut(
                        REPOSITORY_CONTEXT.get(), 
                        currentPage, 
                        this::identifier, 
                        this::document);
                    
                    long currentProcessed = processed.addAndGet(currentPage.size());
                    long elapsed = sw.elapsed(TimeUnit.MILLISECONDS);
                    
                    progressLogger.info("Indexed {} / {} {} components in {} ms",
                        currentProcessed, total, repositoryName, elapsed);
                  } 
                  catch (Exception e) {
                    log.error("Error indexing page of components for repository {}", 
                        repositoryName, e);
                  }
                });
                
                checkCancellation();
                page = components.browse(pageSize, page.nextContinuationToken());
              }
            });
      }
      
      progressLogger.flush(); // ensure the final progress message is flushed
    }
    catch (Exception e) {
      log.error("Unable to rebuild search index for repository {}", repositoryName, e);
    }
  }

  /**
   * Looks for the {@link SearchDocumentProducer} to use for the given repository format.
   */
  private SearchDocumentProducer lookupSearchDocumentProducer(final String format) {
    SearchDocumentProducer producer = searchDocumentProducersByFormat.get(format);
    if (producer == null) {
      producer = searchDocumentProducersByFormat.get("default");
    }
    checkState(producer != null, "Could not find a component metadata producer for format: %s", format);
    return producer;
  }

  /**
   * Returns the identifier for the given component in the repository's index.
   */
  private String identifier(final FluentComponent component) {
    return identifier(internalComponentId(component));
  }

  /**
   * Returns the identifier for the given component in the repository's index.
   */
  private String identifier(final int internalComponentId) {
    return toExternalId(internalComponentId).getValue();
  }

  /**
   * Returns the JSON document for the given component in the repository's index.
   * Uses ScopedValue for repository fields if available, otherwise falls back to instance field.
   */
  private String document(final FluentComponent component) {
    try {
      // Use ScopedValue if bound, otherwise fall back to instance field
      Map<String, Object> fields = REPOSITORY_FIELDS.isBound() ? 
          REPOSITORY_FIELDS.get() : repositoryFields;
      
      return searchDocumentProducer.getDocument(component, fields);
    }
    catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.warn("Error creating search document for {}", component, e);
      }
      else {
        log.warn("Error '{}' creating search document for component: {}", e.getMessage(), component);
      }
    }
    return null;
  }
}