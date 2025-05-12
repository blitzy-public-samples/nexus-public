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
package org.sonatype.nexus.testsuite.testsupport.blobstore.restore;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.repository.Repository;

/**
 * Helper class containing common functionality needed in ITs testing the restoration of component metadata from blobs.
 * Assumes a unit of work has already been started.
 * <p>
 * Implementations should leverage Java 21 virtual threads for I/O-bound operations to improve performance and
 * scalability. Methods that involve file system operations, network calls, or database interactions are prime
 * candidates for virtual thread execution.
 * </p>
 */
public interface BlobstoreRestoreTestHelper
{
  String TYPE_ID = "blobstore.rebuildComponentDB";

  String BLOB_STORE_NAME_FIELD_ID = "blobstoreName";

  String RESTORE_BLOBS = "restoreBlobs";

  String UNDELETE_BLOBS = "undeleteBlobs";

  String INTEGRITY_CHECK = "integrityCheck";

  String DRY_RUN = "dryRun";

  String PLAN_RECONCILE_TYPE_ID = "blobstore.planReconciliation";

  String EXECUTE_RECONCILE_TYPE_ID = "blobstore.executeReconciliationPlan";

  /**
   * Get the blob ids of the assets
   * 
   * @return List of blob IDs associated with assets
   */
  List<BlobId> getAssetBlobId();

  /**
   * Deletes asset blob
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param repository the name of the repository
   * @param blobStore blobStore where blob is stored
   * @param blobId blobId to delete
   */
  void deleteAssetBlob(Repository repository, BlobStore blobStore, BlobId blobId);

  /**
   * Verifies existence of asset blob
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param repository the name of the blobstore
   * @param blobStore blobStore where blob is stored
   * @param blobId blobId to read
   *
   * @return {@code true} if the asset blob exists
   */
  boolean assetBlobExists(Repository repository, BlobStore blobStore, BlobId blobId);

  /**
   * Clean tables from previous data
   */
  void truncateTables(String format);

  /**
   * Deletes the file with specified extension
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param blobStorageName the name of the blobstore
   * @param extension extension of the file to delete
   */
  void simulateFileLoss(String blobStorageName, String extension);

  /**
   * Asserts that the reconcile plan exists with specific type and action
   *
   * @param type the type of the plan
   * @param action the action of the plan
   * @return {@code true} if the reconcile plan exists
   */
  boolean assertReconcilePlanExists(String type, String action);

  /**
   * Asserts that the reconcile plan exists with specific set of parameters
   *
   * @param type the type of the plan
   * @param action the action of the plan
   * @param blobIds list of blob ids to check
   * @return {@code true} if the reconcile plan exists with the specified parameters
   */
  boolean assertReconcilePlanExists(String type, String action, List<BlobId> blobIds);

  /**
   * Asserts that the property files exist for the specified blobstore
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param blobStorageName the name of the blobstore
   * @return {@code true} if the property files exist
   */
  boolean assertPropertyFilesExist(String blobStorageName);

  /**
   * Run the reconcile task with the specified wait for task timeout
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param blobstoreName the name of the blobstore
   * @param timeout the timeout to wait for the task to complete
   */
  void runReconcileTaskWithTimeout(final String blobstoreName, final long timeout);

  /**
   * Simulates component and asset metadata loss for testing restoration
   */
  void simulateComponentAndAssetMetadataLoss();

  /**
   * Simulates asset metadata loss for testing restoration
   */
  void simulateAssetMetadataLoss();

  /**
   * Simulates component metadata loss for testing restoration
   */
  void simulateComponentMetadataLoss();

  /**
   * Run the restore (reconcile) task with the specified wait for task timeout and the specified dry run flag
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param blobstoreName the name of the blobstore
   */
  default void runRestoreMetadataTask(final String blobstoreName) {
    runRestoreMetadataTaskWithTimeout(blobstoreName, 60, false);
  }

  /**
   * Run the restore (reconcile) task with the default wait for task timeout and the specified dry run flag
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param blobStoreName the name of the blobstore
   * @param isDryRun when true set dryrun on the task which does not restore assets
   */
  default void runRestoreMetadataTask(final String blobStoreName, final boolean isDryRun) {
    runRestoreMetadataTaskWithTimeout(blobStoreName, 60, isDryRun);
  }

  /**
   * Run the restore (reconcile) task with the specified wait for task timeout and the specified dry run flag
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param blobstoreName the name of the blobstore
   * @param timeout the timeout to wait for the task to complete
   * @param dryRun when true set dryrun on the task which does not restore assets
   */
  void runRestoreMetadataTaskWithTimeout(final String blobstoreName, final long timeout, final boolean dryRun);

  /**
   * Asserts that the asset matches the blob
   *
   * @param repository the repository to check
   * @param names the names of the assets to check
   */
  void assertAssetMatchesBlob(Repository repository, String... names);

  /**
   * Asserts that the asset is in the repository
   *
   * @param repository the repository to check
   * @param name the name of the asset to check
   */
  void assertAssetInRepository(Repository repository, String name);

  /**
   * Asserts that the asset is not in the repository
   *
   * @param repository the repository to check
   * @param names the names of the assets to check
   */
  void assertAssetNotInRepository(Repository repository, String... names);

  /**
   * Asserts that the component is in the repository
   *
   * @param repository the repository to check
   * @param name the name of the component to check
   */
  void assertComponentInRepository(Repository repository, String name);

  /**
   * Asserts that the component is in the repository
   *
   * @param repository the repository to check
   * @param name the name of the component to check
   * @param version the version of the component to check
   */
  void assertComponentInRepository(Repository repository, String name, String version);

  /**
   * Asserts that the component is in the repository
   *
   * @param repository the repository to check
   * @param group the group of the component to check
   * @param name the name of the component to check
   * @param version the version of the component to check
   */
  void assertComponentInRepository(Repository repository, String group, String name, String version);

  /**
   * Asserts that the component is not in the repository
   *
   * @param repository the repository to check
   * @param name the name of the component to check
   */
  void assertComponentNotInRepository(Repository repository, String name);

  /**
   * Asserts that the component is not in the repository
   *
   * @param repository the repository to check
   * @param name the name of the component to check
   * @param version the version of the component to check
   */
  void assertComponentNotInRepository(Repository repository, String name, String version);

  /**
   * Asserts that the asset is associated with the component
   *
   * @param repository the repository to check
   * @param name the name of the component to check
   * @param path the path of the asset to check
   */
  void assertAssetAssociatedWithComponent(Repository repository, String name, String path);

  /**
   * Asserts that the asset is associated with the component
   *
   * @param repository the repository to check
   * @param group the group of the component to check
   * @param name the name of the component to check
   * @param version the version of the component to check
   * @param paths the paths of the assets to check
   */
  void assertAssetAssociatedWithComponent(
      Repository repository,
      @Nullable String group,
      String name,
      String version,
      String... paths);

  /**
   * Rewrites all the blob names either adding a leading slash, or removing a leading slash to simulate blobs which were
   * written by the other database.
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   */
  void rewriteBlobNames();

  /**
   * Retrieve the map of path->blobId for all assets in the provided repository.
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param repo the repository to check
   * @param pathFilter a predicate which returns true if the asset path should be included in the result
   * @return a map of path to blob ID
   */
  Map<String, BlobId> getAssetToBlobIds(Repository repo, Predicate<String> pathFilter);

  /**
   * Retrieve the map of path->blobId for all assets in the provided repository.
   * <p>
   * Implementation should use virtual threads for this I/O-bound operation.
   * </p>
   *
   * @param repo the repository to check
   * @return a map of path to blob ID
   */
  default Map<String, BlobId> getAssetToBlobIds(final Repository repo) {
    return getAssetToBlobIds(repo, path -> true);
  }
}