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
package org.sonatype.nexus.repository.tools.datastore;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.common.thread.VirtualThreadExecutorService;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.tools.OrphanedBlobFinder;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.allOf;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.BLOB_STORE_NAME;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STORAGE;

/**
 * Detects orphaned blobs (i.e. non-deleted blobs that exist in the blobstore but not the asset table)
 *
 * @since 3.25
 */
@Named
public class DatastoreOrphanedBlobFinder
    extends ComponentSupport
    implements OrphanedBlobFinder
{
  private final RepositoryManager repositoryManager;

  private final BlobStoreManager blobStoreManager;
  
  private final VirtualThreadExecutorService executorService;

  @Inject
  public DatastoreOrphanedBlobFinder(final RepositoryManager repositoryManager, final BlobStoreManager blobStoreManager) {
    this.repositoryManager = checkNotNull(repositoryManager);
    this.blobStoreManager = checkNotNull(blobStoreManager);
    this.executorService = new VirtualThreadExecutorService(Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Delete orphaned blobs for all repositories
   */
  @Override
  public void delete() {
    log.info(STR."Starting delete of orphaned blobs for all known blob stores");

    blobStoreManager.browse().forEach(this::delete);

    log.info(STR."Finished deleting orphaned blobs");
  }

  /**
   * Delete orphaned blobs associated with a given repository
   *
   * @param repository - where to look for orphaned blobs
   */
  @Override
  public void delete(final Repository repository) {
    log.info(STR."Starting delete of orphaned blobs for \{repository.getName()}");

    delete(getBlobStoreForRepository(repository));

    log.info(STR."Finished deleting orphaned blobs for \{repository.getName()}");
  }

  private void delete(final BlobStore blobStore) {
    detect(blobStore, blobId -> {
      log.info(STR."Deleting orphaned blob \{blobId} from blobstore \{blobStore.getBlobStoreConfiguration().getName()}");

      blobStore.deleteHard(blobId);
    });
  }

  /**
   * Look for orphaned blobs in a given repository and callback for each blobId found
   *
   * @param repository - where to look for orphaned blobs
   * @param handler    - callback to handle an orphaned blob
   */
  @Override
  public void detect(final Repository repository, final Consumer<BlobId> handler) {
    validateRepositoryConfiguration(repository);

    detect(getBlobStoreForRepository(repository), handler);
  }

  private void detect(final BlobStore blobStore, final Consumer<BlobId> handler) {
    Stream<BlobId> blobIds = blobStore.getBlobIdStream();
    
    // Process BlobIds in parallel using Virtual Threads
    CompletableFuture<?>[] futures = blobIds
        .map(id -> executorService.supplyAsync(() -> {
          // Get blob attributes in a Virtual Thread for non-blocking I/O
          BlobAttributes attributes = blobStore.getBlobAttributes(id);
          if (attributes != null) {
            // Process the blob in the same Virtual Thread
            checkIfOrphaned(handler, id, attributes);
          }
          else {
            log.warn(STR."Skipping cleanup for blob \{id} because blob properties not found");
          }
          return null;
        }))
        .toArray(CompletableFuture[]::new);
    
    // Wait for all Virtual Threads to complete
    allOf(futures).join();
  }

  private void checkIfOrphaned(final Consumer<BlobId> handler, final BlobId id, final BlobAttributes attributes) {
    String repositoryName = attributes.getHeaders().get(REPO_NAME_HEADER);

    if (repositoryName != null) {
      String assetName = attributes.getHeaders().get(BLOB_NAME_HEADER);

      Repository repository = repositoryManager.get(repositoryName);
      if (repository == null) {
        log.debug(STR."Blob \{id.asUniqueString()} considered orphaned because repository with name \{repositoryName} no longer exists");

        handler.accept(id);
      }
      else {
        // Use Virtual Thread for non-blocking I/O when retrieving asset information
        findAssociatedAsset(assetName, repository).ifPresent(asset -> {
          BlobRef blobRef = asset.blob().map(AssetBlob::blobRef).orElse(null);
          if (blobRef != null && !blobRef.getBlobId().asUniqueString().equals(id.asUniqueString())) {
            if (!attributes.isDeleted()) {
              handler.accept(id);
            }
            else {
              log.debug(STR."Blob \{id.asUniqueString()} in repository \{repositoryName} not considered orphaned because it is already marked soft-deleted");
            }
          }
        });
      }
    }
  }

  private BlobStore getBlobStoreForRepository(final Repository repository) {
    String blobStoreName = (String) repository.getConfiguration().getAttributes().get(STORAGE)
        .get(BLOB_STORE_NAME);

    return blobStoreManager.get(blobStoreName);
  }

  private Optional<Asset> findAssociatedAsset(final String assetName, final Repository repository) {
    return repository.facet(ContentFacet.class).assets().path(assetName).find().map(a -> (Asset) a);
  }

  private void validateRepositoryConfiguration(final Repository repository) {
    String repositoryName = repository.getName();
    checkArgument(repository.getConfiguration().getAttributes() != null,
        STR."Repository configuration not found \{repositoryName}");
    checkArgument(repository.getConfiguration().getAttributes().get(STORAGE) != null,
        STR."No storage configuration found for the repository \{repositoryName}");
    checkArgument(
        isNotBlank((String) repository.getConfiguration().getAttributes().get(STORAGE).get(BLOB_STORE_NAME)),
        STR."Blob store name not set for repository \{repositoryName}");
  }
}