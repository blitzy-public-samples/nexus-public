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

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.text.UnitFormatter.formatStorage;
import static java.lang.String.format;

/**
 * A {@link BlobStoreQuota} which checks that a blob store has at least a certain amount of space left.
 * Uses Java 21 Virtual Threads for improved I/O throughput when checking available space.
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
  
  // Default timeout for space checking operations (in milliseconds)
  private static final long DEFAULT_TIMEOUT_MS = 5000;
  
  // Virtual thread executor for I/O operations
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Override
  public void validateConfig(final BlobStoreConfiguration config) {
    if (getLimit(config) <= 0) {
      throw new ValidationErrorsException(DISPLAY_NAME + " quotas must have a Quota Limit greater than 0");
    }
  }

  @Override
  public BlobStoreQuotaResult check(final BlobStore blobStore) {
    checkNotNull(blobStore);
    
    String name = blobStore.getBlobStoreConfiguration().getName();
    String threadName = Thread.currentThread().toString();
    log.debug("Starting quota check for blob store {} on {}", name, threadName);
    
    try {
      // Submit space checking task to virtual thread executor
      Future<BlobStoreQuotaResult> future = virtualThreadExecutor.submit(() -> {
        Thread currentThread = Thread.currentThread();
        log.debug("Checking available space for blob store {} on {}", name, currentThread);
        
        try {
          // Check for thread interruption before proceeding
          if (Thread.interrupted()) {
            throw new InterruptedException("Virtual thread was interrupted before space check could begin");
          }
          
          long availableSpace = blobStore.getMetrics().getAvailableSpace();
          boolean isUnlimited = blobStore.getMetrics().isUnlimited();
          long limit = getLimit(blobStore.getBlobStoreConfiguration());
          
          String msg = format("Blob store %s is limited to having %s available space, and has %s space remaining",
              name,
              formatStorage(limit),
              formatStorage(availableSpace));
          
          log.debug("Completed quota check for blob store {} on {}: {}", name, currentThread, msg);
          return new BlobStoreQuotaResult(!isUnlimited && availableSpace < limit, name, msg);
        }
        catch (RuntimeException e) {
          log.error("Error checking available space for blob store {} on {}: {}", 
              name, currentThread, e.getMessage(), e);
          throw e;
        }
      });
      
      // Wait for the result with a timeout to prevent hanging
      return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // Restore the interrupted status
      log.warn("Quota check for blob store {} was interrupted on {}", name, threadName, e);
      throw new BlobStoreException("Quota check was interrupted", e);
    }
    catch (ExecutionException e) {
      log.error("Error during quota check for blob store {} on {}", name, threadName, e.getCause());
      throw new BlobStoreException("Error during quota check: " + e.getCause().getMessage(), e.getCause());
    }
    catch (TimeoutException e) {
      log.warn("Quota check for blob store {} timed out after {} ms on {}", 
          name, DEFAULT_TIMEOUT_MS, threadName, e);
      throw new BlobStoreException("Quota check timed out after " + DEFAULT_TIMEOUT_MS + " ms", e);
    }
    catch (CancellationException e) {
      log.warn("Quota check for blob store {} was cancelled on {}", name, threadName, e);
      throw new BlobStoreException("Quota check was cancelled", e);
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