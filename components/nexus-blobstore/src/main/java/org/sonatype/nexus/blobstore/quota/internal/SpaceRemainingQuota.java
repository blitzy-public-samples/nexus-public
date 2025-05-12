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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.text.UnitFormatter.formatStorage;
import static java.lang.String.format;
import static java.lang.Thread.currentThread;

/**
 * A {@link BlobStoreQuota} which checks that a blob store has at least a certain amount of space left.
 * This implementation uses Virtual Threads for improved I/O throughput when checking available space.
 *
 * @since 3.14
 */
@Named(SpaceRemainingQuota.ID)
@Singleton
public class SpaceRemainingQuota
    extends BlobStoreQuotaSupport
{
  private static final Logger log = LoggerFactory.getLogger(SpaceRemainingQuota.class);
  
  public static final String ID = "spaceRemainingQuota";

  private static final String DISPLAY_NAME = "Space Remaining";

  @Override
  public void validateConfig(final BlobStoreConfiguration config) {
    if (getLimit(config) <= 0) {
      throw new ValidationErrorsException(DISPLAY_NAME + " quotas must have a Quota Limit greater than 0");
    }
  }

  @Override
  public BlobStoreQuotaResult check(final BlobStore blobStore) {
    checkNotNull(blobStore);
    
    String threadName = currentThread().toString();
    String blobStoreName = blobStore.getBlobStoreConfiguration().getName();
    log.debug("Starting quota check on {} for blob store {}", threadName, blobStoreName);
    
    try {
      // Use a CompletableFuture with Virtual Thread to perform the I/O-bound space check operation
      CompletableFuture<BlobStoreQuotaResult> future = CompletableFuture.supplyAsync(() -> {
        try {
          // Get metrics inside the Virtual Thread
          long availableSpace = blobStore.getMetrics().getAvailableSpace();
          boolean isUnlimited = blobStore.getMetrics().isUnlimited();
          long limit = getLimit(blobStore.getBlobStoreConfiguration());
          
          String msg = format("Blob store %s is limited to having %s available space, and has %s space remaining",
              blobStoreName,
              formatStorage(limit),
              formatStorage(availableSpace));
          
          log.debug("Quota check completed on {} - blob store: {}, available: {}, limit: {}", 
              currentThread().toString(), blobStoreName, formatStorage(availableSpace), formatStorage(limit));
              
          return new BlobStoreQuotaResult(!isUnlimited && availableSpace < limit, blobStoreName, msg);
        }
        catch (InterruptedException e) {
          // Handle Virtual Thread interruption
          log.warn("Quota check interrupted on {} for blob store {}", currentThread().toString(), blobStoreName, e);
          Thread.currentThread().interrupt(); // Restore the interrupted status
          throw new RuntimeException("Quota check interrupted for blob store " + blobStoreName, e);
        }
        catch (Exception e) {
          // Optimize exception handling for Virtual Threads
          log.error("Error during quota check on {} for blob store {}", currentThread().toString(), blobStoreName, e);
          throw new RuntimeException("Error during quota check for blob store " + blobStoreName, e);
        }
      }, Thread.ofVirtual().name("quota-check-" + blobStoreName).factory());
      
      // Wait for the Virtual Thread to complete and get the result
      return future.get();
    }
    catch (InterruptedException e) {
      log.warn("Quota check interrupted on {} for blob store {}", threadName, blobStoreName, e);
      Thread.currentThread().interrupt(); // Restore the interrupted status
      throw new RuntimeException("Quota check interrupted for blob store " + blobStoreName, e);
    }
    catch (ExecutionException e) {
      log.error("Error during quota check on {} for blob store {}", threadName, blobStoreName, e.getCause());
      throw new RuntimeException("Error during quota check for blob store " + blobStoreName, e.getCause());
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