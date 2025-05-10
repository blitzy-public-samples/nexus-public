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

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.rest.ValidationErrorsException;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.ROOT_KEY;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.TYPE_KEY;

/**
 * Default implementation of {@link BlobStoreQuotaService}
 *
 * @since 3.14
 */
@Named
@Singleton
public class BlobStoreQuotaServiceImpl
    extends ComponentSupport
    implements BlobStoreQuotaService
{
  private final Map<String, BlobStoreQuota> quotas;
  
  /**
   * Timeout for quota check operations in milliseconds
   */
  private static final long QUOTA_CHECK_TIMEOUT_MS = 30000;

  @Inject
  public BlobStoreQuotaServiceImpl(final Map<String, BlobStoreQuota> quotas) {
    this.quotas = checkNotNull(quotas);
  }

  @Override
  public void validateSoftQuotaConfig(final BlobStoreConfiguration config) {
    getQuotaType(config).ifPresent(type -> {
      if (!quotas.containsKey(type)) {
        throw new ValidationErrorsException("To enable Soft Quota, you must select a Type of Quota");
      }
    });
    getQuota(config).ifPresent(quota -> quota.validateConfig(config));
  }

  private Optional<String> getQuotaType(final BlobStoreConfiguration config) {
    return ofNullable(config.attributes(ROOT_KEY).get(TYPE_KEY, String.class));
  }

  private Optional<BlobStoreQuota> getQuota(final BlobStoreConfiguration config) {
    Optional<String> quotaType = getQuotaType(config);
    Optional<BlobStoreQuota> quota = quotaType.map(quotas::get);

    if (quotaType.isPresent() && !quota.isPresent()) {
      log.error("For blob store {} unable to find quota type for key {}", config.getName(), quotaType.get());
    }
    return quota;
  }

  /**
   * Checks if a BlobStore exceeds its quota using Virtual Threads for improved I/O performance.
   * 
   * This implementation uses Java 21 Virtual Threads to execute the quota check operation,
   * which is typically I/O bound. Virtual Threads are lightweight and managed by the JVM,
   * allowing for better resource utilization during I/O operations.
   *
   * @param blobStore the blob store to check
   * @return the quota check result or null if no quota is configured
   */
  @Nullable
  @Override
  public BlobStoreQuotaResult checkQuota(final BlobStore blobStore) {
    checkNotNull(blobStore);
    BlobStoreConfiguration config = blobStore.getBlobStoreConfiguration();
    
    Optional<BlobStoreQuota> quotaOptional = getQuota(config);
    if (!quotaOptional.isPresent()) {
      return null;
    }
    
    BlobStoreQuota quota = quotaOptional.get();
    String blobStoreName = config.getName();
    
    log.debug("Starting Virtual Thread for checking blob store {} quota {}", blobStoreName, quota);
    
    try {
      // Use structured concurrency with a timeout to ensure the operation completes in a timely manner
      try (ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
        // Fork a virtual thread to perform the quota check
        var subtask = scope.fork(() -> {
          log.debug("Virtual Thread executing quota check for blob store {}", blobStoreName);
          try {
            // Thread confinement: all quota check operations happen within this virtual thread
            return quota.check(blobStore);
          } catch (Exception e) {
            log.error("Error checking quota for blob store {}: {}", blobStoreName, e.getMessage(), e);
            throw e;
          }
        });
        
        // Wait for the quota check to complete with a timeout
        try {
          scope.joinUntil(java.time.Instant.now().plusMillis(QUOTA_CHECK_TIMEOUT_MS));
          // Propagate any exceptions from the subtask
          scope.throwIfFailed(e -> new RuntimeException("Quota check failed: " + e.getMessage(), e));
          
          // Get the result from the completed subtask
          BlobStoreQuotaResult result = subtask.get();
          log.debug("Virtual Thread completed quota check for blob store {}: {}", blobStoreName, 
              result != null ? result.isViolation() ? "Violation detected" : "No violation" : "No result");
          return result;
        } catch (InterruptedException e) {
          // Handle interruption of the current thread
          Thread.currentThread().interrupt();
          log.warn("Quota check for blob store {} was interrupted", blobStoreName, e);
          return null;
        } catch (ExecutionException e) {
          // Handle execution exceptions from the virtual thread
          log.error("Quota check execution failed for blob store {}", blobStoreName, e.getCause());
          return null;
        } catch (CancellationException e) {
          // Handle cancellation of the virtual thread
          log.warn("Quota check for blob store {} was cancelled", blobStoreName, e);
          return null;
        } catch (IllegalStateException e) {
          // Handle timeout or other state issues
          log.error("Quota check failed due to illegal state for blob store {}", blobStoreName, e);
          return null;
        }
      }
    } catch (Exception e) {
      // Catch any unexpected exceptions from the structured concurrency framework
      log.error("Unexpected error during quota check for blob store {}", blobStoreName, e);
      return null;
    }
  }
}
