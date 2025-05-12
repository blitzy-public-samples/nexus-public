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
package org.sonatype.nexus.blobstore.restore.apt.internal;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.restore.datastore.BaseRestoreBlobStrategy;
import org.sonatype.nexus.blobstore.restore.datastore.DataStoreRestoreBlobData;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.apt.datastore.AptContentFacet;
import org.sonatype.nexus.repository.apt.debian.Utils;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.DetachedBlobPayload;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;

/**
 * Strategy for restoring APT repository blobs.
 * 
 * @since 3.31
 * @see <a href="https://openjdk.org/jeps/444">JEP 444: Virtual Threads</a>
 */
@FeatureFlag(name = DATASTORE_ENABLED)
@Named(AptFormat.NAME)
@Singleton
public class AptRestoreBlobStrategy
    extends BaseRestoreBlobStrategy<DataStoreRestoreBlobData>
{
  private final RepositoryManager repositoryManager;
  
  // Virtual thread executor for I/O-bound operations
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public AptRestoreBlobStrategy(
      final DryRunPrefix dryRunPrefix,
      final RepositoryManager repositoryManager)
  {
    super(dryRunPrefix);
    this.repositoryManager = repositoryManager;
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  protected boolean canAttemptRestore(
      @Nonnull final DataStoreRestoreBlobData data)
  {
    Repository repository = data.getRepository();

    // Using Java 21 pattern matching for instanceof to simplify the code
    if (repository.optionalFacet(AptContentFacet.class) instanceof Optional<AptContentFacet> optFacet 
        && optFacet.isEmpty()) {
      log.warn("Skipping as {} not found on repository: {}", AptContentFacet.class.getSimpleName(), repository.getName());
      return false;
    }
    return true;
  }

  @Override
  protected void createAssetFromBlob(
      final Blob assetBlob, final DataStoreRestoreBlobData data) throws IOException
  {
    String assetPath = getAssetPath(data);
    Payload payload = new DetachedBlobPayload(assetBlob);
    
    try {
      // Using virtual threads for I/O-bound operations to improve throughput
      virtualThreadExecutor.submit(() -> {
        try {
          data.getRepository()
              .facet(AptContentFacet.class)
              .put(assetPath, payload);
        } 
        catch (IOException e) {
          log.error("Failed to restore blob {} to path {}", assetBlob.getId(), assetPath, e);
        }
        return null;
      }).get(); // Wait for completion
    } 
    catch (Exception e) {
      throw new IOException("Error restoring blob " + assetBlob.getId(), e);
    }
  }

  @Override
  protected String getAssetPath(@Nonnull final DataStoreRestoreBlobData data) {
    return data.getBlobName();
  }

  @Override
  protected DataStoreRestoreBlobData createRestoreData(
      final Properties properties, final Blob blob, final BlobStore blobStore)
  {
    return new DataStoreRestoreBlobData(blob, properties, blobStore, repositoryManager);
  }

  @Override
  protected boolean isComponentRequired(final DataStoreRestoreBlobData data) {
    String blobName = data.getBlobName();
    return Utils.isDebPackageContentType(blobName);
  }

  @Override
  public void after(final boolean updateAssets, final Repository repository) {
    //no-op
  }
}