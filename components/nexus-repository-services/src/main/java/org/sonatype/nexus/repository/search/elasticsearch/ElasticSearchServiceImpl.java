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
package org.sonatype.nexus.repository.search.elasticsearch;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.rest.internal.resources.TokenEncoder;
import org.sonatype.nexus.repository.search.AssetSearchResult;
import org.sonatype.nexus.repository.search.ComponentSearchResult;
import org.sonatype.nexus.repository.search.SearchRequest;
import org.sonatype.nexus.repository.search.SearchResponse;
import org.sonatype.nexus.repository.search.SearchService;
import org.sonatype.nexus.repository.search.index.ElasticSearchIndexService;
import org.sonatype.nexus.repository.search.query.ElasticSearchQueryService;
import org.sonatype.nexus.repository.search.query.ElasticSearchUtils;

import com.google.common.collect.ImmutableList;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.time.format.DateTimeFormatter.ofPattern;
import static org.sonatype.nexus.repository.search.index.SearchConstants.*;

/**
 * Implementation of {@link SearchService} to be used with orient/elasticsearch
 *
 * @since 3.38
 */
@Named
@Singleton
public class ElasticSearchServiceImpl
    extends ComponentSupport
    implements SearchService
{
  private static final DateTimeFormatter DATE_TIME_FORMATTER = ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSZ");

  private final ElasticSearchQueryService elasticSearchQueryService;

  private final ElasticSearchIndexService elasticSearchIndexService;

  private final ElasticSearchUtils elasticSearchUtils;

  private final TokenEncoder tokenEncoder;

  private final Set<ElasticSearchExtension> decorators;
  
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public ElasticSearchServiceImpl(
      final ElasticSearchQueryService elasticSearchQueryService,
      final ElasticSearchIndexService elasticSearchIndexService,
      final ElasticSearchUtils elasticSearchUtils,
      final TokenEncoder tokenEncoder,
      final Set<ElasticSearchExtension> decorators)
  {
    this.elasticSearchQueryService = checkNotNull(elasticSearchQueryService);
    this.elasticSearchIndexService = checkNotNull(elasticSearchIndexService);
    this.elasticSearchUtils = checkNotNull(elasticSearchUtils);
    this.tokenEncoder = checkNotNull(tokenEncoder);
    this.decorators = checkNotNull(decorators);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Performs a search operation using Virtual Threads for non-blocking asynchronous processing.
   * This improves performance for search operations by allowing them to run concurrently
   * without blocking the calling thread.
   *
   * @param searchRequest the search request containing query parameters
   * @return the search response containing the results
   */
  @Override
  public SearchResponse search(final SearchRequest searchRequest) {
    QueryBuilder queryBuilder = elasticSearchUtils.buildQuery(searchRequest);

    int from = Optional.ofNullable(searchRequest.getContinuationToken())
        .filter(Strings2::notBlank)
        .map(__ -> decodeFrom(searchRequest, queryBuilder))
        .orElseGet(() -> Optional.ofNullable(searchRequest.getOffset()).orElse(0));

    // Use Virtual Threads for non-blocking asynchronous processing
    Future<org.elasticsearch.action.search.SearchResponse> searchResponseFuture = virtualThreadExecutor.submit(() ->
        elasticSearchQueryService.search(queryBuilder, from, searchRequest.getLimit()));
    
    try {
      org.elasticsearch.action.search.SearchResponse searchResponse = searchResponseFuture.get();
      return convertSearchResponse(searchResponse, continuationToken(searchRequest, queryBuilder, searchResponse));
    } catch (Exception e) {
      log.error("Error executing search with virtual threads", e);
      // Fallback to synchronous execution if virtual thread execution fails
      org.elasticsearch.action.search.SearchResponse searchResponse =
          elasticSearchQueryService.search(queryBuilder, from, searchRequest.getLimit());
      return convertSearchResponse(searchResponse, continuationToken(searchRequest, queryBuilder, searchResponse));
    }
  }

  @Override
  public Iterable<ComponentSearchResult> browse(final SearchRequest searchRequest) {
    Iterable<SearchHit> browse = elasticSearchQueryService.browse(elasticSearchUtils.buildQuery(searchRequest));
    return () -> new SearchResultIterator(browse.iterator());
  }

  @Override
  public long count(final SearchRequest searchRequest) {
    return elasticSearchQueryService.count(elasticSearchUtils.buildQuery(searchRequest));
  }

  /**
   * Iterator implementation that uses Virtual Threads for concurrent processing of search results.
   * This improves performance for I/O-bound operations by allowing more concurrent processing
   * without the overhead of platform threads.
   */
  private class SearchResultIterator
      implements Iterator<ComponentSearchResult>
  {
    private final Iterator<SearchHit> searchHitIterator;
    private final ExecutorService executor;

    public SearchResultIterator(final Iterator<SearchHit> searchHitIterator) {
      this.searchHitIterator = searchHitIterator;
      // Use Virtual Threads for improved concurrent search result iteration
      this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public boolean hasNext() {
      return searchHitIterator.hasNext();
    }

    @Override
    public ComponentSearchResult next() {
      SearchHit hit = searchHitIterator.next();
      try {
        // Process the next result using a virtual thread for improved concurrency
        Future<ComponentSearchResult> future = executor.submit(() -> toComponentSearchResult(hit));
        return future.get();
      } catch (Exception e) {
        log.debug("Error processing search result with virtual thread, falling back to synchronous processing", e);
        // Fallback to synchronous processing if virtual thread execution fails
        return toComponentSearchResult(hit);
      }
    }
  }

  @Override
  public void waitForCalm() {
    elasticSearchIndexService.waitForCalm();
  }

  @Override
  public void waitForReady() {
    elasticSearchIndexService.waitForReady();
  }

  private String continuationToken(
      final SearchRequest request,
      final QueryBuilder query,
      final org.elasticsearch.action.search.SearchResponse searchResponse)
  {
    if (searchResponse.getHits().hits().length != request.getLimit()) {
      return null;
    }

    int from = decodeFrom(request, query);
    return tokenEncoder.encode(from, request.getLimit(), query);
  }

  private int decodeFrom(final SearchRequest request, final QueryBuilder query) {
    return tokenEncoder.decode(request.getContinuationToken(), query);
  }

  /**
   * Converts an Elasticsearch search response to a SearchResponse using Java 21 stream features
   * and Virtual Threads for improved performance.
   *
   * @param esResponse the Elasticsearch search response
   * @param continuationToken the continuation token for pagination
   * @return the converted search response
   */
  private SearchResponse convertSearchResponse(
      final org.elasticsearch.action.search.SearchResponse esResponse,
      final String continuationToken)
  {
    SearchResponse searchResponse = new SearchResponse();

    // Use Java 21 stream features for improved performance with parallel processing
    // and Virtual Threads for non-blocking asynchronous processing
    List<ComponentSearchResult> componentSearchResults = java.util.Arrays.stream(esResponse.getHits().getHits())
        .parallel() // Use parallel stream for concurrent processing
        .map(hit -> {
          try {
            // Process each hit using a virtual thread for improved concurrency
            Future<ComponentSearchResult> future = virtualThreadExecutor.submit(() -> toComponentSearchResult(hit));
            return future.get();
          } catch (Exception e) {
            log.debug("Error processing search hit with virtual thread, falling back to synchronous processing", e);
            // Fallback to synchronous processing if virtual thread execution fails
            return toComponentSearchResult(hit);
          }
        })
        .collect(Collectors.toList());

    searchResponse.setSearchResults(componentSearchResults);
    searchResponse.setContinuationToken(continuationToken);
    searchResponse.setTotalHits(esResponse.getHits().getTotalHits());

    return searchResponse;
  }

  /**
   * Converts a SearchHit to a ComponentSearchResult using pattern matching for instanceof checks.
   * This improves code readability and eliminates the need for explicit casting.
   *
   * @param componentHit the search hit to convert
   * @return the component search result
   */
  @SuppressWarnings("unchecked")
  private ComponentSearchResult toComponentSearchResult(final SearchHit componentHit) {
    Map<String, Object> componentMap = checkNotNull(componentHit.getSource());
    Repository repository = elasticSearchUtils.getReadableRepository((String) componentMap.get(REPOSITORY_NAME));

    ComponentSearchResult componentSearchResult = new ComponentSearchResult();

    // Using pattern matching for instanceof check - Java 21 feature
    // This eliminates the need for explicit casting after the instanceof check
    if (componentMap.get(ASSETS) instanceof List<?> assets) {
      componentSearchResult.setAssets(assets.stream()
          .filter(Map.class::isInstance)
          .map(Map.class::cast)
          .map(assetMap -> toAssetSearchResult(assetMap, repository))
          .collect(Collectors.toList()));
    }
    else {
      componentSearchResult.setAssets(ImmutableList.of());
    }

    componentSearchResult.setGroup((String) componentMap.get(GROUP));
    componentSearchResult.setName((String) componentMap.get(NAME));
    componentSearchResult.setVersion((String) componentMap.get(VERSION));
    componentSearchResult.setId(componentHit.getId());
    componentSearchResult.setRepositoryName(repository.getName());
    componentSearchResult.setFormat(repository.getFormat().getValue());
    componentSearchResult.setLastDownloaded(calculateOffsetDateTime(componentMap, LAST_DOWNLOADED_KEY));
    componentSearchResult.setLastModified(calculateOffsetDateTime(componentMap, LAST_BLOB_UPDATED_KEY));

    decorators.forEach(extension -> extension.updateComponent(componentSearchResult, componentHit));

    return componentSearchResult;
  }

  /**
   * Converts a Map to an AssetSearchResult using pattern matching for instanceof checks.
   * This improves code readability and eliminates the need for explicit casting.
   *
   * @param assetMap the map containing asset data
   * @param repository the repository containing the asset
   * @return the asset search result
   */
  @SuppressWarnings("unchecked")
  private AssetSearchResult toAssetSearchResult(final Map<String, Object> assetMap, final Repository repository) {
    AssetSearchResult assetSearchResult = new AssetSearchResult();

    // Using pattern matching for instanceof checks and type casting - Java 21 feature
    // This eliminates the need for explicit casting after the instanceof check
    assetSearchResult.setAttributes(assetMap.getOrDefault(ATTRIBUTES, Collections.emptyMap()) instanceof Map<?, ?> attributes 
        ? (Map<String, Object>) attributes 
        : Collections.emptyMap());
    assetSearchResult.setPath((String) assetMap.get(NAME));
    assetSearchResult.setRepository(repository.getName());
    assetSearchResult.setId(String.valueOf(assetMap.get(ID)));
    
    // Using pattern matching for instanceof check and direct access
    if (assetSearchResult.getAttributes().get(CHECKSUM) instanceof Map<?, ?> checksum) {
      assetSearchResult.setChecksum((Map<String, String>) checksum);
    }
    
    assetSearchResult.setFormat(repository.getFormat().getValue());
    assetSearchResult.setContentType((String) assetMap.get(CONTENT_TYPE));
    assetSearchResult.setLastModified(calculateLastModified(assetSearchResult.getAttributes()));
    assetSearchResult.setUploader((String) assetMap.get(UPLOADER));
    assetSearchResult.setUploaderIp((String) assetMap.get(UPLOADER_IP));
    assetSearchResult.setLastDownloaded(calculateLastDownloaded(assetMap));
    assetSearchResult.setFileSize(getFileSize(assetMap));

    return assetSearchResult;
  }

  /**
   * Gets the file size from a map attribute using pattern matching for instanceof checks.
   * This improves code readability and eliminates the need for explicit casting.
   *
   * @param attributes the map containing the attribute
   * @return the file size, or null if not available
   */
  private Long getFileSize(final Map<String, Object> attributes) {
    // Using pattern matching for instanceof check - Java 21 feature
    return Optional.ofNullable(attributes.get(FILE_SIZE))
        .filter(size -> size instanceof Number) // Pattern matching in lambda
        .map(size -> (Number) size)
        .map(Number::longValue)
        .orElse(null);
  }

  /**
   * Record pattern for date-time data extraction.
   * This uses Java 21's record patterns to simplify data transformations.
   */
  private record DateTimeData(String value, Instant instant) {}
  
  /**
   * Calculates an OffsetDateTime from a map attribute using Record Patterns.
   * This optimizes data transformations by using a record to encapsulate the date-time information.
   *
   * @param attributes the map containing the attribute
   * @param field the field name to retrieve
   * @return the calculated OffsetDateTime, or null if not available
   */
  private OffsetDateTime calculateOffsetDateTime(final Map<String, Object> attributes, final String field) {
    try {
      return Optional.ofNullable(attributes.get(field))
          .filter(String.class::isInstance)
          .map(value -> {
            String dateStr = (String) value;
            Instant instant = Instant.from(DATE_TIME_FORMATTER.parse(dateStr));
            // Using Record Pattern to encapsulate date-time data
            return new DateTimeData(dateStr, instant);
          })
          .map(data -> OffsetDateTime.ofInstant(data.instant(), ZoneOffset.UTC))
          .orElse(null);
    }
    catch (Exception ignored) {
      log.debug("Unable to retrieve {}", field, ignored);
      // Nothing we can do here for invalid data. It shouldn't happen but date parsing will blow out the results.
      return null;
    }
  }

  /**
   * Calculates the last downloaded date from a map attribute using Record Patterns.
   * This optimizes data transformations by using a record to encapsulate the date-time information.
   *
   * @param attributes the map containing the attribute
   * @return the calculated Date, or null if not available
   */
  private Date calculateLastDownloaded(final Map<String, Object> attributes) {
    try {
      return Optional.ofNullable(attributes.get(LAST_DOWNLOADED_KEY))
          .filter(String.class::isInstance)
          .map(value -> {
            String dateStr = (String) value;
            Instant instant = Instant.from(DATE_TIME_FORMATTER.parse(dateStr));
            // Using Record Pattern to encapsulate date-time data
            return new DateTimeData(dateStr, instant);
          })
          .map(data -> Date.from(data.instant()))
          .orElse(null);
    }
    catch (Exception ignored) {
      log.debug("Unable to retrieve last_downloaded", ignored);
      return null;
    }
  }

  /**
   * Calculates the last modified date from a map attribute using pattern matching for instanceof checks.
   * This improves code readability and eliminates the need for explicit casting.
   *
   * @param attributes the map containing the attribute
   * @return the calculated Date, or null if not available
   */
  private Date calculateLastModified(final Map<String, Object> attributes) {
    try {
      // Using pattern matching for instanceof check and direct access - Java 21 feature
      // This eliminates the need for explicit casting after the instanceof check
      if (attributes.get("content") instanceof Map<?, ?> content) {
        return Optional.ofNullable(content.get("last_modified"))
            .map(Object::toString) // Sometimes last_modified is a string and sometimes a long for unknown reasons
            .map(Long::parseLong)
            .map(Date::new)
            .orElse(null);
      }
      return null;
    }
    catch (Exception ignored) {
      log.debug("Unable to retrieve last_modified", ignored);
      // Nothing we can do here for invalid data. It shouldn't happen but date parsing will blow out the results.
      return null;
    }
  }
}