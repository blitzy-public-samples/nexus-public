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
package org.sonatype.nexus.repository.content.fluent.internal;

import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentQuery;
import org.sonatype.nexus.repository.content.fluent.constraints.FluentQueryConstraint;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.emptyList;

/**
 * {@link FluentQuery} implementation for {@link FluentAsset}s.
 *
 * @since 3.26
 */
public class FluentAssetQueryImpl
    implements FluentQuery<FluentAsset>
{
  private static final Logger log = Logger.getLogger(FluentAssetQueryImpl.class.getName());
  
  private final FluentAssetsImpl assets;

  private final String kind;

  private final String filter;

  private final Map<String, Object> filterParams;

  private final List<FluentQueryConstraint> constraints;

  /**
   * Constructor for constraint-based query.
   */
  FluentAssetQueryImpl(final FluentAssetsImpl assets, final List<FluentQueryConstraint> constraints) {
    this.assets = checkNotNull(assets);
    this.constraints = checkNotNull(constraints);
    this.filter = null;
    this.filterParams = null;
    this.kind = null;
    
    log.fine(STR."Creating constraint-based query with \{constraints.size()} constraints");
  }

  /**
   * Constructor for kind-based query.
   */
  FluentAssetQueryImpl(final FluentAssetsImpl assets, final String kind) {
    this.assets = checkNotNull(assets);
    this.kind = checkNotNull(kind);
    this.filter = null;
    this.filterParams = null;
    this.constraints = emptyList();
    
    log.fine(STR."Creating kind-based query for kind: \{kind}");
  }

  /**
   * Constructor for filter-based query.
   */
  FluentAssetQueryImpl(
      final FluentAssetsImpl assets,
      final String filter,
      final Map<String, Object> filterParams)
  {
    this.assets = checkNotNull(assets);
    this.kind = null;
    this.filter = checkNotNull(filter);
    this.filterParams = checkNotNull(filterParams);
    this.constraints = emptyList();
    
    log.fine(STR."Creating filter-based query with filter: \{filter}");
  }

  @Override
  public int count() {
    log.fine(STR."Counting assets with query type: \{determineQueryType()}");
    return assets.doCount(kind, filter, filterParams);
  }

  @Override
  public Continuation<FluentAsset> browse(final int limit, final String continuationToken) {
    log.fine(STR."Browsing assets with limit: \{limit}, continuationToken: \{continuationToken}");
    
    // Use virtual thread for pagination operations to improve concurrent query performance
    var result = Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
        assets.doBrowse(limit, continuationToken, kind, filter, filterParams, constraints)
    ).join();
    
    // Log the number of results found
    if (result != null) {
      log.fine(STR."Found \{result.size()} assets in browse operation");
    }
    
    return result;
  }

  @Override
  public Continuation<FluentAsset> browseEager(final int limit, @Nullable final String continuationToken) {
    log.fine(STR."BrowseEager operation requested but not supported");
    throw new UnsupportedOperationException(STR."BrowseEager operation is not supported for \{this.getClass().getSimpleName()}");
  }
  
  /**
   * Determines the type of query based on which parameters are set.
   * Uses pattern matching for switch to provide more concise and readable code.
   */
  private String determineQueryType() {
    return switch (this) {
      case FluentAssetQueryImpl q when !q.constraints.isEmpty() -> "CONSTRAINT";
      case FluentAssetQueryImpl q when q.kind != null -> "KIND";
      case FluentAssetQueryImpl q when q.filter != null -> "FILTER";
      default -> "UNKNOWN";
    };
  }
  
  /**
   * Enhances the browse operation with additional capabilities using Java 21 features.
   * This method demonstrates how we could use Sequenced Collections in the future
   * when the Continuation interface is updated to support it.
   * 
   * @param limit The maximum number of results to return
   * @param continuationToken The token indicating where to start returning results from
   * @return A continuation of results
   */
  private Continuation<FluentAsset> enhancedBrowse(final int limit, final String continuationToken) {
    // Use pattern matching to handle different query types
    var queryType = switch (this) {
      case FluentAssetQueryImpl q when !q.constraints.isEmpty() -> "CONSTRAINT";
      case FluentAssetQueryImpl q when q.kind != null -> "KIND";
      case FluentAssetQueryImpl q when q.filter != null -> "FILTER";
      default -> "UNKNOWN";
    };
    
    // Use string templates for logging
    log.fine(STR."Performing enhanced browse operation with query type: \{queryType}");
    
    // Use virtual threads for better concurrency
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
        assets.doBrowse(limit, continuationToken, kind, filter, filterParams, constraints)
    ).join();
    
    // In the future, when Continuation implements SequencedCollection:
    // return continuation.reversed(); // For reverse order browsing
  }
}