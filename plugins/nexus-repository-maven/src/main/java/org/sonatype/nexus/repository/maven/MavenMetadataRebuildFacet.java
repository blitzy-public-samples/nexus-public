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
package org.sonatype.nexus.repository.maven;

import java.io.IOException;

// Updated from javax.annotation.Nullable to jakarta.annotation.Nullable for Java 21 compatibility
import jakarta.annotation.Nullable;

import org.sonatype.nexus.repository.Facet;

/**
 * Maven metadata rebuild facet for managing Maven metadata files.
 * 
 * @since 3.29
 * @see <a href="https://jakarta.ee/specifications/annotations/">Jakarta Annotations</a> for information about the migration from javax to jakarta annotations
 */
@Facet.Exposed
public interface MavenMetadataRebuildFacet
    extends Facet
{
  String METADATA_FORCE_REBUILD = "forceRebuild";

  String METADATA_REBUILD = "metadataRebuild";

  /**
   * Rebuilds/updates Maven metadata. The parameters depend each on previous, and if any of those are set (ie. G, GA or
   * GAV), the metadata will be updated. Rebuild is possible only against repository as whole, not a sub-part of it.
   *
   * @param groupId     scope the work to given groupId.
   * @param artifactId  scope the work to given artifactId (groupId must be given).
   * @param baseVersion scope the work to given baseVersion (groupId and artifactId must be given).
   * @param rebuildChecksums  whether or not checksums should be checked and corrected if found
   *                           missing or incorrect
   */
  void rebuildMetadata(@Nullable String groupId,
                       @Nullable String artifactId,
                       @Nullable String baseVersion,
                       boolean rebuildChecksums);

  /**
   * Rebuilds/updates Maven metadata. The parameters depend each on previous, and if any of those are set (ie. G, GA or
   * GAV), the metadata will be updated. Rebuild is possible only against repository as whole, not a sub-part of it.
   *
   * @param groupId     scope the work to given groupId.
   * @param artifactId  scope the work to given artifactId (groupId must be given).
   * @param baseVersion scope the work to given baseVersion (groupId and artifactId must be given).
   * @param rebuildChecksums  whether or not checksums should be checked and corrected if found
   *                           missing or incorrect
   * @param update      whether to update or replace metadata
   *
   * @since 3.22
   */
  void rebuildMetadata(@Nullable String groupId,
                       @Nullable String artifactId,
                       @Nullable String baseVersion,
                       boolean rebuildChecksums,
                       boolean update);

  /**
   * Rebuilds/updates Maven metadata. The parameters depend each on previous, and if any of those are set (ie. G, GA or
   * GAV), the metadata will be updated. Rebuild is possible only against repository as whole, not a sub-part of it.
   *
   * @param groupId     scope the work to given groupId.
   * @param artifactId  scope the work to given artifactId (groupId must be given).
   * @param baseVersion scope the work to given baseVersion (groupId and artifactId must be given).
   * @param rebuildChecksums  whether or not checksums should be checked and corrected if found
   *                           missing or incorrect
   * @param cascadeUpdate should we rebuild nested levels if groupId/artifactId/baseVersion not present (empty)
   * @param update      whether to update or replace metadata
   *
   */
  void rebuildMetadata(@Nullable String groupId,
                       @Nullable String artifactId,
                       @Nullable String baseVersion,
                       boolean rebuildChecksums,
                       boolean cascadeUpdate,
                       boolean update);

  /**
   * Rebuilds/updates Maven metadata if an asset is available at {@code path} which has an associated
   * blob.
   *
   * @param path             the path to check for an asset
   * @param update           whether to update or replace metadata
   * @param rebuildChecksums whether or not checksums should be checked and corrected if found
   *                         missing or incorrect
   * @throws IOException if an I/O error occurs
   *
   * @since 3.30
   * @implNote Implementations of this method can benefit from Java 21's Virtual Threads for improved
   *           performance when handling I/O operations, especially when processing multiple assets concurrently.
   */
  default void maybeRebuildMavenMetadata(
      String path,
      boolean update,
      boolean rebuildChecksums)
      throws IOException
  {
    // do nothing by default
  }
}