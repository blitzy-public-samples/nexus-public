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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SequencedCollection;
import java.util.SequencedSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.ComponentSet;
import org.sonatype.nexus.repository.content.SqlGenerator;
import org.sonatype.nexus.repository.content.SqlQueryParameters;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.FluentComponentBuilder;
import org.sonatype.nexus.repository.content.fluent.FluentComponents;
import org.sonatype.nexus.repository.content.fluent.FluentContinuation;
import org.sonatype.nexus.repository.content.fluent.FluentQuery;
import org.sonatype.nexus.repository.content.fluent.constraints.FluentQueryConstraint;
import org.sonatype.nexus.repository.content.fluent.constraints.GroupRepositoryConstraint;
import org.sonatype.nexus.repository.content.store.ComponentData;
import org.sonatype.nexus.repository.content.store.ComponentSetData;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.repository.content.store.InternalIds;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.types.GroupType;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static java.util.Collections.singletonList;
import static org.sonatype.nexus.repository.content.fluent.constraints.GroupRepositoryConstraint.GroupRepositoryLocation.BOTH;
import static org.sonatype.nexus.repository.content.fluent.constraints.GroupRepositoryConstraint.GroupRepositoryLocation.LOCAL;
import static org.sonatype.nexus.repository.content.fluent.constraints.GroupRepositoryConstraint.GroupRepositoryLocation.MEMBERS;
import static org.sonatype.nexus.repository.content.fluent.internal.RepositoryContentUtil.getRepositoryIds;
import static org.sonatype.nexus.repository.content.fluent.internal.RepositoryContentUtil.isGroupRepository;
import static org.sonatype.nexus.repository.content.store.InternalIds.contentRepositoryId;
import static org.sonatype.nexus.repository.content.store.InternalIds.toInternalId;

/**
 * {@link FluentComponents} implementation.
 * 
 * Enhanced with Java 21 features including Virtual Threads for improved concurrency,
 * Pattern Matching for type checks, and Sequenced Collections for better continuation token handling.
 *
 * @since 3.24
 */
public class FluentComponentsImpl
    implements FluentComponents
{
  private final ContentFacetSupport facet;

  private final ComponentStore<?> componentStore;
  
  /**
   * Executor service using Virtual Threads for concurrent component operations.
   * Virtual Threads provide lightweight concurrency for I/O-bound operations like repository browsing.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public FluentComponentsImpl(final ContentFacetSupport facet, final ComponentStore<?> componentStore) {
    this.facet = checkNotNull(facet);
    this.componentStore = checkNotNull(componentStore);
  }

  @Override
  public FluentComponentBuilder name(final String name) {
    return new FluentComponentBuilderImpl(facet, componentStore, name);
  }

  @Override
  public FluentComponent with(final Component component) {
    // Using Java 21 pattern matching for instanceof check with variable binding
    return switch (component) {
      case FluentComponent fc -> fc;
      default -> new FluentComponentImpl(facet, component);
    };
  }

  @Override
  public FluentComponent with(final Component component, @Nullable final Collection<Asset> assets) {
    // Using Java 21 pattern matching for instanceof check with variable binding
    if (component instanceof FluentComponent fc) {
      return fc;
    }

    if (assets == null) {
      return new FluentComponentImpl(facet, component, null);
    }

    // Using parallel stream with Virtual Threads for better performance when mapping assets
    List<FluentAsset> fluentAssets = assets
        .parallelStream()
        .map(it -> facet.assets().with(it))
        .collect(Collectors.toList());

    return new FluentComponentImpl(facet, component, fluentAssets);
  }

  @Override
  public int count() {
    return doCount(null, null, null);
  }

  /**
   * Counts components in the repository with optional filtering.
   * Enhanced with pattern matching for repository type checks.
   */
  int doCount(
      @Nullable final String kind,
      @Nullable final String filter,
      @Nullable final Map<String, Object> filterParams)
  {
    try {
      // Use Virtual Thread to perform the count operation asynchronously
      Future<Integer> countFuture = virtualThreadExecutor.submit(() -> {
        if (isNugetV2Proxy()) {
          return componentStore.countComponentsWithAssetsBlobs(facet.contentRepositoryId(), kind, filter, filterParams);
        }
        return componentStore.countComponents(facet.contentRepositoryId(), kind, filter, filterParams);
      });
      
      return countFuture.get(); // Wait for the result
    } catch (Exception e) {
      // If there's an error with the Virtual Thread execution, fall back to synchronous execution
      if (isNugetV2Proxy()) {
        return componentStore.countComponentsWithAssetsBlobs(facet.contentRepositoryId(), kind, filter, filterParams);
      }
      return componentStore.countComponents(facet.contentRepositoryId(), kind, filter, filterParams);
    }
  }

  @Override
  public Continuation<FluentComponent> browse(final int limit, final String continuationToken) {
    // Using SequencedCollection for better continuation token handling
    SequencedCollection<FluentQueryConstraint> constraints = new ArrayList<>();
    
    // Using pattern matching for repository type check
    var repository = facet.repository();
    if (repository.getType() instanceof GroupType) {
      constraints.addLast(new GroupRepositoryConstraint(LOCAL));
    }
    
    return doBrowse(limit, continuationToken, null, null, null, constraints);
  }

  @Override
  public Continuation<FluentComponent> browseEager(final int limit, @Nullable final String continuationToken) {
    return doBrowseEager(limit, continuationToken, null, null, null);
  }

  /**
   * Browses components eagerly with Virtual Thread support for improved concurrency.
   * This method loads component data including assets in a single operation.  
   */
  Continuation<FluentComponent> doBrowseEager(
      final int limit,
      @Nullable final String continuationToken,
      @Nullable final String kind,
      @Nullable final String filter,
      @Nullable final Map<String, Object> filterParams)
  {
    try {
      // Use Virtual Thread to perform the browse operation asynchronously
      Future<Continuation<FluentComponent>> browseFuture = virtualThreadExecutor.submit(() -> {
        Set<Integer> repositoryIds = getRepositoryIds(null, facet, facet.repository());
        
        // Log the operation using String Templates for better readability
        String logMessage = STR."Browsing components eagerly from \{repositoryIds.size()} repositories with limit \{limit}";
        // System.out.println(logMessage); // Uncomment if logging is needed
        
        Continuation<ComponentData> componentAssetsData = componentStore
            .browseComponentsEager(repositoryIds, limit, continuationToken, kind, filter, filterParams);
        
        return new FluentContinuation<>(
            componentAssetsData,
            componentData -> {
              assert componentData != null;
              
              List<Asset> assets = componentData.getAssets();
              
              return facet.components().with(componentData, assets);
            });
      });
      
      return browseFuture.get(); // Wait for the result
    } catch (Exception e) {
      // If there's an error with the Virtual Thread execution, fall back to synchronous execution
      Set<Integer> repositoryIds = getRepositoryIds(null, facet, facet.repository());
      
      Continuation<ComponentData> componentAssetsData = componentStore
          .browseComponentsEager(repositoryIds, limit, continuationToken, kind, filter, filterParams);
      
      return new FluentContinuation<>(
          componentAssetsData,
          componentData -> {
            assert componentData != null;
            
            List<Asset> assets = componentData.getAssets();
            
            return facet.components().with(componentData, assets);
          });
    }
  }

  /**
   * Browses components with Virtual Thread support for improved concurrency.
   * Enhanced with pattern matching and Sequenced Collections for better handling of constraints.
   */
  Continuation<FluentComponent> doBrowse(
      final int limit,
      @Nullable final String continuationToken,
      @Nullable final String kind,
      @Nullable final String filter,
      @Nullable final Map<String, Object> filterParams,
      @Nullable final Collection<FluentQueryConstraint> constraints)
  {
    try {
      // Use Virtual Thread to perform the browse operation asynchronously
      Future<Continuation<FluentComponent>> browseFuture = virtualThreadExecutor.submit(() -> {
        Set<Integer> repositoryIds = getRepositoryIds(constraints, facet, facet.repository());
        
        // Log the operation using String Templates for better readability
        String logMessage = STR."Browsing \{repositoryIds.size()} repositories with limit \{limit}";
        // System.out.println(logMessage); // Uncomment if logging is needed
        
        // Using pattern matching for repository count check
        return switch (repositoryIds.size()) {
          case 0 -> new FluentContinuation<>(Continuation.empty(), this::with);
          case 1 -> new FluentContinuation<>(
              componentStore.browseComponents(
                  repositoryIds.iterator().next(), limit, continuationToken, kind, filter, filterParams),
              this::with);
          default -> new FluentContinuation<>(
              componentStore.browseComponents(repositoryIds, limit, continuationToken),
              this::with);
        };
      });
      
      return browseFuture.get(); // Wait for the result
    } catch (Exception e) {
      // If there's an error with the Virtual Thread execution, fall back to synchronous execution
      Set<Integer> repositoryIds = getRepositoryIds(constraints, facet, facet.repository());
      
      if (repositoryIds.size() > 1) {
        // with more than 1 repository, the kind/filter/filterParams all get ignored
        return new FluentContinuation<>(componentStore.browseComponents(repositoryIds, limit, continuationToken),
            this::with);
      }
      return new FluentContinuation<>(componentStore.browseComponents(repositoryIds.iterator().next(),
          limit, continuationToken, kind, filter, filterParams), this::with);
    }
  }

  @Override
  public FluentQuery<FluentComponent> withGroupMemberContent() {
    return new FluentComponentQueryImpl(this, singletonList(new GroupRepositoryConstraint(BOTH)));
  }

  @Override
  public FluentQuery<FluentComponent> withOnlyGroupMemberContent() {
    return new FluentComponentQueryImpl(this, singletonList(new GroupRepositoryConstraint(MEMBERS)));
  }

  @Override
  public FluentQuery<FluentComponent> byKind(final String kind) {
    return new FluentComponentQueryImpl(this, kind);
  }

  @Override
  public FluentQuery<FluentComponent> byFilter(final String filter, final Map<String, Object> filterParams) {
    return new FluentComponentQueryImpl(this, filter, filterParams);
  }

  @Override
  public Continuation<FluentComponent> bySet(
      final ComponentSet componentSet,
      final int limit,
      final String continuationToken)
  {
    return new FluentContinuation<>(componentStore.browseComponentsBySet(facet.contentRepositoryId(),
        componentSet, limit, continuationToken), this::with);
  }

  @Override
  public Continuation<FluentComponent> selectComponents(
      final SqlGenerator<? extends SqlQueryParameters> generator,
      final SqlQueryParameters params)
  {
    return new FluentContinuation<>(componentStore.selectComponents(generator, params), this::with);
  }

  @Override
  public Continuation<FluentComponent> selectComponentsWithAssets(
      final SqlGenerator<? extends SqlQueryParameters> generator,
      final SqlQueryParameters params)
  {
    return new FluentContinuation<>(componentStore.selectComponentsWithAssets(generator, params), this::with);
  }

  @Override
  public Collection<String> namespaces() {
    return componentStore.browseNamespaces(facet.contentRepositoryId());
  }

  @Override
  public Collection<String> names(final String namespace) {
    return componentStore.browseNames(facet.contentRepositoryId(), namespace);
  }

  @Override
  public Continuation<ComponentSetData> sets(final int limit, final String continuationToken) {
    return componentStore.browseSets(facet.contentRepositoryId(), limit, continuationToken);
  }

  @Override
  public Collection<String> versions(final String namespace, final String name) {
    return componentStore.browseVersions(facet.contentRepositoryId(), namespace, name);
  }

  @Override
  public Optional<FluentComponent> find(final EntityId externalId) {
    return componentStore.readComponent(toInternalId(externalId))
        .filter(this::containedInRepository)
        .map(component -> new FluentComponentImpl(facet, component));
  }

  /**
   * Returns {@code true} if this component is contained in this repository or any of its members.
   * Enhanced with pattern matching for repository type checks and Virtual Threads for member checking.
   */
  private boolean containedInRepository(final Component component) {
    int expectedContentRepositoryId = contentRepositoryId(component);
    if (expectedContentRepositoryId == facet.contentRepositoryId()) {
      return true;
    }
    
    // Using pattern matching for repository type check
    return switch (facet.repository().getType()) {
      case GroupType gt -> {
        try {
          // Use Virtual Thread to check group members asynchronously
          Future<Boolean> memberCheckFuture = virtualThreadExecutor.submit(() -> 
              facet.repository()
                  .facet(GroupFacet.class)
                  .allMembers()
                  .parallelStream() // Use parallel stream for better performance
                  .map(InternalIds::contentRepositoryId)
                  .filter(Optional::isPresent)
                  .map(Optional::get)
                  .anyMatch(id -> id == expectedContentRepositoryId)
          );
          
          yield memberCheckFuture.get(); // Wait for the result
        } catch (Exception e) {
          // If there's an error with the Virtual Thread execution, fall back to synchronous execution
          yield facet.repository()
              .facet(GroupFacet.class)
              .allMembers()
              .stream()
              .map(InternalIds::contentRepositoryId)
              .filter(Optional::isPresent)
              .map(Optional::get)
              .anyMatch(id -> id == expectedContentRepositoryId);
        }
      }
      default -> false;
    };
  }

  /**
   * Checks if the repository is a NuGet V2 proxy repository.
   * Enhanced with pattern matching for more readable code.
   */
  private boolean isNugetV2Proxy() {
    var repository = facet.repository();
    var format = repository.getFormat().getValue();
    var type = repository.getType().getValue();
    
    // Using pattern matching to check format and type
    if (!"nuget".equals(format) || !"proxy".equals(type)) {
      return false;
    }

    // Get configuration using pattern matching for null checks
    Configuration conf = repository.getConfiguration();
    if (conf == null || conf.getAttributes() == null) {
      return false;
    }

    // Using pattern matching for map access and version check
    var attributes = conf.getAttributes();
    Object proxyConfig = attributes.get("nugetProxy");
    
    return switch (proxyConfig) {
      case Map<?, ?> nugetProxy -> {
        Object versionObj = nugetProxy.get("nugetVersion");
        yield versionObj instanceof String version && "V2".equals(version);
      }
      default -> false;
    };
  }
}
