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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
  
  // Default timeout for quota check operations in seconds
  private static final int DEFAULT_QUOTA_CHECK_TIMEOUT_SECONDS = 30;

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

  @Nullable
  @Override
  public BlobStoreQuotaResult checkQuota(final BlobStore blobStore) {
    checkNotNull(blobStore);
    BlobStoreConfiguration config = blobStore.getBlobStoreConfiguration();
    
    // Use Virtual Thread for executing the quota check to improve I/O operation performance
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      log.debug("Starting quota check for blob store {} using Virtual Thread", config.getName());
      
      Future<BlobStoreQuotaResult> future = executor.submit(() -> {
        try {
          return getQuota(config).map(quota -> {
            log.debug("Virtual Thread executing quota check for blob store {} with quota {}", 
                config.getName(), quota);
            return quota.check(blobStore);
          }).orElse(null);
        } catch (Exception e) {
          log.error("Error during Virtual Thread quota check for blob store {}: {}", 
              config.getName(), e.getMessage(), e);
          throw e;
        }
      });
      
      try {
        // Wait for the result with a timeout to prevent hanging
        BlobStoreQuotaResult result = future.get(DEFAULT_QUOTA_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        log.debug("Completed quota check for blob store {} using Virtual Thread", config.getName());
        return result;
      } catch (InterruptedException e) {
        // Handle Virtual Thread interruption
        log.warn("Virtual Thread quota check for blob store {} was interrupted", config.getName(), e);
        Thread.currentThread().interrupt(); // Preserve interrupt status
        return null;
      } catch (Exception e) {
        // Handle other exceptions (timeout, execution exception)
        log.error("Virtual Thread quota check for blob store {} failed: {}", 
            config.getName(), e.getMessage(), e);
        return null;
      }
    }
  }
}