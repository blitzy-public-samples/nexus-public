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
package org.sonatype.nexus.repository.apt.datastore.internal.task;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Named;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.apt.datastore.AptContentFacet;
import org.sonatype.nexus.repository.apt.datastore.internal.data.AptKeyValueFacet;
import org.sonatype.nexus.repository.apt.datastore.internal.hosted.metadata.AptHostedMetadataFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.CancelableHelper;

/**
 * Task to rebuild APT repository metadata.
 * <p>
 * This implementation leverages Java 21 Virtual Threads for improved performance
 * when processing assets and rebuilding metadata, which are primarily I/O-bound operations.
 * </p>
 */
@Named
public class RebuildAptMetadataTask
    extends RepositoryTaskSupport
    implements Cancelable
{
  /**
   * Default timeout for virtual thread executor shutdown in seconds.
   */
  private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 60;

  @Override
  protected void execute(final Repository repository) {
    log.debug("Populating metadata in repository {} started", repository.getName());

    boolean isFullRebuild = getConfiguration()
        .getBoolean(RebuildAptMetadataTaskDescriptor.APT_METADATA_FULL_REBUILD, false);

    executeRebuild(repository, isFullRebuild);
  }

  /**
   * Executes the rebuild operation using Java 21 Virtual Threads for processing assets.
   * Virtual Threads are particularly well-suited for I/O-bound operations like metadata processing.
   *
   * @param repository the repository to rebuild metadata for
   * @param isFullRebuild whether to perform a full rebuild (true) or incremental rebuild (false)
   */
  private void executeRebuild(final Repository repository, boolean isFullRebuild) {
    if (isFullRebuild) {
      // Remove all data in key-value storage
      data(repository).removeAllPackageMetadata();
    }

    // Get all assets
    Iterable<FluentAsset> assets = content(repository).getAptPackageAssets();

    // Create a virtual thread executor for processing assets
    // Virtual threads are lightweight and efficient for I/O-bound operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Add metadata from each asset into key-value table using virtual threads
      for (FluentAsset asset : assets) {
        CancelableHelper.checkCancellation();
        
        // Submit each asset processing task to the virtual thread executor
        executor.submit(() -> {
          try {
            metadata(repository).addPackageMetadata(asset);
          } catch (Exception e) {
            log.error("Error processing asset {}", asset.path(), e);
          }
        });
      }

      // Orderly shutdown of the executor service
      executor.shutdown();
      try {
        // Wait for all tasks to complete or timeout
        if (!executor.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          log.warn("Timeout waiting for asset processing to complete");
          executor.shutdownNow();
        }
      } catch (InterruptedException e) {
        log.warn("Asset processing interrupted", e);
        Thread.currentThread().interrupt();
        executor.shutdownNow();
      }
    }

    // Remove Release index file
    metadata(repository).removeInReleaseIndex();

    // Rebuild index files
    try {
      metadata(repository).rebuildMetadata(Collections.emptyList());
    }
    catch (IOException e) {
      log.error("Error index rebuilding", log.isDebugEnabled() ? e : null);
    }
  }

  @Override
  protected boolean appliesTo(final Repository repository) {
    return repository.getFormat().getValue().equals(AptFormat.NAME) &&
        repository.getType().getValue().equals(HostedType.NAME);
  }

  @Override
  public String getMessage() {
    return "Rebuilding Apt metadata in " + getRepositoryField();
  }

  private AptContentFacet content(final Repository repository) {
    return repository.facet(AptContentFacet.class);
  }

  private AptKeyValueFacet data(final Repository repository) {
    return repository.facet(AptKeyValueFacet.class);
  }

  private AptHostedMetadataFacet metadata(final Repository repository) {
    return repository.facet(AptHostedMetadataFacet.class);
  }
}