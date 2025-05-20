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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.search.ComponentSearchResult;
import org.sonatype.nexus.repository.search.ComponentSearchResult.ComponentRecord;
import org.sonatype.nexus.repository.search.SearchResponse;

/**
 * Generates search results consumable by the UI
 *
 * @since 3.14
 */
@Named
@Singleton
public class SearchResultsGeneratorImpl
    implements SearchResultsGenerator
{
  private final Map<String, SearchResultComponentGenerator> searchResultComponentGeneratorMap;

  private final SearchResultComponentGenerator defaultSearchResultComponentGenerator;

  @Inject
  SearchResultsGeneratorImpl(
      final Map<String, SearchResultComponentGenerator> searchResultComponentGeneratorMap)
  {
    this.searchResultComponentGeneratorMap = searchResultComponentGeneratorMap;
    this.defaultSearchResultComponentGenerator = searchResultComponentGeneratorMap
        .get(DefaultSearchResultComponentGenerator.DEFAULT_SEARCH_RESULT_COMPONENT_GENERATOR_KEY);
  }

  @Override
  public List<ComponentSearchResult> getSearchResultList(final SearchResponse response) {
    Set<String> componentIdSet = new HashSet<>();

    // Using enhanced type inference with Java 21
    return response.getSearchResults().stream()
        .map(processComponent(componentIdSet))
        .filter(Objects::nonNull)
        .toList(); // Using toList() instead of collect(Collectors.toList()) in Java 21
  }
  
  /**
   * Creates a function to process a component search result using pattern matching.
   * 
   * @param componentIdSet the set of component IDs to track processed components
   * @return a function that processes a component search result
   */
  private Function<ComponentSearchResult, ComponentSearchResult> processComponent(final Set<String> componentIdSet) {
    return component -> {
      // Using Record Pattern matching with Java 21
      if (component instanceof ComponentSearchResult result 
          && result.toRecord() instanceof ComponentRecord(var id, var repositoryName, var group, var name, 
                                                         var version, var format, var lastDownloaded, 
                                                         var lastModified, var assets, var annotations)) {
        
        // Using Pattern Matching for switch with Java 21 to select the appropriate generator
        SearchResultComponentGenerator generator = switch (format) {
          case null -> defaultSearchResultComponentGenerator;
          case String s when searchResultComponentGeneratorMap.containsKey(s) -> 
              searchResultComponentGeneratorMap.get(s);
          default -> defaultSearchResultComponentGenerator;
        };
        
        ComponentSearchResult hit = generator.from(component, componentIdSet);
        if (hit != null) {
          componentIdSet.add(hit.getId());
        }
        return hit;
      }
      return null;
    };
  }
}
