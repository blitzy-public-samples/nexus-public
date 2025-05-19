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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

  List<FluentAsset> browse(final Repository repository, final String continuationToken) {
    log.debug(STR."Browsing assets for repository \{repository.getName()} with token \{continuationToken}");
    
    List<FluentAsset> permittedAssets = new ArrayList<>();
    String internalToken = toInternalToken(continuationToken);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Get the first batch of assets
      Continuation<FluentAsset> assetContinuation = getAssets(repository, internalToken);
      
      // Process assets concurrently until we reach the page size limit or run out of assets
      while (permittedAssets.size() < PAGE_SIZE_LIMIT && !assetContinuation.isEmpty()) {
        // Create a copy of the current continuation to use in the async task
        Continuation<FluentAsset> currentContinuation = assetContinuation;
        
        // Get the next continuation token for the next iteration
        String nextToken = assetContinuation.nextContinuationToken();
        
        // Process current batch of assets asynchronously
        CompletableFuture<List<FluentAsset>> future = CompletableFuture.supplyAsync(
            () -> removeAssetsNotPermitted(repository, currentContinuation),
            executor
        );
        
        // Start fetching the next batch of assets while processing the current batch
        assetContinuation = getAssets(repository, nextToken);
        
        // Add the permitted assets from the current batch
        permittedAssets.addAll(future.join());
      }
      
      log.debug(STR."Found \{permittedAssets.size()} permitted assets for repository \{repository.getName()}");
      return trim(permittedAssets, PAGE_SIZE_LIMIT);
    } catch (Exception e) {
      log.error(STR."Error browsing assets for repository \{repository.getName()}: \{e.getMessage()}", e);
      throw e;
    }
  }

  private Continuation<FluentAsset> getAssets(Repository repository, final String continuationToken) {
    // Helper for users, if they query by group chances are they want the list of member content
    return switch (repository.getType().getValue()) {
      case GroupType.NAME -> repository.facet(ContentFacet.class).assets().withOnlyGroupMemberContent()
          .browse(PAGE_SIZE_LIMIT, continuationToken);
      default -> repository.facet(ContentFacet.class).assets().browse(PAGE_SIZE_LIMIT, continuationToken);
    };
  }

  private List<FluentAsset> removeAssetsNotPermitted(
      final Repository repository,
      final Continuation<FluentAsset> assets)
  {
    return assets.stream()
        .filter(assetPermitted(repository.getFormat().getValue(), repository.getName()))
        .collect(toList());
  }

  Predicate<FluentAsset> assetPermitted(final String format, final String... repositoryNames) {
    return asset -> contentAuthHelper.checkPathPermissions(asset.path(), format, repositoryNames);
  }

  static String toInternalToken(final String continuationToken) {
    if (continuationToken != null) {
      return toInternalId(EntityHelper.id(continuationToken)) + EMPTY;
    }
    return null;
  }

  static <T> List<T> trim(List<T> items, final int limit) {
    if (items.size() > limit) {
      // Use subList to create a view of the first 'limit' elements
      return items.subList(0, limit);
    }
    return items;
  }
}