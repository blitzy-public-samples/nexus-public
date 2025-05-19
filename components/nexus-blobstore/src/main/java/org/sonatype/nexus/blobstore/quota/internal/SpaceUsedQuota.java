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
package org.sonatype.nexus.blobstore.quota.internal;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport;
import org.sonatype.nexus.rest.ValidationErrorsException;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.text.UnitFormatter.formatStorage;
import static java.lang.StringTemplate.STR;

/**
 * A {@link BlobStoreQuota} which checks that a blob store isn't using more space the limit.
 *
 * @since 3.14
 */
@Named(SpaceUsedQuota.ID)
@Singleton
public class SpaceUsedQuota
    extends BlobStoreQuotaSupport
{
  public static final String ID = "spaceUsedQuota";

  private static final String DISPLAY_NAME = "Space Used";
  
  // Lock to ensure thread safety during quota checking
  private final ReentrantLock quotaCheckLock = new ReentrantLock();

  @Override
  public void validateConfig(final BlobStoreConfiguration config) {
    if (getLimit(config) <= 0) {
      throw new ValidationErrorsException(DISPLAY_NAME + " quotas must have a Quota Limit greater than 0");
    }
  }

  @Override
  public BlobStoreQuotaResult check(final BlobStore blobStore) {
    checkNotNull(blobStore);
    
    // Acquire lock to ensure thread safety during quota checking
    quotaCheckLock.lock();
    try {
      // Create a virtual thread executor for I/O-bound operations
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit the storage calculation task to a virtual thread
        Future<Long> usedSpaceFuture = executor.submit(() -> blobStore.getMetrics().getTotalSize());
        
        // Get the blob store configuration and limit while the virtual thread is working
        String name = blobStore.getBlobStoreConfiguration().getName();
        long limit = getLimit(blobStore.getBlobStoreConfiguration());
        
        // Get the result from the virtual thread
        long usedSpace;
        try {
          usedSpace = usedSpaceFuture.get();
        } catch (Exception e) {
          // If there's an error, fall back to synchronous calculation
          usedSpace = blobStore.getMetrics().getTotalSize();
        }
        
        // Use Java 21 String Templates for message formatting
        String msg = STR."Blob store \{name} is using \{formatStorage(usedSpace)} space and has a limit of \{formatStorage(limit)}";

        return new BlobStoreQuotaResult(usedSpace > limit, name, msg);
      }
    } finally {
      // Always release the lock
      quotaCheckLock.unlock();
    }
  }

  @Override
  public String getDisplayName() {
    return DISPLAY_NAME;
  }

  @Override
  public String getId() {
    return ID;
  }
}