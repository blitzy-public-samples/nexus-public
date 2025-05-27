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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.tools.OrphanedBlobFinder;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.BLOB_STORE_NAME;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STORAGE;

/**
 * Detects orphaned blobs (i.e. non-deleted blobs that exist in the blobstore but not the asset table)
 * using Java 21 Virtual Threads for improved concurrency and performance.
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

  @Inject
  public DatastoreOrphanedBlobFinder(final RepositoryManager repositoryManager, final BlobStoreManager blobStoreManager) {
    this.repositoryManager = checkNotNull(repositoryManager);
    this.blobStoreManager = checkNotNull(blobStoreManager);
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
   * using Virtual Threads for concurrent processing.
   *
   * @param repository - where to look for orphaned blobs
   * @param handler    - callback to handle an orphaned blob
   */
  @Override
  public void detect(final Repository repository, final Consumer<BlobId> handler) {
    validateRepositoryConfiguration(repository);

    detect(getBlobStoreForRepository(repository), handler);
  }

  /**
   * Detects orphaned blobs in the given blob store using Virtual Threads for concurrent processing.
   * This implementation leverages Java 21 Virtual Threads to efficiently process large numbers of blobs
   * with minimal overhead, especially for I/O-bound operations like retrieving blob attributes.
   *
   * @param blobStore - the blob store to scan for orphaned blobs
   * @param handler   - callback to handle each orphaned blob that is found
   */
  private void detect(final BlobStore blobStore, final Consumer<BlobId> handler) {
    Stream<BlobId> blobIds = blobStore.getBlobIdStream();
    
    // Use a ConcurrentHashMap to track processing status
    ConcurrentHashMap<BlobId, Boolean> processedBlobs = new ConcurrentHashMap<>();
    AtomicInteger activeThreads = new AtomicInteger(0);
    CountDownLatch completionLatch = new CountDownLatch(1);
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Process each blob ID with a dedicated virtual thread
      blobIds.forEach(id -> {
        activeThreads.incrementAndGet();
        executor.submit(() -> {
          try {
            // Skip if already processed (defensive check)
            if (processedBlobs.putIfAbsent(id, Boolean.TRUE) != null) {
              return;
            }
            
            BlobAttributes attributes = blobStore.getBlobAttributes(id);
            if (attributes != null) {
              checkIfOrphaned(handler, id, attributes);
            }
            else {
              log.warn(STR."Skipping cleanup for blob \{id} because blob properties not found");
            }
          }
          catch (Exception e) {
            log.error(STR."Error processing blob \{id}", e);
          }
          finally {
            // If this is the last active thread, signal completion
            if (activeThreads.decrementAndGet() == 0) {
              completionLatch.countDown();
            }
          }
        });
      });
      
      // Wait for all virtual threads to complete
      try {
        completionLatch.await();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn(STR."Orphaned blob detection interrupted");
      }
    }
  }

  /**
   * Checks if a blob is orphaned by verifying its association with repository assets.
   * This method is designed to be called from Virtual Threads and handles I/O operations
   * efficiently without blocking platform threads.
   *
   * @param handler    - callback to handle the blob if it's orphaned
   * @param id         - the blob ID to check
   * @param attributes - the blob's attributes
   */
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
        // Use Optional to simplify the asset lookup and processing logic
        findAssociatedAsset(assetName, repository).ifPresentOrElse(
            asset -> {
              Optional<BlobRef> blobRefOpt = asset.blob().map(AssetBlob::blobRef);
              if (blobRefOpt.isPresent()) {
                BlobRef blobRef = blobRefOpt.get();
                if (!blobRef.getBlobId().asUniqueString().equals(id.asUniqueString()) && !attributes.isDeleted()) {
                  handler.accept(id);
                }
                else if (attributes.isDeleted()) {
                  log.debug(STR."Blob \{id.asUniqueString()} in repository \{repositoryName} not considered orphaned because it is already marked soft-deleted");
                }
              }
              else {
                // Asset exists but has no blob reference
                if (!attributes.isDeleted()) {
                  handler.accept(id);
                }
              }
            },
            () -> {
              // No asset found for this blob, consider it orphaned if not deleted
              if (!attributes.isDeleted()) {
                log.debug(STR."Blob \{id.asUniqueString()} considered orphaned because no asset with path \{assetName} exists in repository \{repositoryName}");
                handler.accept(id);
              }
            }
        );
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

  /**
   * Validates repository configuration to ensure it has the necessary attributes for blob store operations.
   * Optimized to handle thread context switches efficiently when called from Virtual Threads.
   *
   * @param repository - the repository to validate
   */
  private void validateRepositoryConfiguration(final Repository repository) {
    // Perform all validation checks in a single method call to minimize context switching overhead
    checkArgument(repository.getConfiguration().getAttributes() != null,
        STR."Repository configuration not found \{repository.getName()}");
    checkArgument(repository.getConfiguration().getAttributes().get(STORAGE) != null,
        STR."No storage configuration found for the repository \{repository.getName()}");
    checkArgument(
        isNotBlank((String) repository.getConfiguration().getAttributes().get(STORAGE).get(BLOB_STORE_NAME)),
        STR."Blob store name not set for repository \{repository.getName()}");
  }
}