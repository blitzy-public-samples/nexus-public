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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.rest.api.RepositoryManagerRESTAdapter;
import org.sonatype.nexus.repository.selector.ContentAuthHelper;
import org.sonatype.nexus.repository.types.GroupType;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.toList;
import static org.sonatype.nexus.repository.content.rest.internal.resources.AssetsResourceSupport.toInternalToken;
import static org.sonatype.nexus.repository.content.rest.internal.resources.AssetsResourceSupport.trim;

/**
 * Support class for {@link ComponentsResource} which fetches and returns only components that the user is permitted
 * to view according to {@link ContentAuthHelper#checkPathPermissions(String, String, String...)}
 *
 * @since 3.27
 */
abstract class ComponentsResourceSupport
    extends ComponentSupport
{
  /**
   * Limit the number of components returned per page. This value is aligned with AssetsResourceSupport.PAGE_SIZE_LIMIT
   * and also matches the number of component identifiers that can be passed as input to the Firewall component
   * evaluation API.
   */
  protected static final int PAGE_SIZE_LIMIT = 100;

  private final ContentAuthHelper contentAuthHelper;

  private final RepositoryManagerRESTAdapter repositoryManagerRESTAdapter;
  
  /**
   * Virtual thread executor for concurrent component and asset operations
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  ComponentsResourceSupport(
      final ContentAuthHelper contentAuthHelper,
      final RepositoryManagerRESTAdapter repositoryManagerRESTAdapter)
  {
    this.contentAuthHelper = checkNotNull(contentAuthHelper);
    this.repositoryManagerRESTAdapter = checkNotNull(repositoryManagerRESTAdapter);
  }

  List<FluentComponent> browse(final Repository browsedRepository, final String continuationToken)
  {
    log.debug(STR."Browsing components in repository \{browsedRepository.getName()\} with token \{continuationToken\}");
    
    List<FluentComponent> permittedComponents = new ArrayList<>();
    String internalToken = toInternalToken(continuationToken);
    Continuation<FluentComponent> componentContinuation = getComponents(browsedRepository, internalToken);

    // Process components concurrently using Virtual Threads
    while (permittedComponents.size() < PAGE_SIZE_LIMIT && !componentContinuation.isEmpty()) {
      // Create a final reference for use in lambda
      Continuation<FluentComponent> currentContinuation = componentContinuation;
      
      // Process component permissions asynchronously using virtual threads
      CompletableFuture<List<FluentComponent>> permittedComponentsFuture = supplyAsync(
          () -> removeComponentsNotPermitted(browsedRepository, currentContinuation),
          virtualThreadExecutor);
      
      // Get the next continuation asynchronously while processing current components
      CompletableFuture<Continuation<FluentComponent>> nextContinuationFuture = supplyAsync(
          () -> getComponents(browsedRepository, currentContinuation.nextContinuationToken()),
          virtualThreadExecutor);
      
      // Add permitted components to the result list
      permittedComponents.addAll(permittedComponentsFuture.join());
      
      // Get the next continuation
      componentContinuation = nextContinuationFuture.join();
    }
    
    List<FluentComponent> result = trim(permittedComponents, PAGE_SIZE_LIMIT);
    log.debug(STR."Found \{result.size()\} permitted components in repository \{browsedRepository.getName()\}");
    return result;
  }

  private Continuation<FluentComponent> getComponents(Repository repository, final String continuationToken) {
    // Use pattern matching to simplify repository type handling
    return switch (repository.getType().getValue()) {
      case GroupType.NAME -> repository.facet(ContentFacet.class).components().withOnlyGroupMemberContent()
          .browse(PAGE_SIZE_LIMIT, continuationToken);
      default -> repository.facet(ContentFacet.class).components().browse(PAGE_SIZE_LIMIT, continuationToken);
    };
  }

  private List<FluentComponent> removeComponentsNotPermitted(
      final Repository repository,
      final Continuation<FluentComponent> assets)
  {
    String format = repository.getFormat().getValue();
    String repositoryName = repository.getName();
    
    log.trace(STR."Filtering components for permissions in repository \{repositoryName\} with format \{format\}");
    
    return assets.stream()
        .filter(componentPermitted(format, repositoryName))
        .collect(toList());
  }

  Predicate<FluentComponent> componentPermitted(final String format, final String repositoryName) {
    return component -> contentAuthHelper.checkPathPermissions(component.name(), format, repositoryName);
  }

  Predicate<FluentAsset> assetPermitted(Repository repository) {
    String repositoryName = repository.getName();
    String format = repository.getFormat().getValue();
    
    // Optimize set creation and conversion to array
    Set<String> repoNames = new HashSet<>(repositoryManagerRESTAdapter.findContainingGroups(repositoryName));
    repoNames.add(repositoryName);
    String[] repoNamesArray = repoNames.toArray(String[]::new);
    
    // Create an optimized lambda that captures the necessary variables
    return asset -> contentAuthHelper.checkPathPermissions(asset.path(), format, repoNamesArray);
  }
}
