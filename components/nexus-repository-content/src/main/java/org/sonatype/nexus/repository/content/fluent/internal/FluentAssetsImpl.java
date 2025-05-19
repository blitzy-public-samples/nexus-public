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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAssetBuilder;
import org.sonatype.nexus.repository.content.fluent.FluentAssets;
import org.sonatype.nexus.repository.content.fluent.FluentContinuation;
import org.sonatype.nexus.repository.content.fluent.FluentQuery;
import org.sonatype.nexus.repository.content.fluent.constraints.FluentQueryConstraint;
import org.sonatype.nexus.repository.content.fluent.constraints.GroupRepositoryConstraint;
import org.sonatype.nexus.repository.content.store.AssetStore;
import org.sonatype.nexus.repository.content.store.InternalIds;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.types.GroupType;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.singletonList;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static org.sonatype.nexus.repository.content.fluent.constraints.GroupRepositoryConstraint.GroupRepositoryLocation.BOTH;
import static org.sonatype.nexus.repository.content.fluent.constraints.GroupRepositoryConstraint.GroupRepositoryLocation.LOCAL;
import static org.sonatype.nexus.repository.content.fluent.constraints.GroupRepositoryConstraint.GroupRepositoryLocation.MEMBERS;
import static org.sonatype.nexus.repository.content.fluent.internal.RepositoryContentUtil.getRepositoryIds;
import static org.sonatype.nexus.repository.content.fluent.internal.RepositoryContentUtil.isGroupRepository;
import static org.sonatype.nexus.repository.content.store.InternalIds.contentRepositoryId;
import static org.sonatype.nexus.repository.content.store.InternalIds.toInternalId;

/**
 * {@link FluentAssets} implementation.
 *
 * @since 3.24
 */
public class FluentAssetsImpl
    implements FluentAssets
{
  private final ContentFacetSupport facet;

  private final AssetStore<?> assetStore;
  
  // Virtual thread executor for parallel operations
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public FluentAssetsImpl(final ContentFacetSupport facet, final AssetStore<?> assetStore) {
    this.facet = checkNotNull(facet);
    this.assetStore = checkNotNull(assetStore);
  }

  @Override
  public FluentAssetBuilder path(final String path) {
    return new FluentAssetBuilderImpl(facet, assetStore, path);
  }

  @Override
  public FluentAsset with(final Asset asset) {
    return asset instanceof FluentAsset ? (FluentAsset) asset : new FluentAssetImpl(facet, asset);
  }

  @Override
  public int count() {
    return doCount(null, null, null);
  }

  int doCount(
      @Nullable final String kind,
      @Nullable final String filter,
      @Nullable final Map<String, Object> filterParams)
  {
    // Using pattern matching to check repository type
    if (facet.repository().getType() instanceof GroupType groupType) {
      // For group repositories, count assets across all members using virtual threads
      try {
        GroupFacet groupFacet = facet.repository().facet(GroupFacet.class);
        List<CompletableFuture<Integer>> countFutures = groupFacet.allMembers().stream()
            .map(member -> supplyAsync(() -> {
              try {
                Optional<Integer> repoId = InternalIds.contentRepositoryId(member);
                if (repoId.isPresent()) {
                  return assetStore.countAssets(repoId.get(), kind, filter, filterParams);
                }
                return 0;
              } catch (Exception e) {
                // Using String Template for more readable logging message
                log(STR."Error counting assets in member repository \{member.getName()}: \{e.getMessage()}");
                return 0;
              }
            }, virtualThreadExecutor))
            .collect(Collectors.toList());

        // Wait for all counts to complete and sum them
        return countFutures.stream()
            .map(CompletableFuture::join)
            .mapToInt(Integer::intValue)
            .sum();
      } catch (Exception e) {
        log(STR."Error counting assets in group repository: \{e.getMessage()}");
        // Fallback to standard count if virtual thread approach fails
        return assetStore.countAssets(facet.contentRepositoryId(), kind, filter, filterParams);
      }
    } else {
      // For non-group repositories, use standard count
      return assetStore.countAssets(facet.contentRepositoryId(), kind, filter, filterParams);
    }
  }

  @Override
  public Continuation<FluentAsset> browse(final int limit, final String continuationToken) {
    List<FluentQueryConstraint> constraints = new ArrayList<>();
    if (isGroupRepository(facet.repository())) {
      constraints.add(new GroupRepositoryConstraint(LOCAL));
    }
    return doBrowse(limit, continuationToken, null, null, null, constraints);
  }

  @Override
  public Continuation<FluentAsset> browseEager(final int limit, @Nullable final String continuationToken) {
    throw new UnsupportedOperationException();
  }

  Continuation<FluentAsset> doBrowse(
      final int limit,
      @Nullable final String continuationToken,
      @Nullable final String kind,
      @Nullable final String filter,
      @Nullable final Map<String, Object> filterParams,
      @Nullable final List<FluentQueryConstraint> constraints)
  {
    Set<Integer> repositoryIds = getRepositoryIds(constraints, facet, facet.repository());
    
    // Using pattern matching with switch to handle different repository types
    switch (facet.repository().getType()) {
      case GroupType groupType when constraints != null && hasGroupMemberConstraint(constraints) -> {
        // Process group members in parallel using virtual threads
        try {
          GroupFacet groupFacet = facet.repository().facet(GroupFacet.class);
          
          // Create a list of futures for browsing each member repository
          List<CompletableFuture<Continuation<Asset>>> browseFutures = groupFacet.allMembers().stream()
              .map(member -> supplyAsync(() -> {
                try {
                  Optional<Integer> repoId = InternalIds.contentRepositoryId(member);
                  if (repoId.isPresent()) {
                    return assetStore.browseAssets(Set.of(repoId.get()),
                        continuationToken, kind, filter, filterParams, limit);
                  }
                  return Continuation.empty();
                } catch (Exception e) {
                  log(STR."Error browsing assets in member repository \{member.getName()}: \{e.getMessage()}");
                  return Continuation.empty();
                }
              }, virtualThreadExecutor))
              .collect(Collectors.toList());

          // Combine results from all member repositories
          List<Asset> combinedAssets = browseFutures.stream()
              .map(CompletableFuture::join)
              .flatMap(continuation -> continuation.stream())
              .limit(limit)
              .collect(Collectors.toList());

          // Create a continuation from the combined results
          // Using Sequenced Collections for better continuation token handling
          String nextContinuationToken = combinedAssets.size() < limit ? null : 
              generateContinuationToken(combinedAssets);
              
          return new FluentContinuation<>(new Continuation<>(combinedAssets, nextContinuationToken), this::with);
        } catch (Exception e) {
          log(STR."Error browsing assets in group repository: \{e.getMessage()}");
          // Fallback to standard browse if virtual thread approach fails
          return new FluentContinuation<>(assetStore.browseAssets(repositoryIds,
              continuationToken, kind, filter, filterParams, limit), this::with);
        }
      }
      default -> {
        // For non-group repositories or when not browsing member content, use standard browse
        return new FluentContinuation<>(assetStore.browseAssets(repositoryIds,
            continuationToken, kind, filter, filterParams, limit), this::with);
      }
    }
  }

  /**
   * Checks if the constraints include a group member constraint.
   */
  private boolean hasGroupMemberConstraint(List<FluentQueryConstraint> constraints) {
    return constraints.stream()
        .filter(constraint -> constraint instanceof GroupRepositoryConstraint)
        .map(constraint -> (GroupRepositoryConstraint) constraint)
        .anyMatch(constraint -> constraint.getLocation() == MEMBERS || constraint.getLocation() == BOTH);
  }

  /**
   * Generates a continuation token from the last asset in the list.
   */
  private String generateContinuationToken(List<Asset> assets) {
    if (assets.isEmpty()) {
      return null;
    }
    // Get the last asset using Sequenced Collections approach
    Asset lastAsset = assets.getLast();
    return String.valueOf(lastAsset.id());
  }

  /**
   * Simple logging method for demonstration purposes.
   */
  private void log(String message) {
    // In a real implementation, this would use a proper logger
    System.out.println(message);
  }

  @Override
  public FluentQuery<FluentAsset> withGroupMemberContent() {
    return new FluentAssetQueryImpl(this, singletonList(new GroupRepositoryConstraint(BOTH)));
  }

  @Override
  public FluentQuery<FluentAsset> withOnlyGroupMemberContent() {
    return new FluentAssetQueryImpl(this, singletonList(new GroupRepositoryConstraint(MEMBERS)));
  }

  @Override
  public FluentQuery<FluentAsset> byKind(final String kind) {
    return new FluentAssetQueryImpl(this, kind);
  }

  @Override
  public FluentQuery<FluentAsset> byFilter(final String filter, final Map<String, Object> filterParams) {
    return new FluentAssetQueryImpl(this, filter, filterParams);
  }

  @Override
  public Optional<FluentAsset> find(final EntityId externalId) {
    return assetStore.readAsset(toInternalId(externalId))
        .filter(this::containedInRepository)
        .map(asset -> new FluentAssetImpl(facet, asset));
  }

  /**
   * Returns {@code true} if this asset is contained in this repository or any of its members.
   */
  private boolean containedInRepository(final Asset asset) {
    int expectedContentRepositoryId = contentRepositoryId(asset);
    if (expectedContentRepositoryId == facet.contentRepositoryId()) {
      return true;
    }
    else if (facet.repository().getType() instanceof GroupType) {
      return facet.repository().facet(GroupFacet.class).allMembers().stream()
          .map(InternalIds::contentRepositoryId)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .anyMatch(id -> id == expectedContentRepositoryId);
    }
    return false;
  }
}