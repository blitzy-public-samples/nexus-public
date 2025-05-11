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

import java.util.Properties;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.restore.datastore.DataStoreRestoreBlobData;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPathParser;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Maven-specific implementation of {@link DataStoreRestoreBlobData} that adds Maven path information.
 * <p>
 * This class is compatible with Java 21 and uses Guice's Preconditions for null checking,
 * which remains a recommended practice in Java 21 for ensuring parameter validity.
 *
 * @since 3.0
 */
public class MavenRestoreBlobData
    extends DataStoreRestoreBlobData
{
  private final MavenPath mavenPath;

  /**
   * Constructs a new instance with the specified parameters.
   *
   * @param blob the blob to restore
   * @param blobProperties the properties of the blob
   * @param blobStore the blob store containing the blob
   * @param repositoryManager the repository manager
   * @param mavenPathParser the parser for Maven paths
   * @throws NullPointerException if mavenPathParser is null
   */
  public MavenRestoreBlobData(
      final Blob blob,
      final Properties blobProperties,
      final BlobStore blobStore,
      final RepositoryManager repositoryManager,
      final MavenPathParser mavenPathParser)
  {
    super(blob, blobProperties, blobStore, repositoryManager);
    this.mavenPath = checkNotNull(mavenPathParser).parsePath(getBlobName());
  }

  /**
   * Returns the Maven path associated with this blob data.
   *
   * @return the Maven path
   */
  public MavenPath getMavenPath() {
    return mavenPath;
  }
}