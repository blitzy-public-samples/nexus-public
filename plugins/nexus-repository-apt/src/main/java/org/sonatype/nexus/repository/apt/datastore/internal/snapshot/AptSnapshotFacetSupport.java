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
package org.sonatype.nexus.repository.apt.datastore.internal.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.apt.datastore.AptContentFacet;
import org.sonatype.nexus.repository.apt.internal.AptFacetHelper;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFile;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFileParser;
import org.sonatype.nexus.repository.apt.internal.debian.Release;
import org.sonatype.nexus.repository.apt.internal.snapshot.AptFilterInputStream;
import org.sonatype.nexus.repository.apt.internal.snapshot.AptSnapshotFacet;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotComponentSelector;
import org.sonatype.nexus.repository.apt.internal.snapshot.SnapshotItem;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.bcpg.ArmoredInputStream;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Basic implementation of snapshots for apt datastore repositories.
 * 
 * This implementation leverages Java 21 features including Virtual Threads for improved
 * I/O operations performance when creating and processing snapshots.
 *
 * @since 3.31
 */
public abstract class AptSnapshotFacetSupport
    extends FacetSupport
    implements AptSnapshotFacet
{
  @Override
  public boolean isSnapshotableFile(final String path) {
    return !path.endsWith(".deb") && !path.endsWith(".DEB");
  }

  @Override
  public void createSnapshot(final String id, final SnapshotComponentSelector selector) throws IOException {
    Iterable<SnapshotItem> snapshots = collectSnapshotItems(selector);
    createSnapshot(id, snapshots);
  }

  /**
   * Creates a snapshot with the given ID using the provided snapshot items.
   * Uses Virtual Threads for parallel processing of snapshot items to improve I/O performance.
   *
   * @param id the snapshot ID
   * @param snapshots the items to include in the snapshot
   * @throws IOException if an error occurs during snapshot creation
   */
  protected void createSnapshot(final String id, final Iterable<SnapshotItem> snapshots) throws IOException {
    checkNotNull(id);
    AptContentFacet contentFacet = facet(AptContentFacet.class);
    
    // Use virtual threads for parallel processing of snapshot items
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (SnapshotItem item : snapshots) {
        futures.add(executor.submit(() -> {
          String assetPath = createAssetPath(id, item.specifier.path);
          try (InputStream is = item.content.openInputStream();
               TempBlob tempBlob = contentFacet.getTempBlob(is, item.specifier.role.getMimeType())) {
            contentFacet.findOrCreateMetadataAsset(tempBlob, assetPath);
            return null;
          } catch (IOException e) {
            throw new RuntimeException("Failed to create snapshot asset: " + assetPath, e);
          }
        }));
      }
      
      // Wait for all tasks to complete and handle any exceptions
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (Exception e) {
          if (e.getCause() instanceof IOException) {
            throw (IOException) e.getCause();
          }
          throw new IOException("Error creating snapshot", e);
        }
      }
    }
  }

  @Override
  @Nullable
  public Content getSnapshotFile(final String id, final String path) {
    checkNotNull(id);
    checkNotNull(path);

    String assetPath = createAssetPath(id, path);
    AptContentFacet contentFacet = facet(AptContentFacet.class);

    return contentFacet.get(assetPath).orElse(null);
  }

  @Override
  public void deleteSnapshot(final String id) {
    checkNotNull(id);

    AptContentFacet contentFacet = facet(AptContentFacet.class);
    String path = createAssetPath(id, StringUtils.EMPTY);
    contentFacet.deleteAssetsByPrefix(path);
  }

  /**
   * Collects snapshot items based on the provided selector.
   * Uses Java 21 features for improved stream processing and I/O operations.
   *
   * @param selector the component selector to determine which items to include
   * @return an iterable of snapshot items
   * @throws IOException if an error occurs during collection
   */
  protected Iterable<SnapshotItem> collectSnapshotItems(final SnapshotComponentSelector selector) throws IOException {
    AptContentFacet aptFacet = getRepository().facet(AptContentFacet.class);

    List<SnapshotItem> releaseIndexItems =
        fetchSnapshotItems(AptFacetHelper.getReleaseIndexSpecifiers(aptFacet.isFlat(), aptFacet.getDistribution()));
    
    // Use Java 21 enhanced collectors for better readability
    Map<SnapshotItem.Role, SnapshotItem> itemsByRole = new EnumMap<>(
        releaseIndexItems.stream().collect(Collectors.toMap(item -> item.specifier.role, item -> item)));
    
    InputStream releaseStream = null;
    
    // Use pattern matching for instanceof checks when processing the release index
    var releaseIndexItem = itemsByRole.get(SnapshotItem.Role.RELEASE_INDEX);
    if (releaseIndexItem != null) {
      releaseStream = releaseIndexItem.content.openInputStream();
    }
    else {
      var inlineIndexItem = itemsByRole.get(SnapshotItem.Role.RELEASE_INLINE_INDEX);
      if (inlineIndexItem != null) {
        try (InputStream is = inlineIndexItem.content.openInputStream()) {
          ArmoredInputStream aIs = new ArmoredInputStream(is);
          releaseStream = new AptFilterInputStream(aIs);
        }
      }
    }

    if (releaseStream == null) {
      throw new IOException("Invalid upstream repository: no release index present");
    }

    Release release;
    try {
      ControlFile index = new ControlFileParser().parseControlFile(releaseStream);
      release = new Release(index);
    }
    finally {
      releaseStream.close();
    }

    List<SnapshotItem> result = new ArrayList<>(releaseIndexItems);
    
    // Process repository content based on structure (flat or hierarchical)
    if (aptFacet.isFlat()) {
      result.addAll(fetchSnapshotItems(
          AptFacetHelper.getReleasePackageIndexes(aptFacet.isFlat(), aptFacet.getDistribution(), null, null)));
    }
    else {
      // Use virtual threads for parallel processing of architectures and components
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<List<SnapshotItem>>> futures = new ArrayList<>();
        
        List<String> archs = selector.getArchitectures(release);
        List<String> comps = selector.getComponents(release);
        
        for (String arch : archs) {
          for (String comp : comps) {
            futures.add(executor.submit(() -> {
              try {
                return fetchSnapshotItems(
                    AptFacetHelper.getReleasePackageIndexes(aptFacet.isFlat(), aptFacet.getDistribution(), comp, arch));
              } catch (IOException e) {
                throw new RuntimeException("Failed to fetch snapshot items for " + comp + "/" + arch, e);
              }
            }));
          }
        }
        
        // Collect results from all futures
        for (Future<List<SnapshotItem>> future : futures) {
          try {
            result.addAll(future.get());
          } catch (Exception e) {
            if (e.getCause() instanceof IOException) {
              throw (IOException) e.getCause();
            }
            throw new IOException("Error collecting snapshot items", e);
          }
        }
      }
    }

    return result;
  }

  private String createAssetPath(final String id, final String path) {
    return "/snapshots/" + id + "/" + path;
  }

  /**
   * Fetches snapshot items based on the provided specifications.
   * Implementation should leverage Virtual Threads for I/O operations where appropriate.
   *
   * @param specs the specifications for items to fetch
   * @return a list of snapshot items
   * @throws IOException if an error occurs during fetching
   */
  protected abstract List<SnapshotItem> fetchSnapshotItems(final List<SnapshotItem.ContentSpecifier> specs)
      throws IOException;
}