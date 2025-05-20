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
package org.sonatype.nexus.repository.search.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.Loggers;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.cluster.health.ClusterIndexHealth;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.slf4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableMap;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.cluster.health.ClusterHealthStatus.GREEN;
import static org.elasticsearch.index.query.QueryBuilders.idsQuery;
import static org.sonatype.nexus.repository.search.index.IndexSettingsContributor.MAPPING_JSON;
import static org.sonatype.nexus.repository.search.index.SearchConstants.TYPE;
import static org.sonatype.nexus.scheduling.CancelableHelper.checkCancellation;

/**
 * Default {@link ElasticSearchIndexService} implementation.
 *
 * @since 3.25
 */
@Named("default")
@Singleton
public class ElasticSearchIndexServiceImpl
    extends ComponentSupport
    implements ElasticSearchIndexService
{
  private final Provider<Client> client;

  private final List<IndexSettingsContributor> indexSettingsContributors;

  private final EventManager eventManager;

  private final int calmTimeout;

  private final IndexNamingPolicy indexNamingPolicy;

  private final List<BulkIndexUpdateListener> updateListeners = new ArrayList<>();

  private final boolean periodicFlush;

  private final AtomicLong updateCount = new AtomicLong();

  private final ConcurrentMap<String, String> repositoryIndexNames = Maps.newConcurrentMap();

  private Map<Integer, Entry<BulkProcessor, ExecutorService>> bulkProcessorToExecutors;

  /**
   * @param client source for a {@link Client}
   * @param indexNamingPolicy the index naming policy
   * @param indexSettingsContributors the index settings contributors
   * @param eventManager the event manager
   * @param bulkCapacity how many bulk requests to batch before they're automatically flushed (default: 1000)
   * @param concurrentRequests how many bulk requests to execute concurrently (default: 1; 0 means execute
   *          synchronously)
   * @param flushInterval how long to wait in milliseconds between flushing bulk requests (default: 0, instantaneous)
   * @param calmTimeout timeout in ms to wait for a calm period
   * @param batchingThreads This is the number of threads 'n' batching up index updates into 'n' BulkProcessors.
   *          That is, the number of independent batches to accumulate index updates in.
   */
  @Inject
  public ElasticSearchIndexServiceImpl(
      final Provider<Client> client, // NOSONAR
      final IndexNamingPolicy indexNamingPolicy,
      final List<IndexSettingsContributor> indexSettingsContributors,
      final EventManager eventManager,
      @Named("${nexus.elasticsearch.bulkCapacity:-1000}") final int bulkCapacity,
      @Named("${nexus.elasticsearch.concurrentRequests:-1}") final int concurrentRequests,
      @Named("${nexus.elasticsearch.flushInterval:-0}") final int flushInterval,
      @Named("${nexus.elasticsearch.calmTimeout:-3000}") final int calmTimeout,
      @Named("${nexus.elasticsearch.batching.threads.count:-1}") final int batchingThreads)
  {
    checkState(batchingThreads > 0,
        "'nexus.elasticsearch.batching.threads.count' must be positive.");

    this.client = checkNotNull(client);
    this.indexNamingPolicy = checkNotNull(indexNamingPolicy);
    this.indexSettingsContributors = checkNotNull(indexSettingsContributors);
    this.eventManager = checkNotNull(eventManager);
    this.calmTimeout = calmTimeout;
    this.periodicFlush = flushInterval > 0;

    createBulkProcessorsAndExecutors(bulkCapacity, concurrentRequests, flushInterval, batchingThreads);
  }

  @VisibleForTesting
  void setBulkProcessorToExecutors(final Map<Integer, Entry<BulkProcessor, ExecutorService>> bulkProcessorToExecutors) {
    this.bulkProcessorToExecutors = bulkProcessorToExecutors;
  }

  private void createBulkProcessorsAndExecutors(
      final int bulkCapacity,
      final int concurrentRequests,
      final int flushInterval,
      final int batchingThreads)
  {
    Map<Integer, Entry<BulkProcessor, ExecutorService>> bulkProcessorAndThreadPools = new HashMap<>();
    for (int count = 0; count < batchingThreads; ++count) {
      final BulkIndexUpdateListener updateListener = new BulkIndexUpdateListener();
      updateListeners.add(updateListener);
      bulkProcessorAndThreadPools.put(count, new SimpleImmutableEntry<>(BulkProcessor
          .builder(this.client.get(), updateListener)
          .setBulkActions(bulkCapacity)
          .setBulkSize(new ByteSizeValue(-1)) // turn off automatic flush based on size in bytes
          .setConcurrentRequests(concurrentRequests)
          .setFlushInterval(periodicFlush ? TimeValue.timeValueMillis(flushInterval) : null)
          .build(), createThreadPool(count)));
    }
    this.bulkProcessorToExecutors = unmodifiableMap(bulkProcessorAndThreadPools);
  }

  private ExecutorService createThreadPool(final int id) {
    return newVirtualThreadPerTaskExecutor();
  }

  @Override
  public void createIndex(final Repository repository) {
    checkNotNull(repository);
    final String safeIndexName = indexNamingPolicy.indexName(repository);
    log.debug(STR."Creating index for \{repository}");
    createIndex(repository, safeIndexName);
  }

  private void createIndex(final Repository repository, final String indexName) {
    // TODO we should calculate the checksum of index settings and compare it with a value stored in index _meta tags
    // in case that they not match (settings changed) we should drop the index, recreate it and re-index all components
    IndicesAdminClient indices = indicesAdminClient();
    if (!indices.prepareExists(indexName).execute().actionGet().isExists()) {
      // determine list of mapping configuration urls
      List<URL> urls = Lists.newArrayListWithExpectedSize(indexSettingsContributors.size() + 1);
      urls.add(Resources.getResource(getClass(), MAPPING_JSON)); // core mapping
      for (IndexSettingsContributor contributor : indexSettingsContributors) {
        URL url = contributor.getIndexSettings(repository);
        if (url != null) {
          urls.add(url);
        }
      }

      try {
        // merge all mapping configuration
        String source = "{}";
        for (URL url : urls) {
          log.debug(STR."Merging ElasticSearch mapping: \{url}");
          String contributed = Resources.toString(url, StandardCharsets.UTF_8);
          log.trace(STR."Contributed ElasticSearch mapping: \{contributed}");
          source = JsonUtils.merge(source, contributed);
        }
        // update runtime configuration
        log.trace(STR."ElasticSearch mapping: \{source}");
        indices.prepareCreate(indexName)
            .setSource(source)
            .execute()
            .actionGet();
      }
      catch (IndexAlreadyExistsException e) {
        log.debug(STR."Using existing index for \{repository}", e);
      }
      catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    repositoryIndexNames.put(repository.getName(), indexName);
  }

  @Override
  public void deleteIndex(final Repository repository) {
    checkNotNull(repository);
    String indexName = repositoryIndexNames.remove(repository.getName());
    if (indexName != null) {
      log.debug(STR."Removing index of \{repository}");
      deleteIndex(indexName);
    }
  }

  private void deleteIndex(final String indexName) {
    // make sure dangling requests don't resurrect this index
    flushBulkProcessors();

    IndicesAdminClient indices = indicesAdminClient();
    if (indices.prepareExists(indexName).execute().actionGet().isExists()) {
      indices.prepareDelete(indexName).execute().actionGet();
    }
  }

  @Override
  public void rebuildIndex(final Repository repository) {
    checkNotNull(repository);
    String indexName = repositoryIndexNames.remove(repository.getName());
    if (indexName != null) {
      log.debug(STR."Rebuilding index for \{repository}");
      deleteIndex(indexName);
      createIndex(repository, indexName);
    }
  }

  @Override
  public boolean indexExist(final Repository repository) {
    checkNotNull(repository);
    final String indexName = indexNamingPolicy.indexName(repository);
    IndicesAdminClient indices = indicesAdminClient();
    boolean indexExists = indices.prepareExists(indexName).execute().actionGet().isExists();
    log.info(STR."Repository \{repository} has search index: \{indexExists}");
    return indexExists;
  }

  @Override
  public boolean indexEmpty(final Repository repository) {
    checkNotNull(repository);
    String indexName = indexNamingPolicy.indexName(repository);

    IndexStats indexStats = indicesAdminClient().prepareStats(indexName).get().getIndex(indexName);
    long count = 0;
    if (indexStats != null) {
      count = indexStats.getTotal().getDocs().getCount();
    }

    boolean isEmpty = count == 0;
    log.debug(STR."Repository index: \{indexName} is \{isEmpty ? "empty" : "not empty"}.");
    return isEmpty;
  }

  @Override
  public void put(final Repository repository, final String identifier, final String json) {
    checkNotNull(repository);
    checkNotNull(identifier);
    checkNotNull(json);
    String indexName = repositoryIndexNames.get(repository.getName());
    if (indexName == null) {
      return;
    }
    updateCount.getAndIncrement();
    log.debug(STR."Adding to index document \{identifier} from \{repository}: \{json}");
    client.get()
        .prepareIndex(indexName, TYPE, identifier)
        .setSource(json)
        .execute(
            new ActionListener<IndexResponse>()
            {
              @Override
              public void onResponse(final IndexResponse indexResponse) {
                log.debug(STR."successfully added \{TYPE} \{identifier} to index \{indexName}: \{indexResponse}");
              }

              @Override
              public void onFailure(final Throwable e) {
                log.error(
                    STR."failed to add \{TYPE} \{identifier} to index \{indexName}; this is a sign that the Elasticsearch index thread pool is overloaded",
                    e);
              }
            });
  }

  @Override
  public <T> List<Future<Void>> bulkPut(
      final Repository repository,
      final Iterable<T> components,
      final Function<T, String> identifierProducer,
      final Function<T, String> jsonDocumentProducer)
  {
    checkNotNull(repository);
    checkNotNull(components);
    String indexName = repositoryIndexNames.get(repository.getName());
    if (indexName == null) {
      return emptyList();
    }

    final Entry<BulkProcessor, ExecutorService> bulkProcessorToExecutorPair = pickABulkProcessor();
    final BulkProcessor bulkProcessor = bulkProcessorToExecutorPair.getKey();
    final ExecutorService executorService = bulkProcessorToExecutorPair.getValue();
    final List<Future<Void>> futures = new ArrayList<>();

    for (T component : components) {
      checkCancellation();
      String identifier = identifierProducer.apply(component);
      String json = jsonDocumentProducer.apply(component);
      if (json != null) {
        json = filterConanAssetAttributes(json);
        updateCount.getAndIncrement();

        log.debug(STR."Bulk adding to index document \{identifier} from \{repository}: \{json}");
        futures.add(executorService.submit(
            () -> {
              bulkProcessor.add(createIndexRequest(indexName, identifier, json));
              return null;
            }));
      }
    }

    if (!periodicFlush) {
      futures.add(executorService.submit(() -> {
        bulkProcessor.flush();
        return null;
      }));
    }
    return futures;
  }

  @VisibleForTesting
  static String filterConanAssetAttributes(String json) {
    Logger logger = Loggers.getLogger(ElasticSearchIndexServiceImpl.class);
    try {
      JsonNode root = JsonUtils.readTree(json);

      // Use pattern matching to check if this is a Conan format
      if (root instanceof ObjectNode rootObj) {
        JsonNode format = rootObj.get("format");
        if (format == null || !format.textValue().equals("conan")) {
          logger.debug(STR."Skip filter Conan Asset. Format \{format} is not Conan. json: \{json}");
          return json;
        }

        JsonNode assetsNode = rootObj.get("assets");
        if (assetsNode instanceof ArrayNode assets) {
          for (JsonNode assetNode : assets) {
            if (assetNode instanceof ObjectNode asset) {
              JsonNode attributesNode = asset.get("attributes");
              if (attributesNode instanceof ObjectNode attributes) {
                // Filter attributes to keep only 'conan' and 'checksum'
                Iterator<String> attributesFieldNames = attributes.fieldNames();
                while (attributesFieldNames.hasNext()) {
                  String fieldName = attributesFieldNames.next();
                  if (!fieldName.equals("conan") && !fieldName.equals("checksum")) {
                    attributesFieldNames.remove();
                  }
                }

                JsonNode conanNode = attributes.get("conan");
                if (conanNode instanceof ObjectNode conan) {
                  // Filter conan attributes to keep only 'packageId' and 'packageRevision'
                  Iterator<String> conanFieldNames = conan.fieldNames();
                  while (conanFieldNames.hasNext()) {
                    String fieldName = conanFieldNames.next();
                    if (!fieldName.equals("packageId") && !fieldName.equals("packageRevision")) {
                      conanFieldNames.remove();
                    }
                  }
                } else {
                  logger.debug(STR."Conan Attribute not found for json \{json}");
                }
              } else {
                logger.debug(STR."Asset Attributes not found for json \{json}");
              }
            }
          }
        } else {
          logger.debug(STR."Asset not found for json \{json}");
        }
      }
      return JsonUtils.from(root);
    }
    catch (IOException e) {
      logger.debug(STR."Error during filter Conan Asset Attributes for json \{json}");
      return json;
    }
  }

  private IndexRequest createIndexRequest(final String indexName, final String identifier, final String json) {
    return client.get()
        .prepareIndex(indexName, TYPE, identifier)
        .setSource(json)
        .request();
  }

  @Override
  public void delete(final Repository repository, final String identifier) {
    checkNotNull(repository);
    checkNotNull(identifier);
    String indexName = repositoryIndexNames.get(repository.getName());
    if (indexName == null) {
      return;
    }
    log.debug(STR."Removing from index document \{identifier} from \{repository}");
    client.get().prepareDelete(indexName, TYPE, identifier).execute(new ActionListener<DeleteResponse>()
    {
      @Override
      public void onResponse(final DeleteResponse deleteResponse) {
        log.debug(STR."successfully removed \{TYPE} \{identifier} from index \{indexName}: \{deleteResponse}");
      }

      @Override
      public void onFailure(final Throwable e) {
        log.error(
            STR."failed to remove \{TYPE} \{identifier} from index \{indexName}; this is a sign that the Elasticsearch index thread pool is overloaded",
            e);
      }
    });
  }

  @Override
  public void bulkDelete(@Nullable final Repository repository, final Iterable<String> identifiers) {
    checkNotNull(identifiers);

    final Entry<BulkProcessor, ExecutorService> bulkProcessorToExecutorPair = pickABulkProcessor();
    final BulkProcessor bulkProcessor = bulkProcessorToExecutorPair.getKey();
    final ExecutorService executorService = bulkProcessorToExecutorPair.getValue();
    if (repository != null) {
      String indexName = repositoryIndexNames.get(repository.getName());
      if (indexName == null) {
        return; // index has gone, nothing to delete
      }

      for (String id : identifiers) {
        log.debug(STR."Bulk removing from index document \{id} from \{repository}");
        executorService.submit(() -> {
          bulkProcessor.add(client.get().prepareDelete(indexName, TYPE, id).request());
          return null;
        });
      }
    }
    else {
      // When bulk-deleting documents based on the write-ahead-log we won't have the owning index.
      // Since delete is a single-index operation we need to discover the index for each identifier
      // before we can delete its document. Chunking is used to keep queries to a reasonable size.

      for (List<String> chunk : Iterables.partition(identifiers, 100)) {
        SearchResponse toDelete = client.get()
            .prepareSearch("_all")
            .setFetchSource(false)
            .setQuery(idsQuery(TYPE).ids(chunk))
            .setSize(chunk.size())
            .execute()
            .actionGet();

        toDelete.getHits().forEach(hit -> {
          log.debug(STR."Bulk removing from index document \{hit.getId()} from \{hit.index()}");
          executorService.submit(() -> {
            bulkProcessor.add(client.get().prepareDelete(hit.index(), TYPE, hit.getId()).request());
            return null;
          });
        });
      }
    }

    if (!periodicFlush) {
      executorService.submit(() -> {
        bulkProcessor.flush();
        return null;
      });
    }
  }

  private Entry<BulkProcessor, ExecutorService> pickABulkProcessor() {
    final int numberOfBulkProcessors = bulkProcessorToExecutors.size();
    if (numberOfBulkProcessors > 1) {
      final int index = ThreadLocalRandom.current().nextInt(numberOfBulkProcessors);
      return bulkProcessorToExecutors.get(index);
    }
    return bulkProcessorToExecutors.get(0);
  }

  @Override
  public void flush(final boolean fsync) {
    log.debug("Flushing index requests");
    flushBulkProcessors();

    if (fsync) {
      try {
        indicesAdminClient().prepareSyncedFlush().execute().actionGet();
      }
      catch (RuntimeException e) {
        log.warn("Problem flushing search indices", e);
      }
    }
  }

  private List<Future<Void>> flushBulkProcessors() {
    return bulkProcessorToExecutors
        .values()
        .stream()
        .map(this::flushBulkProcessor)
        .collect(toList());
  }

  private Future<Void> flushBulkProcessor(final Entry<BulkProcessor, ExecutorService> bulkProcessorExecutorPair) {
    final ExecutorService executorService = bulkProcessorExecutorPair.getValue();
    final BulkProcessor bulkProcessor = bulkProcessorExecutorPair.getKey();
    return executorService.submit(() -> {
      bulkProcessor.flush();
      return null;
    });
  }

  @Override
  public long getUpdateCount() {
    return updateCount.get();
  }

  @Override
  public boolean isCalmPeriod() {
    if (isUpdateInFlight()) {
      return false;
    }

    try {
      // now it's calm make sure updates are available for searching
      indicesAdminClient().prepareRefresh().execute().actionGet();
    }
    catch (RuntimeException e) {
      log.warn("Problem refreshing search indices", e);
    }

    return true;
  }

  private boolean isUpdateInFlight() {
    return updateListeners
        .stream()
        .map(BulkIndexUpdateListener::inflightRequestCount)
        .anyMatch(inflight -> inflight > 0);
  }

  @Override
  public void waitForCalm() {
    try {
      waitFor(() -> {
        try {
          return eventManager.isCalmPeriod();
        } catch (Exception e) {
          log.debug("Exception while checking calm period", e);
          return false;
        }
      });
      flush(false); // no need for full fsync here
      waitFor(() -> {
        try {
          return isCalmPeriod();
        } catch (Exception e) {
          log.debug("Exception while checking calm period", e);
          return false;
        }
      });
    }
    catch (InterruptedException e) { // NOSONAR: we want to swallow interrupt here
      throw new RuntimeException("Waiting for calm period has been interrupted", e);
    }
  }

  @Override
  public void waitForReady() {
    try {
      waitFor(() -> {
        try {
          return allIndicesReady();
        } catch (Exception e) {
          log.debug("Exception while checking indices readiness", e);
          return false;
        }
      });
    }
    catch (InterruptedException e) { // NOSONAR: we want to swallow interrupt here
      throw new RuntimeException("Waiting for index shards to move to GREEN state has been interrupted", e);
    }
  }

  private boolean allIndicesReady() {
    Map<String, ClusterIndexHealth> indexHealth =
        client.get().admin().cluster().health(new ClusterHealthRequest()).actionGet().getIndices();
    for (ClusterIndexHealth health : indexHealth.values()) {
      if (health.getStatus() != GREEN) {
        log.info(STR."Index \{health.getIndex()} is not ready: \{health.getStatus()}");
        return false;
      }
      log.debug(STR."Index \{health.getIndex()} is ready: \{health.getStatus()}");
    }
    return true;
  }

  private void waitFor(final Callable<Boolean> function) throws InterruptedException {
    Thread.yield();
    long end = System.currentTimeMillis() + calmTimeout;
    do {
      try {
        if (Boolean.TRUE.equals(function.call())) {
          return; // success
        }
      }
      catch (final InterruptedException e) {
        throw e; // cancelled
      }
      catch (final Exception e) { // NOSONAR
        log.debug("Exception thrown whilst waiting", e);
      }
      Thread.sleep(100);
    }
    while (System.currentTimeMillis() <= end);

    log.warn(STR."Timed out waiting for \{function} after \{calmTimeout} ms");
  }

  /**
   * Returns the indixes admin client.
   */
  private IndicesAdminClient indicesAdminClient() {
    return client.get().admin().indices();
  }
}