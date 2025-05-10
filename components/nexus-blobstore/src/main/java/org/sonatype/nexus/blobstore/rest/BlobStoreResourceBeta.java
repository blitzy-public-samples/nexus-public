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
package org.sonatype.nexus.blobstore.rest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.sonatype.nexus.blobstore.ConnectionChecker;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.rest.WebApplicationMessageException;

import io.swagger.annotations.Api;

import static org.sonatype.nexus.blobstore.rest.BlobStoreResourceBeta.RESOURCE_URI;
import static org.sonatype.nexus.rest.APIConstants.BETA_API_PREFIX;

/**
 * beta endpoint for BlobStore REST API
 *
 * @since 3.24
 * @deprecated moving to {@link BlobStoreResourceV1}
 */
@Api(hidden = true)
@Named
@Singleton
@Path(RESOURCE_URI)
@Deprecated
public class BlobStoreResourceBeta
    extends BlobStoreResource
{
  static final String RESOURCE_URI = BETA_API_PREFIX + "/blobstores";
  
  private final ExecutorService virtualThreadExecutor;

  /**
   * Constructor with dependency injection compatible with Guice 7.0.0
   */
  @Inject
  public BlobStoreResourceBeta(
      final BlobStoreManager blobStoreManager,
      final BlobStoreConfigurationStore store,
      final BlobStoreQuotaService quotaService,
      final Map<String, ConnectionChecker> connectionCheckers)
  {
    super(blobStoreManager, store, quotaService, connectionCheckers);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Override to implement Virtual Threads for I/O-bound operations
   */
  @Override
  public List<GenericBlobStoreApiResponse> listBlobStores() {
    try {
      return CompletableFuture.supplyAsync(super::listBlobStores, virtualThreadExecutor).join();
    } catch (Exception e) {
      log.error("Error listing blob stores using virtual threads", e);
      throw e;
    }
  }
  
  /**
   * Override to implement Virtual Threads for I/O-bound operations
   */
  @Override
  public void deleteBlobStore(final String name) throws Exception {
    try {
      CompletableFuture.runAsync(() -> {
        try {
          super.deleteBlobStore(name);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor).join();
    } catch (RuntimeException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }

  /**
   * Override to implement Virtual Threads for I/O-bound operations and use String Templates for error messages
   */
  @Override
  @Deprecated
  public BlobStoreQuotaResultXO quotaStatus(final String name) {
    String errorMessage = STR."Beta API endpoint for quota status (\{name}) is not supported";
    throw new WebApplicationMessageException(Status.BAD_REQUEST, errorMessage);
  }
  
  /**
   * Override to implement Virtual Threads for I/O-bound operations
   */
  @Override
  public void verifyConnection(final BlobStoreConnectionXO blobStoreConnectionXO) {
    try {
      CompletableFuture.runAsync(() -> super.verifyConnection(blobStoreConnectionXO), virtualThreadExecutor).join();
    } catch (Exception e) {
      log.error("Error verifying connection using virtual threads", e);
      if (e.getCause() instanceof WebApplicationException) {
        throw (WebApplicationException) e.getCause();
      }
      throw new WebApplicationException(Response.status(Status.BAD_REQUEST)
          .entity(STR."Connection verification failed: \{e.getMessage()}")
          .build());
    }
  }
  
  /**
   * Cleanup resources when the component is destroyed
   */
  @PreDestroy
  public void shutdown() {
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      log.debug("Shutting down virtual thread executor");
      virtualThreadExecutor.shutdown();
    }
  }
}