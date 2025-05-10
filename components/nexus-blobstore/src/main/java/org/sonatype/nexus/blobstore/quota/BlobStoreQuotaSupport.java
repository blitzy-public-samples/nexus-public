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
package org.sonatype.nexus.blobstore.quota;

import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A base class for {@link BlobStoreQuota} that holds constants which map to config values in {@link
 * BlobStoreConfiguration}
 *
 * @since 3.14
 */
public abstract class BlobStoreQuotaSupport
    extends ComponentSupport
    implements BlobStoreQuota
{
  public static final String ROOT_KEY = "blobStoreQuotaConfig";

  public static final String TYPE_KEY = "quotaType";

  public static final String LIMIT_KEY = "quotaLimitBytes";

  /**
   * Creates a Runnable that executes the quota check job in a Virtual Thread.
   * Virtual Threads are lightweight threads that are managed by the JVM rather than the OS,
   * making them ideal for I/O-bound operations like quota checks.
   *
   * @param blobStore    the blob store to check
   * @param quotaService the quota service to use for checking
   * @param logger       the logger to use for logging
   * @return a Runnable that executes the quota check job
   */
  public static Runnable createQuotaCheckJob(
      final BlobStore blobStore,
      final BlobStoreQuotaService quotaService,
      final Logger logger)
  {
    return () -> {
      // Use Virtual Threads for executing quota check operations
      // This improves scalability by not blocking platform threads during I/O operations
      try {
        Thread.startVirtualThread(() -> quotaCheckJob(blobStore, quotaService, logger));
      }
      catch (Exception e) {
        // Handle any errors that might occur when starting the virtual thread
        logger.error("Failed to start virtual thread for quota check on {}", 
            blobStore.getBlobStoreConfiguration().getName(), e);
      }
    };
  }

  /**
   * Executes the quota check job.
   * This method is designed to be compatible with Virtual Thread execution context.
   * It ensures thread safety and proper error handling for operations running in Virtual Threads.
   *
   * @param blobStore    the blob store to check
   * @param quotaService the quota service to use for checking
   * @param logger       the logger to use for logging
   */
  @VisibleForTesting
  static void quotaCheckJob(final BlobStore blobStore, final BlobStoreQuotaService quotaService, final Logger logger) {
    // Use AtomicReference to ensure thread safety when accessing the result
    AtomicReference<BlobStoreQuotaResult> resultRef = new AtomicReference<>();
    
    try {
      // Execute the quota check and store the result in the AtomicReference
      resultRef.set(quotaService.checkQuota(blobStore));
      
      // Check if there's a violation and log it if necessary
      BlobStoreQuotaResult result = resultRef.get();
      if (result != null && result.isViolation()) {
        logger.warn(result.getMessage());
      }
    }
    catch (Exception e) {
      // Enhanced error handling for Virtual Thread context
      // Don't propagate, as this stops subsequent executions
      String blobStoreName = "unknown";
      try {
        blobStoreName = blobStore.getBlobStoreConfiguration().getName();
      }
      catch (Exception ex) {
        // If we can't get the blob store name, just use the default
        logger.debug("Could not get blob store name for error logging", ex);
      }
      logger.error("Quota check exception for {}", blobStoreName, e);
    }
  }

  /**
   * Gets the blob store's quota limit from the blob store's configuration.
   *
   * @return the quota's limit
   * @since 3.15
   */
  public static long getLimit(final BlobStoreConfiguration config) {
    Number limitObj = config.attributes(ROOT_KEY).get(LIMIT_KEY, Number.class);
    checkArgument(limitObj != null, "Limit not found in configuration");
    return limitObj.longValue();
  }

  /**
   * @return the quota's type
   * @since 3.19
   */
  public static String getType(final BlobStoreConfiguration config) {
    return config.attributes(ROOT_KEY).get(TYPE_KEY, String.class);
  }

  @Override
  public String toString() {
    return getDisplayName();
  }
}