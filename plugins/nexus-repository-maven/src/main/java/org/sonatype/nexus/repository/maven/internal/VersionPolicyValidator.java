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
package org.sonatype.nexus.repository.maven.internal;

import javax.inject.Named;

import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.VersionPolicy;

import static org.sonatype.nexus.repository.maven.internal.Constants.METADATA_FILENAME;
import static org.sonatype.nexus.repository.maven.internal.Constants.SNAPSHOT_VERSION_SUFFIX;

/**
 * Validates Maven artifacts and metadata paths against repository version policies.
 *
 * @since 3.0
 */
@Named
public class VersionPolicyValidator
{
  private static final String METADATA_SNAPSHOT_PATH_SUFFIX = SNAPSHOT_VERSION_SUFFIX + "/" + METADATA_FILENAME;

  /**
   * Validates if the artifact coordinates are compatible with the repository version policy.
   *
   * @param versionPolicy the repository version policy
   * @param coordinates the Maven artifact coordinates
   * @return true if the artifact is valid for the given policy
   */
  public boolean validArtifactPath(final VersionPolicy versionPolicy, final MavenPath.Coordinates coordinates) {
    return switch (versionPolicy) {
      case SNAPSHOT -> coordinates.isSnapshot();
      case RELEASE -> !coordinates.isSnapshot();
      case MIXED -> true;
    };
  }

  /**
   * Validates if the metadata path is compatible with the repository version policy.
   *
   * @param versionPolicy the repository version policy
   * @param path the metadata path
   * @return true if the metadata is valid for the given policy
   */
  public boolean validMetadataPath(final VersionPolicy versionPolicy, final String path) {
    boolean isMetadataSnapshot = path.endsWith(METADATA_SNAPSHOT_PATH_SUFFIX);
    
    return switch (versionPolicy) {
      case RELEASE -> !isMetadataSnapshot;
      case SNAPSHOT, MIXED -> true;
    };
  }
}