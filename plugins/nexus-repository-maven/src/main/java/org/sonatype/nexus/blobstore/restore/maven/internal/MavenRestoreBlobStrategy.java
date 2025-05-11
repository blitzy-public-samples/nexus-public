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
package org.sonatype.nexus.blobstore.restore.maven.internal;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.restore.datastore.BaseRestoreBlobStrategy;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.view.payloads.DetachedBlobPayload;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;

/**
 * Maven implementation of {@link BaseRestoreBlobStrategy} that restores Maven repository content from blobs.
 * This implementation leverages Java 21 features including Virtual Threads for I/O operations,
 * Pattern Matching for type checks, and String Templates for improved logging.
 * 
 * @since 3.29
 */
@FeatureFlag(name = DATASTORE_ENABLED)
@Named(Maven2Format.NAME)
@Singleton
public class MavenRestoreBlobStrategy
    extends BaseRestoreBlobStrategy<MavenRestoreBlobData>
{
  private final RepositoryManager repositoryManager;

  private final MavenPathParser mavenPathParser;

  @Inject
  protected MavenRestoreBlobStrategy(
      final DryRunPrefix dryRunPrefix,
      final RepositoryManager repositoryManager,
      final MavenPathParser mavenPathParser)
  {
    super(dryRunPrefix);
    this.repositoryManager = checkNotNull(repositoryManager);
    this.mavenPathParser = checkNotNull(mavenPathParser);
  }

  @Override
  protected boolean canAttemptRestore(@Nonnull final MavenRestoreBlobData data) {
    MavenPath mavenPath = data.getMavenPath();
    Repository repository = data.getRepository();

    // Check if the Maven path has coordinates or is repository metadata
    if (mavenPath.getCoordinates() == null && !mavenPathParser.isRepositoryMetadata(mavenPath)) {
      log.warn(
          STR."Skipping blob in repository named \{repository.getName()}, because no maven coordinates found for blob named \{data.getBlobName()} in blob store named \{data.getBlobStore().getBlobStoreConfiguration().getName()} and the blob not maven metadata");
      return false;
    }

    // Use pattern matching for Optional - Java 21 feature
    if (repository.optionalFacet(MavenContentFacet.class) instanceof Optional<MavenContentFacet> mavenFacet
        && mavenFacet.isEmpty()) {
      if (log.isWarnEnabled()) {
        log.warn(STR."Skipping as Maven Content Facet not found on repository: \{repository.getName()}");
      }
      return false;
    }

    return true;
  }

  @Override
  protected void createAssetFromBlob(final Blob assetBlob, final MavenRestoreBlobData data) throws IOException {
    // Use Virtual Thread for I/O-bound operation - Java 21 feature
    try (var executor = newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        try {
          MavenContentFacet mavenFacet = data.getRepository().facet(MavenContentFacet.class);
          mavenFacet.put(data.getMavenPath(), new DetachedBlobPayload(assetBlob));
          log.debug(STR."Successfully restored Maven asset from blob \{data.getBlobName()} to path \{data.getMavenPath().getPath()}");
        } catch (Exception e) {
          log.error(STR."Error restoring Maven asset from blob \{data.getBlobName()}", e);
          throw new RuntimeException(e);
        }
        return null;
      });
      
      try {
        future.get(); // Wait for completion
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(STR."Interrupted while restoring Maven asset from blob \{data.getBlobName()}", e);
      } catch (ExecutionException e) {
        throw new IOException(STR."Failed to restore Maven asset from blob \{data.getBlobName()}", e.getCause());
      }
    }
  }

  @Override
  protected String getAssetPath(@Nonnull final MavenRestoreBlobData data) {
    return data.getMavenPath().getPath();
  }

  @Override
  protected MavenRestoreBlobData createRestoreData(
      final Properties properties,
      final Blob blob,
      final BlobStore blobStore)
  {
    return new MavenRestoreBlobData(blob, properties, blobStore, repositoryManager, mavenPathParser);
  }

  @Override
  protected boolean isComponentRequired(final MavenRestoreBlobData data) {
    // Use pattern matching for MavenPath - Java 21 feature
    MavenPath path = data.getMavenPath();
    return switch (path) {
      case MavenPath p when mavenPathParser.isRepositoryIndex(p) -> false;
      case MavenPath p when mavenPathParser.isRepositoryMetadata(p) -> false;
      default -> true;
    };
  }

  @Override
  public void after(final boolean updateAssets, final Repository repository) {
    // no-op
  }
}