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
package org.sonatype.nexus.blobstore.restore.datastore;

import java.util.Properties;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.restore.RestoreBlobData;
import org.sonatype.nexus.repository.manager.RepositoryManager;

/**
 * Simple structure for relevant data for a blob during metadata restoration.
 * Java 21 compatible implementation.
 *
 * @since 3.38
 */
public class DataStoreRestoreBlobData extends RestoreBlobData
{
  /**
   * Constructor for DataStoreRestoreBlobData
   *
   * @param blob The blob to restore
   * @param blobProperties Properties associated with the blob
   * @param blobStore The blob store containing the blob
   * @param repositoryManager Repository manager for accessing repositories
   */
  public DataStoreRestoreBlobData(
      final Blob blob,
      final Properties blobProperties,
      final BlobStore blobStore,
      final RepositoryManager repositoryManager)
  {
    super(blob, blobProperties, blobStore, repositoryManager);
  }

  /**
   * Gets the blob name, ensuring it starts with a forward slash.
   * 
   * @return The blob name with a leading forward slash
   */
  @Override
  public String getBlobName() {
    String blobName = super.getBlobName();
    // Use String.startsWith for path prefix check
    if (!blobName.startsWith("/")) {
      return "/" + blobName;
    }
    return blobName;
  }
}