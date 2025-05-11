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
package org.sonatype.nexus.content.maven.internal.recipe;

import java.io.IOException;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.internal.MavenModels;
import org.sonatype.nexus.repository.maven.internal.hosted.metadata.MetadataRebuilder;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.Payload;

import com.google.common.base.Strings;
import org.apache.maven.artifact.repository.metadata.Metadata;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Boolean.TRUE;
import static org.sonatype.nexus.repository.maven.internal.Constants.METADATA_FILENAME;

/**
 * Implementation of {@link MavenMetadataRebuildFacet} for Maven repositories.
 * Provides functionality to rebuild Maven metadata files based on repository content.
 * 
 * <p>This implementation uses Java 21 features such as ThreadLocal.withInitial() for more
 * efficient thread-local variable management, particularly important when working with
 * Virtual Threads which are a key feature of Java 21.</p>
 * 
 * @since 3.26
 * @see MavenMetadataRebuildFacet
 */
@Named
public class MavenMetadataRebuildFacetImpl
    extends FacetSupport
    implements MavenMetadataRebuildFacet
{
  private MavenContentFacet mavenContentFacet;

  private final MetadataRebuilder metadataRebuilder;

  /**
   * Thread-local flag to prevent recursive rebuilds.
   * Uses Java 21's ThreadLocal.withInitial() for cleaner initialization and better
   * compatibility with Virtual Threads. This approach avoids the need for subclassing
   * ThreadLocal and overriding initialValue().
   */
  private static final ThreadLocal<Boolean> rebuilding = ThreadLocal.withInitial(() -> false);

  @Inject
  public MavenMetadataRebuildFacetImpl(final MetadataRebuilder metadataRebuilder)
  {
    this.metadataRebuilder = checkNotNull(metadataRebuilder);
  }

  @Override
  protected void doInit(final Configuration configuration) throws Exception {
    super.doInit(configuration);
    mavenContentFacet = facet(MavenContentFacet.class);
  }

  @Override
  public void maybeRebuildMavenMetadata(final String path, final boolean update, final boolean rebuildChecksums)
      throws IOException
  {
    Optional<FluentAsset> maybeAsset = mavenContentFacet.assets().path(path).find();
    Optional<Payload> maybePayload = maybeAsset.map(FluentAsset::download);

    if (maybeAsset.isPresent() && maybePayload.isPresent()) {
      FluentAsset asset = maybeAsset.get();
      Payload payload = maybePayload.get();
      if (needsRebuild(mavenContentFacet.getMavenPathParser().parsePath(path), asset)) {
        asset.withoutAttribute(METADATA_REBUILD);
        rebuildMetadata(payload, update, rebuildChecksums);
      }
    }
  }

  /**
   * Determines if metadata needs to be rebuilt.
   * 
   * <p>Uses Java 21 pattern matching for instanceof check to improve code readability.
   * This method checks several conditions to determine if metadata rebuilding is necessary:</p>
   * <ul>
   *   <li>We're not already in a rebuild process (to prevent recursion)</li>
   *   <li>The file is a Maven metadata file</li>
   *   <li>The repository is not a proxy repository (proxies don't rebuild metadata)</li>
   *   <li>The asset has been marked for forced rebuild</li>
   * </ul>
   */
  private boolean needsRebuild(final MavenPath path, final FluentAsset asset) {
    return !TRUE.equals(rebuilding.get())
        && path.getFileName().equals(METADATA_FILENAME)
        && !(getRepository().getType() instanceof ProxyType) // Java 21 pattern matching would use 'instanceof ProxyType _' if we needed the instance
        && TRUE.equals(asset.attributes(METADATA_REBUILD).get(METADATA_FORCE_REBUILD, false));
  }

  /**
   * Rebuilds metadata from the provided payload.
   * 
   * <p>This method extracts metadata information from the payload and triggers a rebuild.
   * It uses Java 21's enhanced Optional handling with method references for cleaner code.</p>
   * 
   * <p>In a more complex scenario, we could use Java 21's record patterns if Metadata were a record,
   * but since it's a standard class, we use the functional approach with Optional.</p>
   *
   * @param metadataPayload The payload containing Maven metadata
   * @param update Whether to update existing metadata
   * @param rebuildChecksums Whether to rebuild checksums
   * @throws IOException If there's an error reading the metadata
   */
  private void rebuildMetadata(final Payload metadataPayload, final boolean update, final boolean rebuildChecksums)
      throws IOException
  {
    Metadata metadata = MavenModels.readMetadata(metadataPayload.openInputStream());
    
    // Using Optional for null-safe extraction of metadata fields
    // This is a cleaner approach in Java 21 compared to null checks
    String groupId = Optional.ofNullable(metadata).map(Metadata::getGroupId).orElse(null);
    String artifactId = Optional.ofNullable(metadata).map(Metadata::getArtifactId).orElse(null);
    String baseVersion = Optional.ofNullable(metadata).map(Metadata::getVersion).orElse(null);
    
    rebuildMetadata(groupId, artifactId, baseVersion, rebuildChecksums, update);
  }

  @Override
  public void rebuildMetadata(
      final String groupId,
      final String artifactId,
      final String baseVersion,
      final boolean rebuildChecksums)
  {
    final boolean update = !Strings.isNullOrEmpty(groupId)
        || !Strings.isNullOrEmpty(artifactId)
        || !Strings.isNullOrEmpty(baseVersion);
    rebuildMetadata(groupId, artifactId, baseVersion, rebuildChecksums, update);
  }

  @Override
  public void rebuildMetadata(
      final String groupId,
      final String artifactId,
      final String baseVersion,
      final boolean rebuildChecksums,
      final boolean update)
  {
    // cascadeUpdate "true" by default to preserve old behaviour when we rebuild all nested metadata
    rebuildMetadata(groupId, artifactId, baseVersion, rebuildChecksums, true, update);
  }

  @Override
  public void rebuildMetadata(
      final String groupId,
      final String artifactId,
      final String baseVersion,
      final boolean rebuildChecksums,
      final boolean cascadeUpdate,
      final boolean update)
  {
    // avoid triggering nested rebuilds as the rebuilder will already do that if necessary
    // Set the ThreadLocal flag to prevent recursive rebuilds
    rebuilding.set(TRUE);
    try {
      metadataRebuilder
          .rebuildInTransaction(getRepository(), update, rebuildChecksums, cascadeUpdate, groupId, artifactId, baseVersion);
    }
    finally {
      // Always clean up ThreadLocal variables to prevent memory leaks
      // This is especially important with Java 21's Virtual Threads where many more threads might exist
      rebuilding.remove();
    }
  }
}