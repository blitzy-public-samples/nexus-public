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
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.search.ComponentSearchResult;
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
  /**
   * Record pattern for component search result data extraction
   * Used for efficient data extraction from search results
   *
   * @param format The format of the component
   * @param id The ID of the component
   * @since Java 21
   */
  private record ComponentData(String format, String id) {}

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
    var componentIdSet = new HashSet<String>();

    return response.getSearchResults().stream()
        .map(processComponent(componentIdSet))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }
  
  /**
   * Creates a function to process a component search result using pattern matching
   * This leverages Java 21's pattern matching for more efficient data extraction
   *
   * @param componentIdSet Set of component IDs to track processed components
   * @return A function that processes component search results
   */
  private Function<ComponentSearchResult, ComponentSearchResult> processComponent(final Set<String> componentIdSet) {
    return component -> {
      // Using record pattern for efficient data extraction
      var componentData = new ComponentData(component.getFormat(), component.getId());
      
      // Using pattern matching for different result types
      var generator = switch (componentData) {
        // When format is null, use default generator
        case ComponentData(null, var id) -> defaultSearchResultComponentGenerator;
        
        // When format is present, look up specific generator or use default
        case ComponentData(var format, var id) -> searchResultComponentGeneratorMap
            .getOrDefault(format, defaultSearchResultComponentGenerator);
      };
      
      // Process the component with the selected generator
      var result = generator.from(component, componentIdSet);
      
      // Track processed component IDs
      if (result != null) {
        componentIdSet.add(result.getId());
      }
      
      return result;
    };
  }
}
