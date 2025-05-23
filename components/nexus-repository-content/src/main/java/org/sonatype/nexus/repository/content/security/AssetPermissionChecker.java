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
package org.sonatype.nexus.repository.content.security;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.MDC;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.RepositoryContent;
import org.sonatype.nexus.repository.content.facet.ContentFacetFinder;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.security.VariableResolverAdapterManager;
import org.sonatype.nexus.selector.VariableSource;

import com.google.common.collect.ImmutableList;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link Asset} permission checks.
 *
 * @since 3.26
 */
@Named
@Singleton
public class AssetPermissionChecker
{
  private final RepositoryManager repositoryManager;

  private final ContentFacetFinder contentFacetFinder;

  private final ContentPermissionChecker contentPermissionChecker;

  private final VariableResolverAdapterManager variableResolverAdapterManager;
  
  // Virtual Thread executor for concurrent permission checks
  private final ExecutorService virtualThreadExecutor;
  
  // Logger for improved diagnostics with String Templates
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AssetPermissionChecker.class);

  @Inject
  public AssetPermissionChecker(
      final RepositoryManager repositoryManager,
      final ContentFacetFinder contentFacetFinder,
      final ContentPermissionChecker contentPermissionChecker,
      final VariableResolverAdapterManager variableResolverAdapterManager)
  {
    this.repositoryManager = checkNotNull(repositoryManager);
    this.contentFacetFinder = checkNotNull(contentFacetFinder);
    this.contentPermissionChecker = checkNotNull(contentPermissionChecker);
    this.variableResolverAdapterManager = checkNotNull(variableResolverAdapterManager);
    
    // Initialize Virtual Thread executor for concurrent permission checks
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    log.debug(STR."Initialized AssetPermissionChecker with Virtual Thread executor");
  }

  /**
   * Finds which of the containing repositories permits access to each of the assets for the given action.
   *
   * Assets that have no permitting repository are not included in the returned mapping.
   *
   * @return mapping from asset to the repository that permits the user to do the action
   */
  /**
   * Finds which of the containing repositories permits access to each of the assets for the given action.
   *
   * Assets that have no permitting repository are not included in the returned mapping.
   * This implementation uses Java 21 Virtual Threads for concurrent permission checks,
   * significantly improving performance for large asset collections.
   *
   * @return mapping from asset to the repository that permits the user to do the action
   */
  public Stream<Entry<Asset, String>> findPermittedAssets(
      final Collection<? extends Asset> assets,
      final String format,
      final String action)
  {
    if (assets.isEmpty()) {
      return Stream.empty();
    }

    VariableResolverAdapter variableResolverAdapter = variableResolverAdapterManager.get(format);

    // only do this once - assumes all assets passed in were uploaded to the same repository
    List<String> containingRepositoryNames = containingRepositoryNames(format, assets.iterator().next());
    
    int assetCount = assets.size();
    log.debug(STR."Checking permissions for \{assetCount} assets with format \{format} and action \{action}");
    
    // Capture current thread context for propagation to Virtual Threads
    Subject currentSubject = SecurityUtils.getSubject();
    Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    // Use CompletableFuture with Virtual Threads for concurrent permission checks
    List<CompletableFuture<Entry<Asset, String>>> futures = assets.stream()
        .map(asset -> CompletableFuture.supplyAsync(() -> {
          try {
            // Propagate thread context to Virtual Thread
            if (currentSubject != null) {
              // Create a callable that will execute with the propagated subject
              Supplier<Entry<Asset, String>> securityContextPropagator = () -> {
                // Restore MDC context
                if (mdcContext != null) {
                  MDC.setContextMap(mdcContext);
                }
                
                try {
                  // Perform permission check with propagated context
                  VariableSource source = variableResolverAdapter.fromPath(asset.path(), format);
                  return findPermittingRepository(containingRepositoryNames, format, action, source)
                      .map(r -> createAssetEntry(asset, r))
                      .orElse(null);
                } finally {
                  // Clean up MDC context
                  MDC.clear();
                }
              };
              
              // Execute with the propagated subject
              return currentSubject.execute(securityContextPropagator);
            } else {
              // No subject to propagate, just restore MDC context
              if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
              }
              
              try {
                VariableSource source = variableResolverAdapter.fromPath(asset.path(), format);
                return findPermittingRepository(containingRepositoryNames, format, action, source)
                    .map(r -> createAssetEntry(asset, r))
                    .orElse(null);
              } finally {
                MDC.clear();
              }
            }
          } catch (Exception e) {
            log.error(STR."Error checking permissions for asset \{asset.path()} with format \{format}: \{e.getMessage()}", e);
            return null;
          }
        }, virtualThreadExecutor))
        .collect(Collectors.toList());
    
    // Wait for all futures to complete and filter out nulls
    Stream<Entry<Asset, String>> result = futures.stream()
        .map(CompletableFuture::join)
        .filter(Objects::nonNull);
    
    log.debug(STR."Completed permission checks using Virtual Threads");
    return result;
  }
  
  /**
   * Creates an Entry with pattern matching for instanceof check.
   * Uses Java 21 pattern matching for instanceof to simplify type checking and casting.
   *
   * @param asset The asset object to check and convert
   * @param repository The repository name that permits access to the asset
   * @return Entry mapping the asset to the permitting repository
   * @throws IllegalArgumentException if the asset is not an instance of Asset
   */
  private Entry<Asset, String> createAssetEntry(Object asset, String repository) {
    if (asset instanceof Asset assetInstance) {
      return new SimpleImmutableEntry<>(assetInstance, repository);
    }
    // This should never happen as we're always passing Asset instances
    throw new IllegalArgumentException(STR."Unexpected asset type: \{asset.getClass().getName()}");
  }

  /**
   * Finds which of the containing repositories permits access to the asset for the given action.
   * This method checks permissions for a single asset and is optimized for that case.
   *
   * @param asset The asset to check permissions for
   * @param format The repository format
   * @param action The action being performed
   * @return the repository that permits the user to do the action, if it exists
   */
  public Optional<String> isPermitted(final Asset asset, final String format, final String action) {
    log.debug(STR."Checking permission for single asset \{asset.path()} with format \{format} and action \{action}");
    
    VariableResolverAdapter variableResolverAdapter = variableResolverAdapterManager.get(format);

    List<String> containingRepositoryNames = containingRepositoryNames(format, asset);
    VariableSource source = variableResolverAdapter.fromPath(asset.path(), format);

    Optional<String> result = findPermittingRepository(containingRepositoryNames, format, action, source);
    
    log.debug(STR."Permission check result for asset \{asset.path()}: \{result.isPresent() ? "permitted by " + result.get() : "denied"}");
    return result;
  }

  /**
   * Finds which of the containing repositories permits access to the asset for the given action.
   * This method is called by both the single-asset and multi-asset permission check methods.
   *
   * @param containingRepositoryNames List of repository names to check permissions against
   * @param format The repository format
   * @param action The action being performed
   * @param variables The variable source for permission evaluation
   * @return The first repository that permits the action, if any
   */
  private Optional<String> findPermittingRepository(
      final List<String> containingRepositoryNames,
      final String format,
      final String action,
      final VariableSource variables)
  {
    // Using thread-safe stream operations that work well with Virtual Threads
    Optional<String> result = containingRepositoryNames.stream()
        .filter(r -> contentPermissionChecker.isPermitted(r, format, action, variables))
        .findFirst();
    
    if (log.isTraceEnabled()) {
      if (result.isPresent()) {
        log.trace(STR."Found permitting repository: \{result.get()} for format \{format} and action \{action}");
      } else {
        log.trace(STR."No permitting repository found for format \{format} and action \{action}");
      }
    }
    
    return result;
  }

  /**
   * Returns the list of repositories that contain the given content by virtue of group membership.
   * Uses Java 21 pattern matching with Optional.isEmpty() instead of !isPresent().
   *
   * @param format The repository format
   * @param repositoryContent The repository content to find containing repositories for
   * @return List of repository names that contain the content
   */
  private List<String> containingRepositoryNames(final String format, final RepositoryContent repositoryContent) {
    Optional<Repository> repository = contentFacetFinder.findRepository(format, repositoryContent);
    if (repository.isEmpty()) {
      log.debug(STR."No repository found for content with format \{format}");
      return ImmutableList.of();
    }

    String repositoryName = repository.get().getName();
    List<String> containingRepositoryNames = repositoryManager.findContainingGroups(repositoryName);
    containingRepositoryNames.add(0, repositoryName);

    log.debug(STR."Found \{containingRepositoryNames.size()} repositories containing content from \{repositoryName}");
    return containingRepositoryNames;
  }
}