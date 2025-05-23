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
package org.sonatype.nexus.repository.content.rest.internal.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.entity.EntityHelper;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.selector.ContentAuthHelper;
import org.sonatype.nexus.repository.types.GroupType;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.sonatype.nexus.repository.content.store.InternalIds.toInternalId;

/**
 * Support class for {@link AssetsResource} which fetches and returns only assets that the user is permitted
 * to view according to {@link ContentAuthHelper#checkPathPermissions(String, String, String...)}
 *
 * @since 3.27
 */
abstract class AssetsResourceSupport
    extends ComponentSupport
{
  /**
   * Limit the number of assets returned per page. This value is aligned with ComponentsResourceSupport.PAGE_SIZE_LIMIT.
   */
  protected static final int PAGE_SIZE_LIMIT = 100;

  private final ContentAuthHelper contentAuthHelper;

  AssetsResourceSupport(final ContentAuthHelper contentAuthHelper) {
    this.contentAuthHelper = checkNotNull(contentAuthHelper);
  }

  /**
   * Browse assets in the repository with optimized concurrent retrieval using Virtual Threads.
   * 
   * @param repository the repository to browse
   * @param continuationToken token for pagination
   * @return list of assets the user is permitted to view
   */
  List<FluentAsset> browse(final Repository repository, final String continuationToken) {
    List<FluentAsset> permittedAssets = new ArrayList<>();
    String internalToken = toInternalToken(continuationToken);
    Continuation<FluentAsset> assetContinuation = getAssets(repository, internalToken);

    log.debug(STR."Browsing assets in repository \{repository.getName()} with token: \{continuationToken}");
    
    // Create a virtual thread executor for concurrent asset retrieval
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      while (permittedAssets.size() < PAGE_SIZE_LIMIT && !assetContinuation.isEmpty()) {
        // Capture the current continuation for the task
        Continuation<FluentAsset> currentContinuation = assetContinuation;
        
        // Submit task to retrieve permitted assets concurrently
        Future<List<FluentAsset>> future = executor.submit(() -> 
            removeAssetsNotPermitted(repository, currentContinuation));
        
        // Get the next batch of assets while processing the current batch
        assetContinuation = getAssets(repository, assetContinuation.nextContinuationToken());
        
        // Add the permitted assets to our result list
        permittedAssets.addAll(future.get());
      }
      
      log.debug(STR."Successfully retrieved \{permittedAssets.size()} assets using virtual threads");
    } catch (Exception e) {
      log.error(STR."Error retrieving assets using virtual threads: \{e.getMessage()}", e);
      // Fallback to sequential processing if virtual threads fail
      while (permittedAssets.size() < PAGE_SIZE_LIMIT && !assetContinuation.isEmpty()) {
        permittedAssets.addAll(removeAssetsNotPermitted(repository, assetContinuation));
        assetContinuation = getAssets(repository, assetContinuation.nextContinuationToken());
      }
      log.debug(STR."Fallback: Retrieved \{permittedAssets.size()} assets sequentially");
    }
    
    return trim(permittedAssets, PAGE_SIZE_LIMIT);
  }

  private Continuation<FluentAsset> getAssets(Repository repository, final String continuationToken) {
    // Using pattern matching for switch to check repository type
    String repoType = repository.getType().getValue();
    return switch (repoType) {
      case GroupType.NAME -> {
        log.debug(STR."Getting assets from group repository: \{repository.getName()}");
        yield repository.facet(ContentFacet.class).assets().withOnlyGroupMemberContent()
            .browse(PAGE_SIZE_LIMIT, continuationToken);
      }
      default -> {
        log.debug(STR."Getting assets from repository: \{repository.getName()}");
        yield repository.facet(ContentFacet.class).assets().browse(PAGE_SIZE_LIMIT, continuationToken);
      }
    };
  }

  /**
   * Filter assets to only include those the user is permitted to view.
   * 
   * @param repository the repository containing the assets
   * @param assets the assets to filter
   * @return list of permitted assets
   */
  private List<FluentAsset> removeAssetsNotPermitted(
      final Repository repository,
      final Continuation<FluentAsset> assets)
  {
    String format = repository.getFormat().getValue();
    String repoName = repository.getName();
    
    log.trace(STR."Filtering assets for format: \{format}, repository: \{repoName}");
    
    return assets.stream()
        .filter(assetPermitted(format, repoName))
        .collect(toList());
  }

  /**
   * Creates a predicate to check if an asset is permitted to be viewed by the current user.
   * 
   * @param format the repository format
   * @param repositoryNames the repository names
   * @return predicate that returns true if the asset is permitted
   */
  Predicate<FluentAsset> assetPermitted(final String format, final String... repositoryNames) {
    return asset -> {
      String path = asset.path();
      boolean permitted = contentAuthHelper.checkPathPermissions(path, format, repositoryNames);
      if (!permitted && log.isTraceEnabled()) {
        log.trace(STR."Asset path not permitted: \{path}");
      }
      return permitted;
    };
  }

  /**
   * Converts an external continuation token to an internal token format.
   * 
   * @param continuationToken the external continuation token
   * @return the internal token format, or null if the input is null
   */
  static String toInternalToken(final String continuationToken) {
    if (continuationToken != null) {
      String internalId = toInternalId(EntityHelper.id(continuationToken));
      return STR."\{internalId}\{EMPTY}";
    }
    return null;
  }

  /**
   * Trims a list to the specified limit using Sequenced Collections approach.
   * 
   * @param <T> the type of elements in the list
   * @param items the list to trim
   * @param limit the maximum number of items to keep
   * @return the trimmed list
   */
  static <T> List<T> trim(List<T> items, final int limit) {
    // Using pattern matching with Sequenced Collections approach for trimming
    if (items instanceof List<?> sequencedList) {
      int size = sequencedList.size();
      if (size > limit) {
        return sequencedList.subList(0, limit);
      }
    }
    return items;
  }
}