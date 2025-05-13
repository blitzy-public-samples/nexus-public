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

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;

/**
 * Strategy for checking the integrity of the assets in a repository against its blobstore.
 * 
 * Implementations should leverage Java 21 features such as Virtual Threads for I/O-bound operations
 * and pattern matching for type-safe asset handling where appropriate.
 *
 * @since 3.29
 */
public interface IntegrityCheckStrategy
{
  /**
   * Run the integrity check on the given repository and blob store.
   * 
   * Implementations should consider using Virtual Threads for I/O-bound operations
   * to improve concurrency and resource utilization under Java 21. For example:
   * <pre>
   * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   *   // Submit integrity check tasks to the executor
   *   assets.forEach(asset -> executor.submit(() -> checkAssetIntegrity(asset, blobStore, integrityCheckFailedHandler)));
   * }
   * </pre>
   *
   * @param repository  repository to check
   * @param blobStore   blob store to check
   * @param isCancelled Supplier to check during processing if the task is cancelled
   * @param sinceDays   number of days to look back for assets to check
   * @param integrityCheckFailedHandler will be called with Asset if unable to validate blob integrity
   */
  void check(
      final Repository repository,
      final BlobStore blobStore,
      final BooleanSupplier isCancelled,
      final int sinceDays,
      @Nullable final Consumer<Asset> integrityCheckFailedHandler);

  /**
   * Default method that provides a type-safe way to handle different asset types using Java 21 pattern matching.
   * This method can be overridden by implementations to leverage pattern matching for switch expressions.
   * 
   * Example implementation using Java 21 pattern matching:
   * <pre>
   * &#64;Override
   * public boolean verifyAssetIntegrity(final Asset asset, final BlobStore blobStore) {
   *   return switch (asset) {
   *     case MavenAsset m when m.getPath().endsWith(".jar") -> verifyJarAsset(m, blobStore);
   *     case MavenAsset m -> verifyMavenAsset(m, blobStore);
   *     case DockerAsset d -> verifyDockerAsset(d, blobStore);
   *     case Asset a -> doVerifyAssetIntegrity(a, blobStore);
   *   };
   * }
   * </pre>
   * 
   * @param asset the asset to process
   * @param blobStore the blob store to check against
   * @return true if the asset's integrity was verified successfully, false otherwise
   * @since 3.60
   */
  default boolean verifyAssetIntegrity(final Asset asset, final BlobStore blobStore) {
    // Default implementation delegates to legacy handling
    // Implementations can override this to use pattern matching with switch expressions
    return doVerifyAssetIntegrity(asset, blobStore);
  }
  
  /**
   * Internal method for asset integrity verification.
   * This method should be implemented by concrete strategies.
   * 
   * @param asset the asset to process
   * @param blobStore the blob store to check against
   * @return true if the asset's integrity was verified successfully, false otherwise
   * @since 3.60
   */
  boolean doVerifyAssetIntegrity(final Asset asset, final BlobStore blobStore);
}