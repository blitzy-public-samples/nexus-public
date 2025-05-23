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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;

/**
 * Abstract support class for BlobStoreDescriptor implementations.
 * Optimized for Java 21 Virtual Thread execution.
 *
 * @since 3.14
 */
public abstract class BlobStoreDescriptorSupport
    implements BlobStoreDescriptor
{
  private final BlobStoreQuotaService quotaService;

  /**
   * Constructor with dependency injection for Guice 7.0.0 compatibility.
   *
   * @param quotaService the BlobStoreQuotaService to use for quota validation
   */
  @Inject
  public BlobStoreDescriptorSupport(final BlobStoreQuotaService quotaService) {
    this.quotaService = quotaService;
  }

  /**
   * Validates the blob store configuration, optimized for Virtual Thread execution.
   * This implementation ensures proper exception propagation in Virtual Thread context.
   *
   * @param configuration the configuration to validate
   */
  @Override
  public void validateConfig(final BlobStoreConfiguration configuration) {
    try {
      // For I/O-bound validation operations, use Virtual Threads to avoid blocking
      if (Thread.currentThread().isVirtual()) {
        // Already running in a Virtual Thread, execute directly to avoid nesting
        quotaService.validateSoftQuotaConfig(configuration);
      }
      else {
        // Execute validation in a Virtual Thread for better scalability
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          quotaService.validateSoftQuotaConfig(configuration);
        }, Thread.ofVirtual().factory());
        
        // Wait for completion and propagate any exceptions
        future.get();
      }
    }
    catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
      throw new RuntimeException("Quota validation was interrupted", e);
    }
    catch (ExecutionException e) {
      // Unwrap and propagate the actual cause
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new RuntimeException("Error during quota validation", cause);
    }
  }
}