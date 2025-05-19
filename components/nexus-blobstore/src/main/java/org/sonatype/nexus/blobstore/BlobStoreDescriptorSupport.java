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
package org.sonatype.nexus.blobstore;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nonnull;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;

import com.google.inject.Inject;

/**
 * Support class for BlobStoreDescriptor implementations.
 * 
 * @since 3.0
 */
public abstract class BlobStoreDescriptorSupport
    implements BlobStoreDescriptor
{
  /**
   * Default timeout for validation operations in seconds.
   */
  private static final long DEFAULT_VALIDATION_TIMEOUT_SECONDS = 30;
  
  private final BlobStoreQuotaService quotaService;

  /**
   * Constructor with required dependencies.
   *
   * @param quotaService the quota service to use for validation
   * @throws NullPointerException if quotaService is null
   */
  @Inject
  public BlobStoreDescriptorSupport(@Nonnull final BlobStoreQuotaService quotaService) {
    this.quotaService = Objects.requireNonNull(quotaService, "BlobStoreQuotaService cannot be null");
  }

  /**
   * Validates the blob store configuration using the quota service.
   * Optimized for execution in Virtual Thread context in Java 21.
   * <p>
   * This implementation uses Virtual Threads to perform validation without blocking platform threads,
   * allowing for higher concurrency and throughput. The validation operation has a timeout to prevent
   * hanging indefinitely.
   *
   * @param configuration the blob store configuration to validate
   * @throws BlobStoreException if validation fails or times out
   */
  @Override
  public void validateConfig(final BlobStoreConfiguration configuration) {
    Objects.requireNonNull(configuration, "BlobStore configuration cannot be null");
    
    try {
      // Use CompletableFuture with Virtual Thread executor for non-blocking I/O operations
      CompletableFuture<Void> future = CompletableFuture.runAsync(
          () -> quotaService.validateSoftQuotaConfig(configuration),
          Executors.newVirtualThreadPerTaskExecutor());
      
      // Add timeout to prevent hanging indefinitely
      future.orTimeout(DEFAULT_VALIDATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .exceptionally(ex -> {
            // Handle specific exception types with appropriate error messages
            if (ex instanceof TimeoutException) {
              throw new BlobStoreException("Validation timed out after " + 
                  DEFAULT_VALIDATION_TIMEOUT_SECONDS + " seconds", ex);
            }
            
            // Properly propagate exceptions in Virtual Thread context
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
              throw (RuntimeException) cause;
            }
            throw new BlobStoreException("Failed to validate blob store configuration", cause);
          })
          .join(); // Wait for completion
    }
    catch (RuntimeException e) {
      // Ensure exceptions are properly propagated with detailed messages
      throw new BlobStoreException("Error validating blob store configuration: " + e.getMessage(), e);
    }
  }
}
