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
package org.sonatype.nexus.repository.rest;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.repository.rest.api.ComponentXO;
import org.sonatype.nexus.repository.rest.internal.resources.SearchResource;
import org.sonatype.nexus.repository.search.ComponentSearchResult;

/**
 * Extension point for the {@link SearchResource} class
 *
 * @since 3.8
 */
public interface SearchResourceExtension
{
  /**
   * Update the {@link ComponentXO} with data from the {@link ComponentSearchResult}
   *
   * @since 3.38
   */
  ComponentXO updateComponentXO(ComponentXO componentXO, ComponentSearchResult hit);

  /**
   * Update the {@link ComponentXO} with data from the {@link ComponentSearchResult} using Java 21 Record Patterns
   * for more efficient and type-safe data extraction.
   * 
   * <p>This method provides an optimized implementation that leverages Java 21 Record Patterns to efficiently
   * extract and process data from the ComponentSearchResult. Implementations should use pattern matching
   * to handle different record types within the search result.</p>
   * 
   * <p>Example implementation using Record Patterns:</p>
   * <pre>
   * {@code
   * @Override
   * public ComponentXO updateComponentXOWithRecordPattern(ComponentXO componentXO, ComponentSearchResult hit) {
   *   // Using record pattern matching for type-safe data extraction
   *   if (hit instanceof ComponentSearchResult(String name, String format, var attributes)) {
   *     // Process with direct access to record components
   *     componentXO.setName(name);
   *     componentXO.setFormat(format);
   *     
   *     // Process nested records if applicable
   *     if (attributes instanceof AttributeRecord(String key, String value)) {
   *       componentXO.addAttribute(key, value);
   *     }
   *   }
   *   return componentXO;
   * }
   * }
   * </pre>
   *
   * <p>Default implementation delegates to the standard {@link #updateComponentXO} method.</p>
   *
   * @param componentXO the component transfer object to update
   * @param hit the search result containing component data
   * @return the updated component transfer object
   * @since 3.60
   */
  default ComponentXO updateComponentXOWithRecordPattern(ComponentXO componentXO, ComponentSearchResult hit) {
    return updateComponentXO(componentXO, hit);
  }

  /**
   * Asynchronously process a batch of search results using Java 21 Virtual Threads for improved concurrency.
   * 
   * <p>This method is designed to efficiently process multiple search results concurrently using Java 21's
   * Virtual Threads. It's particularly beneficial for I/O-bound operations like enriching search results
   * with additional data from external sources.</p>
   * 
   * <p>Implementation best practices:</p>
   * <ul>
   *   <li>Use {@code Executors.newVirtualThreadPerTaskExecutor()} for creating virtual threads</li>
   *   <li>Avoid thread-local variables that might cause memory leaks with virtual threads</li>
   *   <li>Prefer non-blocking I/O operations to maximize virtual thread efficiency</li>
   *   <li>Use record patterns for efficient data extraction from search results</li>
   * </ul>
   * 
   * <p>Example implementation:</p>
   * <pre>
   * {@code
   * @Override
   * public CompletableFuture<List<ComponentXO>> processSearchResultsAsync(
   *     List<ComponentXO> components, List<ComponentSearchResult> hits) {
   *   
   *   try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   *     List<CompletableFuture<ComponentXO>> futures = IntStream.range(0, components.size())
   *         .mapToObj(i -> CompletableFuture.supplyAsync(
   *             () -> updateComponentXOWithRecordPattern(components.get(i), hits.get(i)),
   *             executor))
   *         .collect(Collectors.toList());
   *     
   *     return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
   *         .thenApply(v -> futures.stream()
   *             .map(CompletableFuture::join)
   *             .collect(Collectors.toList()));
   *   }
   * }
   * }
   * </pre>
   *
   * <p>Default implementation processes each result sequentially using {@link #updateComponentXOWithRecordPattern}.</p>
   *
   * @param components the list of component transfer objects to update
   * @param hits the list of search results containing component data
   * @return a CompletableFuture that completes with the list of updated component transfer objects
   * @since 3.60
   */
  default CompletableFuture<List<ComponentXO>> processSearchResultsAsync(
      List<ComponentXO> components, List<ComponentSearchResult> hits) {
    
    // Default sequential implementation
    for (int i = 0; i < components.size(); i++) {
      components.set(i, updateComponentXOWithRecordPattern(components.get(i), hits.get(i)));
    }
    return CompletableFuture.completedFuture(components);
  }
}