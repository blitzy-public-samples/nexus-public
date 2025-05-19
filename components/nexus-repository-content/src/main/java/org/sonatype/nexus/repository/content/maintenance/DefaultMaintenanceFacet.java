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
package org.sonatype.nexus.repository.content.maintenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Named;

import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.store.ComponentStore;

import com.google.common.collect.ImmutableSet;

/**
 * Default {@link ContentMaintenanceFacet} for formats that don't need additional bookkeeping.
 * 
 * Updated to leverage Java 21 Virtual Threads for I/O-bound operations to improve concurrency
 * and performance during component and asset deletion operations.
 *
 * @since 3.26
 */
@Named
public class DefaultMaintenanceFacet
    extends FacetSupport
    implements ContentMaintenanceFacet
{
  @Override
  public Set<String> deleteComponent(final Component component) {
    ImmutableSet.Builder<String> deletedPaths = ImmutableSet.builder();

    FluentComponent componentToDelete = contentFacet().components().with(component);
    
    // Collect all assets to process
    List<FluentAsset> assets = componentToDelete.assets().collect(Collectors.toList());
    
    // Use Virtual Threads for concurrent asset deletion (I/O-bound operations)
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<String>> futures = new ArrayList<>();
      
      // Submit each asset deletion as a separate Virtual Thread task
      for (FluentAsset assetToDelete : assets) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
          if (assetToDelete.delete()) {
            return assetToDelete.path(); // only return paths which were deleted by us
          }
          return null;
        }, executor);
        futures.add(future);
      }
      
      // Collect results from all completed futures
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      // Add all deleted paths to the result set
      for (CompletableFuture<String> future : futures) {
        try {
          String path = future.get();
          if (path != null) {
            deletedPaths.add(path);
          }
        }
        catch (InterruptedException | ExecutionException e) {
          log.error("Error during asset deletion", e);
          Thread.currentThread().interrupt();
        }
      }
    }

    componentToDelete.delete(); // the component itself has no path

    return deletedPaths.build();
  }

  @Override
  public Set<String> deleteAsset(final Asset asset) {
    ImmutableSet.Builder<String> deletedPaths = ImmutableSet.builder();

    FluentAsset assetToDelete = contentFacet().assets().with(asset);
    
    // Use Virtual Thread for I/O-bound asset deletion operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> deleteFuture = CompletableFuture.supplyAsync(
          assetToDelete::delete, executor);
      
      if (deleteFuture.join()) {
        deletedPaths.add(assetToDelete.path());
      }
    }

    return deletedPaths.build();
  }

  protected ContentFacet contentFacet() {
    return facet(ContentFacet.class);
  }

  @Override
  public int deleteComponents(final Stream<FluentComponent> components) {
    ContentFacetSupport contentFacet = (ContentFacetSupport) contentFacet();
    ComponentStore<?> componentStore = contentFacet.stores().componentStore;
    
    // Collect components to delete
    List<FluentComponent> componentList = components.collect(Collectors.toList());
    
    // For large component lists, use Virtual Threads to process in batches
    if (componentList.size() > 100) {
      final int batchSize = 100;
      final int contentRepositoryId = contentFacet.contentRepositoryId();
      
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<Integer>> batchFutures = new ArrayList<>();
        
        // Process components in batches using Virtual Threads
        for (int i = 0; i < componentList.size(); i += batchSize) {
          final int fromIndex = i;
          final int toIndex = Math.min(i + batchSize, componentList.size());
          
          CompletableFuture<Integer> batchFuture = CompletableFuture.supplyAsync(() -> {
            List<FluentComponent> batch = componentList.subList(fromIndex, toIndex);
            return componentStore.purge(contentRepositoryId, batch);
          }, executor);
          
          batchFutures.add(batchFuture);
        }
        
        // Wait for all batches to complete and sum the results
        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();
        
        return batchFutures.stream()
            .map(CompletableFuture::join)
            .mapToInt(Integer::intValue)
            .sum();
      }
    }
    
    // For smaller lists, use the original implementation
    return componentStore.purge(contentFacet.contentRepositoryId(), componentList);
  }
}