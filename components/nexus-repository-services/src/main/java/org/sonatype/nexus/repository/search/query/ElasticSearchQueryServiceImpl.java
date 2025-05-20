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
package org.sonatype.nexus.repository.search.query;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.index.IndexNamingPolicy;
import org.sonatype.nexus.repository.search.index.SearchIndexFacet;
import org.sonatype.nexus.repository.search.query.SearchSubjectHelper.SubjectRegistration;
import org.sonatype.nexus.repository.search.selector.ContentAuthPluginScriptFactory;
import org.sonatype.nexus.repository.security.RepositoryViewPermission;
import org.sonatype.nexus.security.SecurityHelper;

import com.google.common.annotations.VisibleForTesting;
import org.elasticsearch.action.admin.indices.validate.query.ValidateQueryRequestBuilder;
import org.elasticsearch.action.admin.indices.validate.query.ValidateQueryResponse;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.ClearScrollResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequestBuilder;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.profile.ProfileShardResult;
import org.elasticsearch.search.sort.SortBuilder;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static org.elasticsearch.index.query.QueryBuilders.scriptQuery;
import static org.sonatype.nexus.repository.search.index.SearchConstants.TYPE;
import static org.sonatype.nexus.repository.search.query.RepositoryQueryBuilder.repositoryQuery;
import static org.sonatype.nexus.security.BreadActions.BROWSE;

/**
 * Default {@link ElasticSearchQueryService} implementation.
 *
 * @since 3.25
 */
@Named("default")
@Singleton
public class ElasticSearchQueryServiceImpl
    extends ComponentSupport
    implements ElasticSearchQueryService
{
  private static final SearchResponse EMPTY_SEARCH_RESPONSE =
      new SearchResponse(InternalSearchResponse.empty(), null, 0, 0, 0, new ShardSearchFailure[]{});

  private final Provider<Client> client;

  private final RepositoryManager repositoryManager;

  private final SecurityHelper securityHelper;

  private final SearchSubjectHelper searchSubjectHelper;

  private final IndexNamingPolicy indexNamingPolicy;

  private final boolean profile;
  
  private final ExecutorService virtualThreadExecutor;

  private static final int MAX_ELASTIC_RESPONSE_SIZE = 10000;

  /**
   * @param client source for a {@link Client}
   * @param repositoryManager the repositoryManager
   * @param securityHelper the securityHelper
   * @param searchSubjectHelper the searchSubjectHelper
   * @param indexNamingPolicy the index naming policy
   * @param profile whether or not to profile elasticsearch queries (default: false)
   */
  @Inject
  public ElasticSearchQueryServiceImpl(final Provider<Client> client,
                                       final RepositoryManager repositoryManager,
                                       final SecurityHelper securityHelper,
                                       final SearchSubjectHelper searchSubjectHelper,
                                       final IndexNamingPolicy indexNamingPolicy,
                                       @Named("${nexus.elasticsearch.profile:-false}") final boolean profile)
  {
    this.client = checkNotNull(client);
    this.repositoryManager = checkNotNull(repositoryManager);
    this.securityHelper = checkNotNull(securityHelper);
    this.searchSubjectHelper = checkNotNull(searchSubjectHelper);
    this.indexNamingPolicy = checkNotNull(indexNamingPolicy);
    this.profile = profile;
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public Iterable<SearchHit> browse(final QueryBuilder query) {
    if (!validateQuery(query)) {
      return emptyList();
    }

    RepositoryQueryBuilder repoQuery = repositoryQuery(query);
    final String[] searchableIndexes = getSearchableIndexes(repoQuery);
    if (searchableIndexes.length == 0) {
      return emptyList();
    }

    return () -> new SearchHitIterator(query, searchableIndexes, repoQuery.skipContentSelectors);
  }

  @Override
  public SearchResponse search(final QueryBuilder query, final int from, final int size) {
    if (!validateQuery(query) || from + size > MAX_ELASTIC_RESPONSE_SIZE) {
      return EMPTY_SEARCH_RESPONSE;
    }

    RepositoryQueryBuilder repoQuery = repositoryQuery(query);
    final String[] searchableIndexes = getSearchableIndexes(repoQuery);
    if (searchableIndexes.length == 0) {
      return EMPTY_SEARCH_RESPONSE;
    }

    if (repoQuery.skipContentSelectors) {
      return executeSearch(repoQuery, searchableIndexes, from, size, null);
    }

    try (SubjectRegistration registration = searchSubjectHelper.register(securityHelper.subject())) {
      QueryBuilder selectorFilter = scriptQuery(ContentAuthPluginScriptFactory.newScript(registration.getId()));
      return executeSearch(repoQuery, searchableIndexes, from, size, selectorFilter);
    }
  }

  @Override
  public SearchResponse search(final QueryBuilder query, final List<AggregationBuilder> aggregations) {
    if (!validateQuery(query)) {
      return EMPTY_SEARCH_RESPONSE;
    }

    checkNotNull(aggregations);

    RepositoryQueryBuilder repoQuery = repositoryQuery(query);
    final String[] searchableIndexes = getSearchableIndexes(repoQuery);
    if (searchableIndexes.length == 0) {
      return EMPTY_SEARCH_RESPONSE;
    }

    if (repoQuery.skipContentSelectors) {
      return executeSearch(repoQuery, searchableIndexes, aggregations, null);
    }

    try (SubjectRegistration registration = searchSubjectHelper.register(securityHelper.subject())) {
      QueryBuilder selectorFilter = scriptQuery(ContentAuthPluginScriptFactory.newScript(registration.getId()));
      return executeSearch(repoQuery, searchableIndexes, aggregations, selectorFilter);
    }
  }

  private SearchResponse executeSearch(final RepositoryQueryBuilder repoQuery,
                                       final String[] searchableIndexes,
                                       final int from, final int size,
                                       @Nullable final QueryBuilder postFilter)
  {
    try {
      Future<SearchResponse> searchFuture = virtualThreadExecutor.submit(() -> {
        SearchRequestBuilder searchRequestBuilder = client.get().prepareSearch(searchableIndexes)
            .setTypes(TYPE)
            .setQuery(repoQuery)
            .setFrom(from)
            .setSize(size)
            .setProfile(profile);

        if (repoQuery.sort != null) {
          for (SortBuilder entry : repoQuery.sort) {
            searchRequestBuilder.addSort(entry);
          }
        }

        if (postFilter != null) {
          searchRequestBuilder.setPostFilter(postFilter);
        }

        if (repoQuery.timeout != null) {
          searchRequestBuilder.setTimeout(repoQuery.timeout.getSeconds() + "s");
        }

        SearchResponse searchResponse = searchRequestBuilder.execute().actionGet();

        if (profile) {
          logProfileResults(searchResponse);
        }

        return searchResponse;
      });
      
      return searchFuture.get();
    } catch (Exception e) {
      log.error("Error executing search", e);
      return EMPTY_SEARCH_RESPONSE;
    }
  }

  private SearchResponse executeSearch(final RepositoryQueryBuilder repoQuery,
                                       final String[] searchableIndexes,
                                       final List<AggregationBuilder> aggregations,
                                       @Nullable final QueryBuilder postFilter)
  {
    try {
      Future<SearchResponse> searchFuture = virtualThreadExecutor.submit(() -> {
        SearchRequestBuilder searchRequestBuilder = client.get().prepareSearch(searchableIndexes)
            .setTypes(TYPE)
            .setQuery(repoQuery)
            .setFrom(0)
            .setSize(0)
            .setProfile(profile)
            .setTrackScores(true);

        for (AggregationBuilder aggregation : aggregations) {
          searchRequestBuilder.addAggregation(aggregation);
        }

        if (repoQuery.sort != null) {
          for (SortBuilder entry : repoQuery.sort) {
            searchRequestBuilder.addSort(entry);
          }
        }

        if (postFilter != null) {
          searchRequestBuilder.setPostFilter(postFilter);
        }

        if (repoQuery.timeout != null) {
          searchRequestBuilder.setTimeout(repoQuery.timeout.getSeconds() + "s");
        }

        SearchResponse searchResponse = searchRequestBuilder.execute().actionGet();

        if (profile) {
          logProfileResults(searchResponse);
        }

        return searchResponse;
      });
      
      return searchFuture.get();
    } catch (Exception e) {
      log.error("Error executing search with aggregations", e);
      return EMPTY_SEARCH_RESPONSE;
    }
  }

  @Override
  public long count(final QueryBuilder query) {
    if (!validateQuery(query)) {
      return 0;
    }

    RepositoryQueryBuilder repoQuery = repositoryQuery(query);
    final String[] searchableIndexes = getSearchableIndexes(repoQuery);
    if (searchableIndexes.length == 0) {
      return 0;
    }

    try {
      Future<Long> countFuture = virtualThreadExecutor.submit(() -> {
        SearchRequestBuilder searchRequestBuilder = client.get().prepareSearch(searchableIndexes)
            .setTypes(TYPE)
            .setQuery(repoQuery)
            .setFrom(0)
            .setSize(0);

        if (repoQuery.skipContentSelectors) {
          return searchRequestBuilder.execute().actionGet().getHits().totalHits();
        }

        try (SubjectRegistration registration = searchSubjectHelper.register(securityHelper.subject())) {
          QueryBuilder selectorFilter = scriptQuery(ContentAuthPluginScriptFactory.newScript(registration.getId()));
          searchRequestBuilder.setPostFilter(selectorFilter);
          return searchRequestBuilder.execute().actionGet().getHits().totalHits();
        }
      });
      
      return countFuture.get();
    } catch (Exception e) {
      log.error("Error executing count", e);
      return 0;
    }
  }

  private boolean validateQuery(final QueryBuilder query) {
    checkNotNull(query);
    try {
      ValidateQueryRequestBuilder validateRequest = indicesAdminClient().prepareValidateQuery().setQuery(query);
      if (log.isDebugEnabled()) {
        validateRequest.setExplain(true);
      }
      ValidateQueryResponse validateQueryResponse = validateRequest.execute().actionGet();
      if (!validateQueryResponse.isValid()) {
        if (log.isDebugEnabled()) {
          String explanations = validateQueryResponse.getQueryExplanation().stream()
              .map(input -> input.getExplanation() != null ? input.getExplanation() : input.getError())
              .collect(joining());
          log.debug("Invalid query explanation: {}", explanations);
        }
        throw new IllegalArgumentException("Invalid query");
      }
      return true;
    }
    catch (IndexNotFoundException e) {
      // no repositories were created yet, so there is no point in searching
      return false;
    }
  }

  @VisibleForTesting
  String[] getSearchableIndexes(final RepositoryQueryBuilder repoQuery) {
    Stream<Repository> repositories = StreamSupport
        .stream(repositoryManager.browse().spliterator(), false)
        .filter(ElasticSearchQueryServiceImpl::repoOnlineAndHasSearchIndexFacet);

    if (repoQuery.repositoryNames != null) {
      repositories = repositories
          .filter(r -> repoQuery.repositoryNames.contains(r.getName()));
    }

    if (repoQuery.skipContentSelectors) {
      // not post-filtering by content selector, so we must pre-filter repositories we can access
      repositories = repositories
          .filter(r -> securityHelper.allPermitted(new RepositoryViewPermission(r, BROWSE)));
    }

    return repositories
        .map(indexNamingPolicy::indexName)
        .toArray(String[]::new);
  }

  private static boolean repoOnlineAndHasSearchIndexFacet(final Repository repo) {
    return repo.optionalFacet(SearchIndexFacet.class).isPresent() && repo.getConfiguration().isOnline();
  }

  private IndicesAdminClient indicesAdminClient() {
    return client.get().admin().indices();
  }

  private void logProfileResults(final SearchResponse searchResponse) {
    for (Entry<String, List<ProfileShardResult>> entry : searchResponse.getProfileResults().entrySet()) {
      for (ProfileShardResult profileShardResult : entry.getValue()) {
        try {
          // Using pattern matching with record patterns for more concise code
          if (profileShardResult instanceof ProfileShardResult(var searchProfile, var collectors, var rewriteTime)) {
            XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
            builder.startObject();
            profileShardResult.toXContent(builder, ToXContent.EMPTY_PARAMS);
            builder.endObject();
            if (log.isInfoEnabled()) {
              log.info(STR."Elasticsearch profile for \{entry.getKey()} is: \{builder.string()}");
            }
          }
        }
        catch (IOException e) {
          log.error("Error writing elasticsearch profile result", e);
        }
      }
    }
  }

  private class SearchHitIterator
      implements Iterator<SearchHit>
  {
    private final QueryBuilder query;

    private final String[] searchableIndexes;

    private final boolean skipPermissionCheck;

    private SearchResponse response;

    private Iterator<SearchHit> iterator;

    private boolean noMoreHits = false;

    SearchHitIterator(final QueryBuilder query,
                      final String[] searchableIndexes, // NOSONAR
                      final boolean skipPermissionCheck)
    {
      this.query = query;
      this.searchableIndexes = searchableIndexes;
      this.skipPermissionCheck = skipPermissionCheck;
    }

    @Override
    public boolean hasNext() {
      if (noMoreHits) {
        return false;
      }
      if (response == null) {
        try {
          Future<SearchResponse> responseFuture = virtualThreadExecutor.submit(() -> {
            SearchRequestBuilder builder = client.get().prepareSearch(searchableIndexes)
                .setTypes(TYPE)
                .setQuery(query)
                .setScroll(new TimeValue(1, TimeUnit.MINUTES))
                .setSize(100)
                .setProfile(profile);
            if (!skipPermissionCheck) {
              try (SubjectRegistration registration = searchSubjectHelper.register(securityHelper.subject())) {
                QueryBuilder selectorFilter = scriptQuery(ContentAuthPluginScriptFactory.newScript(registration.getId()));
                builder.setPostFilter(selectorFilter);
                return builder.execute().actionGet();
              }
            }
            else {
              return builder.execute().actionGet();
            }
          });
          
          response = responseFuture.get();
          iterator = Arrays.asList(response.getHits().getHits()).iterator();
          noMoreHits = !iterator.hasNext();
        } catch (Exception e) {
          log.error("Error fetching initial search results", e);
          noMoreHits = true;
          return false;
        }
      }
      else if (!iterator.hasNext()) {
        try {
          Future<SearchResponse> scrollFuture = virtualThreadExecutor.submit(() -> {
            SearchScrollRequestBuilder builder = client.get().prepareSearchScroll(response.getScrollId())
                .setScroll(new TimeValue(1, TimeUnit.MINUTES));
            return builder.execute().actionGet();
          });
          
          response = scrollFuture.get();
          iterator = Arrays.asList(response.getHits().getHits()).iterator();
          noMoreHits = !iterator.hasNext();
        } catch (Exception e) {
          log.error("Error fetching next page of search results", e);
          noMoreHits = true;
          return false;
        }
      }
      return iterator.hasNext();
    }

    @Override
    public SearchHit next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return iterator.next();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void forEachRemaining(final Consumer<? super SearchHit> action) {
      Iterator.super.forEachRemaining(action);
      closeScrollId();
    }

    private void closeScrollId() {
      log.debug(STR."Clearing scroll id \{response.getScrollId()}");
      try {
        virtualThreadExecutor.submit(() -> {
          ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
          clearScrollRequest.addScrollId(response.getScrollId());
          ClearScrollResponse clearScrollResponse = client.get().clearScroll(clearScrollRequest).actionGet();
          if (!clearScrollResponse.isSucceeded()) {
            log.info(STR."Unable to close scroll id \{response.getScrollId()}");
          }
          return null;
        }).get();
      } catch (Exception e) {
        log.error(STR."Error clearing scroll id \{response.getScrollId()}", e);
      }
    }
  }
}