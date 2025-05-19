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
package org.sonatype.nexus.repository.content.tasks;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.logging.task.ProgressLogIntervalHelper;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.store.AssetBlobStore;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.CancelableHelper;
import org.sonatype.nexus.scheduling.TaskInterruptedException;

import com.google.common.hash.HashCode;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.BLOB_STORE_NAME;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STORAGE;

/**
 * Support class for a task to generate sha256 hashes
 */
public abstract class GenerateChecksumTaskSupport
    extends RepositoryTaskSupport
    implements Cancelable
{
  private static class ResultCount
  {
    AtomicInteger updated = new AtomicInteger(0);

    AtomicInteger skipped = new AtomicInteger(0);

    AtomicInteger error = new AtomicInteger(0);

    AtomicInteger total = new AtomicInteger(0);
  }

  private static final int ASSET_BROWSE_LIMIT = 100;
  
  private static final int VIRTUAL_THREAD_BATCH_SIZE = 25;

  private int bufferSize;

  private BlobStoreManager blobStoreManager;

  private MessageDigest messageDigest;

  @Inject
  public void init(@Named("${nexus.calculateChecksums.bufferSize:-32768}") final int bufferSize,
      final BlobStoreManager blobStoreManager) throws NoSuchAlgorithmException {
    // Ensure at least a 4K buffer
    this.bufferSize = Math.max(4096, bufferSize);
    this.blobStoreManager = checkNotNull(blobStoreManager);

    messageDigest = MessageDigest.getInstance(HashAlgorithm.SHA256.name());
  }

  private BlobStore getBlobStore(final Repository repository) {
    String name = repository.getConfiguration().attributes(STORAGE).get(BLOB_STORE_NAME, String.class);
    return blobStoreManager.get(name);
  }

  @Override
  protected void execute(final Repository repository) {
    log.info("Checking for missing SHA256 checksums in repository: {}", repository.getName());
    ContentFacetSupport contentFacet = (ContentFacetSupport) repository.facet(ContentFacet.class);
    BlobStore blobStore = getBlobStore(repository);
    AssetBlobStore<?> assetBlobStore = contentFacet.stores().assetBlobStore;
    Continuation<FluentAsset> assets = contentFacet.assets().browse(ASSET_BROWSE_LIMIT, null);
    ResultCount resultCount = new ResultCount();
    try (ProgressLogIntervalHelper progressLogger = new ProgressLogIntervalHelper(log, 60)) {
      while (!isCanceled() && !assets.isEmpty()) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
          // Process assets in parallel batches using Virtual Threads
          for (FluentAsset asset : assets) {
            executor.submit(() -> {
              try {
                processAssetChecksums(blobStore, assetBlobStore, asset, resultCount, progressLogger);
              }
              catch (Exception e) {
                log.error("Error processing asset: {}", asset.path(), e);
                resultCount.error.incrementAndGet();
              }
              return null;
            });
          }
          
          // Wait for all tasks to complete before moving to the next batch
          executor.shutdown();
          if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
            log.warn("Timeout waiting for asset processing to complete");
            executor.shutdownNow();
          }
        }
        catch (InterruptedException e) {
          log.warn("Task interrupted while waiting for asset processing");
          Thread.currentThread().interrupt();
          throw new TaskInterruptedException("Task interrupted", e);
        }
        
        // Get the next batch of assets
        assets = contentFacet.assets().browse(ASSET_BROWSE_LIMIT, assets.nextContinuationToken());
      }
    }
    catch (TaskInterruptedException ex) {
      log.warn("Task interrupted. Processed {} assets. {} updated, {} skipped, {} errors.",
          resultCount.total.get(), resultCount.updated.get(), resultCount.skipped.get(), resultCount.error.get());
      throw ex;
    }
    log.info("Completed processing a total of {} assets. {} updated, {} skipped, {} errors.",
        resultCount.total.get(), resultCount.updated.get(), resultCount.skipped.get(), resultCount.error.get());
  }

  private void processAssetChecksums(
      final BlobStore blobStore, final AssetBlobStore<?> assetBlobStore,
      final FluentAsset asset, final ResultCount resultCount,
      final ProgressLogIntervalHelper progressLogger)
  {
    CancelableHelper.checkCancellation();
    String assetPath = asset.path();
    log.debug("Checking asset: {}", assetPath);
    AssetBlob assetBlob = asset.blob().orElse(null);
    if (assetBlob != null) {
      Map<String, String> checksums = assetBlob.checksums();
      if (!checksums.containsKey(HashAlgorithm.SHA256.name())) {
        BlobId blobId = assetBlob.blobRef().getBlobId();
        Blob blob = blobStore.get(blobId);
        if (blob != null) {
          String sha256 = calculateBlobChecksum(blob, assetPath);
          if (sha256 != null) {
            checksums.put(HashAlgorithm.SHA256.name(), sha256);
            log.debug("Updating blob SHA256 checksum for asset: {}, blobID: {}, checksum: {}",
                assetPath, blobId, sha256);
            assetBlobStore.setChecksums(assetBlob, checksums);
            resultCount.updated.incrementAndGet();
          }
          else {
            log.debug("Unable to create SHA256 checksum for asset: {}, blobID: {}", assetPath, blobId);
            resultCount.error.incrementAndGet();
          }
        }
        else {
          log.debug("No Blob associated with asset: {}. Skipping", assetPath);
          resultCount.skipped.incrementAndGet();
        }
      }
      else {
        log.debug("SHA256 checksum already present for asset: {}. Skipping", assetPath);
        resultCount.skipped.incrementAndGet();
      }
    }
    resultCount.total.incrementAndGet();
    progressLogger.info("Elapsed time: {}. Processed {} assets. {} updated, {} skipped, {} errors.",
        progressLogger.getElapsed(), resultCount.total.get(), resultCount.updated.get(), 
        resultCount.skipped.get(), resultCount.error.get());
  }

  private String calculateBlobChecksum(final Blob blob, final String assetPath) {
    log.debug("Calculating SHA256 checksum for {}", assetPath);
    try {
      // Use a virtual thread for I/O-bound checksum calculation
      return Thread.ofVirtual().name("checksum-" + assetPath).call(() -> {
        try (InputStream inputStream = blob.getInputStream()) {
          int bytesRead;
          byte[] buffer = new byte[bufferSize];
          MessageDigest localDigest = (MessageDigest) messageDigest.clone();
          
          while ((bytesRead = inputStream.read(buffer)) != -1) {
            // Check for cancellation during long-running operations
            CancelableHelper.checkCancellation();
            if (bytesRead > 0) {
              localDigest.update(buffer, 0, bytesRead);
            }
          }
          return HashCode.fromBytes(localDigest.digest()).toString();
        }
      });
    }
    catch (Exception e) {
      log.warn(String.format("Exception whilst calculating SHA256 checksum for %s: %s", assetPath,
              e.getLocalizedMessage()),
          log.isDebugEnabled() ? e : null);
      return null;
    }
  }

  @Override
  public String getMessage() {
    return "Generating sha256 hashes";
  }
}