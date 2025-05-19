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
import java.util.concurrent.Executors;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;

import org.sonatype.nexus.blobstore.ConnectionChecker;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.common.thread.VirtualThreadExecutorService;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static java.lang.String.format;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static org.sonatype.nexus.blobstore.rest.BlobStoreResourceV1.RESOURCE_URI;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;

/**
 * v1 endpoint for BlobStore REST API
 *
 * @since 3.24
 */
@Named
@Singleton
@Path(RESOURCE_URI)
public class BlobStoreResourceV1
    extends BlobStoreResource
{
  static final String RESOURCE_URI = V1_API_PREFIX + "/blobstores";
  
  private final VirtualThreadExecutorService virtualThreadExecutor;

  @Inject
  public BlobStoreResourceV1(
      final BlobStoreManager blobStoreManager,
      final BlobStoreConfigurationStore store,
      final BlobStoreQuotaService quotaService,
      final Map<String, ConnectionChecker> connectionCheckers)
  {
    super(blobStoreManager, store, quotaService, connectionCheckers);
    this.virtualThreadExecutor = new VirtualThreadExecutorService(
        Executors.newVirtualThreadPerTaskExecutor());
  }
  
  @Override
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @GET
  public List<GenericBlobStoreApiResponse> listBlobStores() {
    // Use virtual threads for I/O-bound operations to improve scalability
    try {
      return virtualThreadExecutor.supplyAsync(() -> super.listBlobStores()).join();
    }
    catch (Exception e) {
      // Ensure proper exception propagation in Virtual Thread context
      if (e.getCause() != null) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        }
        throw new RuntimeException(e.getCause());
      }
      throw e;
    }
  }

  @Override
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @GET
  @Path("/{name}/quota-status")
  public BlobStoreQuotaResultXO quotaStatus(@PathParam("name") final String name) {
    // Use virtual threads for I/O-bound operations to improve scalability
    try {
      return virtualThreadExecutor.supplyAsync(() -> {
        BlobStore blobStore = blobStoreManager.get(name);

        if (blobStore == null) {
          throw new WebApplicationException(format("No blob store found for id '%s' ", name), NOT_FOUND);
        }

        BlobStoreQuotaResult result = quotaService.checkQuota(blobStore);

        return result != null ? BlobStoreQuotaResultXO.asQuotaXO(result) : BlobStoreQuotaResultXO.asNoQuotaXO(name);
      }).join();
    }
    catch (Exception e) {
      // Ensure proper exception propagation in Virtual Thread context
      if (e.getCause() != null) {
        if (e.getCause() instanceof WebApplicationException) {
          throw (WebApplicationException) e.getCause();
        }
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        }
        throw new RuntimeException(e.getCause());
      }
      throw e;
    }
  }
}