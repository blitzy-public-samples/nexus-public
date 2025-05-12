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
package org.sonatype.nexus.repository.apt.datastore.internal;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.common.thread.Java21TestGroup;
import org.sonatype.nexus.common.thread.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.apt.datastore.internal.browse.AptBrowseNodeGenerator;
import org.sonatype.nexus.repository.content.browse.BrowseTestSupport;
import org.sonatype.nexus.repository.browse.node.BrowsePath;
import org.sonatype.nexus.repository.content.store.AssetData;
import org.sonatype.nexus.repository.content.store.ComponentData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Tests for {@link AptBrowseNodeGenerator} that validate the correct generation of browse nodes
 * for APT repository content.
 * 
 * @since 3.31
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class AptBrowseNodeGeneratorTest
    extends BrowseTestSupport
{
  private final AptBrowseNodeGenerator generator = new AptBrowseNodeGenerator();

  /**
   * Tests the computation of component paths for APT packages.
   */
  @Test
  void computeComponentPath() {
    ComponentData componentData = new ComponentData();
    componentData.setRepositoryId(1);
    componentData.setComponentId(1);
    componentData.setName("nano");
    componentData.setNamespace("amd64");
    componentData.setVersion("1.0.0");

    AssetData asset = new AssetData();
    asset.setComponent(componentData);
    asset.setPath("/path/assetName");

    List<BrowsePath> paths = generator.computeComponentPaths(asset);

    assertAll(
        () -> assertThat(paths.size(), is(6)),
        () -> assertThat(paths, containsInAnyOrder(
            new BrowsePath("packages", "/packages/"),
            new BrowsePath("n", "/packages/n/"),
            new BrowsePath("nano", "/packages/n/nano/"),
            new BrowsePath("1.0.0", "/packages/n/nano/1.0.0/"),
            new BrowsePath("amd64", "/packages/n/nano/1.0.0/amd64/"),
            new BrowsePath("nano", "/packages/n/nano/1.0.0/amd64/nano/")))
    );
  }

  /**
   * Tests the computation of asset paths for metadata files.
   */
  @Test
  void computeAssetPathMetadata() {
    AssetData asset = new AssetData();
    asset.setPath("/path/assetName");

    List<BrowsePath> paths = generator.computeAssetPaths(asset);

    assertAll(
        () -> assertThat(paths.size(), is(3)),
        () -> assertThat(paths, containsInAnyOrder(
            new BrowsePath("metadata", "/metadata/"),
            new BrowsePath("path", "/metadata/path/"),
            new BrowsePath("assetName", "/metadata/path/assetName/")))
    );
  }

  /**
   * Tests the computation of asset paths for .deb package files.
   */
  @Test
  void computeAssetPathDeb() {
    AssetData asset = new AssetData();
    asset.setPath("/path/assetName.deb");

    List<BrowsePath> paths = generator.computeAssetPaths(asset);

    assertAll(
        () -> assertThat(paths.size(), is(3)),
        () -> assertThat(paths, containsInAnyOrder(
            new BrowsePath("packages", "/packages/"),
            new BrowsePath("path", "/packages/path/"),
            new BrowsePath("assetName.deb", "/packages/path/assetName.deb/")))
    );
  }

  /**
   * Tests the computation of asset paths for snapshot files.
   */
  @Test
  void computeAssetPathSnapshots() {
    AssetData asset = new AssetData();
    asset.setPath("/snapshots/path/assetName");

    List<BrowsePath> paths = generator.computeAssetPaths(asset);

    assertAll(
        () -> assertThat(paths.size(), is(3)),
        () -> assertThat(paths, containsInAnyOrder(
            new BrowsePath("snapshots", "/snapshots/"),
            new BrowsePath("path", "/snapshots/path/"),
            new BrowsePath("assetName", "/snapshots/path/assetName/")))
    );
  }
  
  /**
   * Tests concurrent computation of browse paths using Virtual Threads.
   * This test validates that the browse node generator works correctly
   * under concurrent access patterns using Java 21 Virtual Threads.
   */
  @Test
  void concurrentComputePathsWithVirtualThreads() throws Exception {
    // Create test data
    ComponentData componentData = new ComponentData();
    componentData.setRepositoryId(1);
    componentData.setComponentId(1);
    componentData.setName("nano");
    componentData.setNamespace("amd64");
    componentData.setVersion("1.0.0");

    AssetData componentAsset = new AssetData();
    componentAsset.setComponent(componentData);
    componentAsset.setPath("/path/assetName");
    
    AssetData metadataAsset = new AssetData();
    metadataAsset.setPath("/path/metadata");
    
    AssetData debAsset = new AssetData();
    debAsset.setPath("/path/package.deb");
    
    AssetData snapshotAsset = new AssetData();
    snapshotAsset.setPath("/snapshots/path/snapshot");
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Reference to store any exceptions that occur during execution
      AtomicReference<Throwable> exception = new AtomicReference<>();
      
      // Execute concurrent browse path computations
      CompletableFuture<List<BrowsePath>> componentPathsFuture = CompletableFuture.supplyAsync(
          () -> generator.computeComponentPaths(componentAsset), executor);
          
      CompletableFuture<List<BrowsePath>> metadataPathsFuture = CompletableFuture.supplyAsync(
          () -> generator.computeAssetPaths(metadataAsset), executor);
          
      CompletableFuture<List<BrowsePath>> debPathsFuture = CompletableFuture.supplyAsync(
          () -> generator.computeAssetPaths(debAsset), executor);
          
      CompletableFuture<List<BrowsePath>> snapshotPathsFuture = CompletableFuture.supplyAsync(
          () -> generator.computeAssetPaths(snapshotAsset), executor);
      
      // Wait for all futures to complete
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(
          componentPathsFuture, metadataPathsFuture, debPathsFuture, snapshotPathsFuture);
      
      // Handle any exceptions
      allFutures.exceptionally(ex -> {
        exception.set(ex);
        return null;
      }).join();
      
      // If an exception occurred, fail the test
      if (exception.get() != null) {
        throw new AssertionError("Exception during concurrent execution", exception.get());
      }
      
      // Verify results
      List<BrowsePath> componentPaths = componentPathsFuture.join();
      List<BrowsePath> metadataPaths = metadataPathsFuture.join();
      List<BrowsePath> debPaths = debPathsFuture.join();
      List<BrowsePath> snapshotPaths = snapshotPathsFuture.join();
      
      assertAll(
          () -> assertThat("Component paths size", componentPaths.size(), is(6)),
          () -> assertThat("Metadata paths size", metadataPaths.size(), is(3)),
          () -> assertThat("Deb paths size", debPaths.size(), is(3)),
          () -> assertThat("Snapshot paths size", snapshotPaths.size(), is(3))
      );
    }
  }
}
