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
package org.sonatype.nexus.repository.search;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.core.UriInfo;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.rest.SearchMapping;
import org.sonatype.nexus.repository.rest.SearchMappings;
import org.sonatype.nexus.repository.rest.api.RepositoryManagerRESTAdapter;
import org.sonatype.nexus.repository.search.query.SearchFilter;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.StreamSupport.stream;

/**
 * Utility class for search operations in Nexus Repository.
 * 
 * @since 3.38
 */
@Named
@Singleton
public class SearchUtils
    extends ComponentSupport
{
  public static final String CONTINUATION_TOKEN = "continuationToken";

  public static final String SORT_FIELD = "sort";

  public static final String SORT_DIRECTION = "direction";

  private static final String ASSET_PREFIX = "assets.";

  private final RepositoryManagerRESTAdapter repoAdapter;

  private final Map<String, String> searchParams;

  private final Map<String, String> assetSearchParams;

  @Inject
  public SearchUtils(
      final RepositoryManagerRESTAdapter repoAdapter,
      final Map<String, SearchMappings> searchMappings)
  {
    this.repoAdapter = checkNotNull(repoAdapter);
    this.searchParams = checkNotNull(searchMappings).entrySet().stream()
        .flatMap(e -> stream(e.getValue().get().spliterator(), true))
        .collect(toMap(SearchMapping::getAlias, SearchMapping::getAttribute));
    this.assetSearchParams = searchParams.entrySet().stream()
        .filter(e -> e.getValue().startsWith(ASSET_PREFIX))
        .collect(toMap(Entry::getKey, Entry::getValue));
    
    log.debug(STR."Initialized SearchUtils with \{searchParams.size()} search parameters and \{assetSearchParams.size()} asset search parameters");
  }

  /**
   * Returns all search parameters mapping.
   *
   * @return Map of search parameter aliases to their attribute names
   */
  public Map<String, String> getSearchParameters() {
    return searchParams;
  }

  /**
   * Returns asset-specific search parameters mapping.
   *
   * @return Map of asset search parameter aliases to their attribute names
   */
  public Map<String, String> getAssetSearchParameters() {
    return assetSearchParams;
  }

  /**
   * Retrieves a repository by name, ensuring it is readable.
   *
   * @param repository the repository name
   * @return the Repository object
   */
  public Repository getRepository(final String repository) {
    Repository repo = repoAdapter.getReadableRepository(repository);
    log.debug(STR."Retrieved repository: \{repository}");
    return repo;
  }

  /**
   * Builds a collection of {@link SearchFilter} based on configured search parameters.
   *
   * @param uriInfo {@link UriInfo} to extract query parameters from
   * @return List of search filters
   */
  public List<SearchFilter> getSearchFilters(final UriInfo uriInfo) {
    List<SearchFilter> filters = convertParameters(uriInfo, Arrays.asList(CONTINUATION_TOKEN, SORT_FIELD, SORT_DIRECTION));
    log.debug(STR."Created \{filters.size()} search filters from URI parameters");
    return filters;
  }

  /**
   * Converts URI parameters to search filters, excluding specified keys.
   *
   * @param uriInfo the URI info containing query parameters
   * @param excludedKeys keys to exclude from conversion
   * @return List of search filters
   */
  private List<SearchFilter> convertParameters(final UriInfo uriInfo, final List<String> excludedKeys) {
    return uriInfo.getQueryParameters().entrySet().stream()
        .filter(entry -> !excludedKeys.contains(entry.getKey()))
        .flatMap(entry -> entry.getValue().stream()
            .map(value -> {
                // Use pattern matching to handle different value types
                return switch (value) {
                    case String s when s.isEmpty() -> {
                        log.trace(STR."Empty value for parameter: \{entry.getKey()}");
                        yield createSearchFilter(entry.getKey(), s);
                    }
                    case String s when s.startsWith("*") && s.endsWith("*") -> {
                        log.trace(STR."Wildcard search value for parameter: \{entry.getKey()}");
                        yield createSearchFilter(entry.getKey(), s);
                    }
                    case String s -> {
                        log.trace(STR."Standard search value for parameter: \{entry.getKey()}");
                        yield createSearchFilter(entry.getKey(), s);
                    }
                    case null -> {
                        log.warn(STR."Null value for parameter: \{entry.getKey()}, using empty string");
                        yield createSearchFilter(entry.getKey(), "");
                    }
                    default -> {
                        log.warn(STR."Unexpected value type for parameter: \{entry.getKey()}, using toString()");
                        yield createSearchFilter(entry.getKey(), value.toString());
                    }
                };
            }))
        .collect(toList());
  }
  
  /**
   * Creates a SearchFilter with the appropriate key mapping.
   *
   * @param paramKey the original parameter key
   * @param value the parameter value
   * @return a new SearchFilter
   */
  private SearchFilter createSearchFilter(final String paramKey, final String value) {
    String key = searchParams.getOrDefault(paramKey, paramKey);
    return new SearchFilter(key, value);
  }

  /**
   * Checks if the parameter is an asset search parameter.
   *
   * @param assetSearchParam the parameter to check
   * @return true if it's an asset search parameter, false otherwise
   */
  public boolean isAssetSearchParam(final String assetSearchParam) {
    if (assetSearchParam == null) {
      log.warn("Null asset search parameter provided");
      return false;
    }
    return assetSearchParams.containsKey(assetSearchParam) || isFullAssetAttributeName(assetSearchParam);
  }

  /**
   * Checks if the parameter is a full asset attribute name.
   *
   * @param assetSearchParam the parameter to check
   * @return true if it's a full asset attribute name, false otherwise
   */
  public boolean isFullAssetAttributeName(final String assetSearchParam) {
    return Optional.ofNullable(assetSearchParam)
        .map(param -> param.startsWith(ASSET_PREFIX))
        .orElse(false);
  }

  /**
   * Gets the full asset attribute name for a key.
   *
   * @param key the key to get the full asset attribute name for
   * @return the full asset attribute name
   */
  public String getFullAssetAttributeName(final String key) {
    if (key == null) {
      log.warn("Null key provided for getFullAssetAttributeName");
      return null;
    }
    return isFullAssetAttributeName(key) ? key : getAssetSearchParameters().get(key);
  }
}