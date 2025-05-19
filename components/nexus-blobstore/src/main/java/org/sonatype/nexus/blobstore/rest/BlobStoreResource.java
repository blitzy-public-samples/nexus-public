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
import java.util.concurrent.Executors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;
import org.sonatype.nexus.blobstore.ConnectionChecker;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConnectionException;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.validation.Validate;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static java.util.stream.Collectors.toList;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;

/**
 * REST resource for blob store management operations.
 * 
 * This implementation leverages Java 21 features including Virtual Threads for I/O-bound operations,
 * String Templates for improved logging, and Pattern Matching for type checks.
 *
 * @since 3.14
 */
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class BlobStoreResource
    extends ComponentSupport
    implements Resource, BlobStoreResourceDoc
{
  private final BlobStoreManager blobStoreManager;

  private final BlobStoreConfigurationStore store;

  private final BlobStoreQuotaService quotaService;

  private final Map<String, ConnectionChecker> connectionCheckers;

  private interface Messages
      extends MessageBundle
  {
    @DefaultMessage("Connection failed, check the logs for more information.")
    String connectionError();
  }

  private static final Messages messages = I18N.create(Messages.class);

  public BlobStoreResource(
      final BlobStoreManager blobStoreManager,
      final BlobStoreConfigurationStore store,
      final BlobStoreQuotaService quotaService,
      final Map<String, ConnectionChecker> connectionCheckers)
  {
    this.blobStoreManager = checkNotNull(blobStoreManager);
    this.store = checkNotNull(store);
    this.quotaService = checkNotNull(quotaService);
    this.connectionCheckers = connectionCheckers;
  }

  @Override
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @GET
  public List<GenericBlobStoreApiResponse> listBlobStores() {
    // This method is I/O-bound when retrieving blob store configurations and metrics
    // Using Virtual Threads for improved concurrency without blocking platform threads
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      Map<String, BlobStore> blobstoresByName = blobStoreManager.getByName();
      return store.list()
          .stream()
          .map(configuration -> new GenericBlobStoreApiResponse(
              configuration, blobstoresByName.get(configuration.getName())))
          .collect(toList());
    }).join();
  }

  @Override
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:delete")
  @DELETE
  @Path("/{name}")
  public void deleteBlobStore(@PathParam("name") final String name) throws Exception {
    // This method is I/O-bound when deleting blob store data
    // Using Virtual Threads for improved concurrency without blocking platform threads
    Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      if (!blobStoreManager.exists(name)) {
        BlobStoreResourceUtil.throwCreateBlobStoreNotFoundException("", name);
      }
      try {
        blobStoreManager.delete(name);
        return null; // Needed for CompletableFuture<Void>
      }
      catch (BlobStoreException e) {
        // Using pattern matching for exception handling
        if (e instanceof BlobStoreException bse) {
          BlobStoreResourceUtil.throwBlobStoreBadRequestException(bse.getMessage());
        }
        throw e;
      }
    }).join();
  }

  @Override
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @GET
  @Path("/{name}/quota-status")
  public BlobStoreQuotaResultXO quotaStatus(@PathParam("name") final String name) {
    // This method is I/O-bound when checking quota metrics
    // Using Virtual Threads for improved concurrency without blocking platform threads
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      BlobStore blobStore = blobStoreManager.get(name);

      if (blobStore == null) {
        // Using String Templates instead of String.format for improved readability and performance
        throw new WebApplicationException(
            STR."No blob store found for id '{name}' ", 
            NOT_FOUND);
      }

      BlobStoreQuotaResult result = quotaService.checkQuota(blobStore);

      // Using pattern matching for improved type checking and readability
      return switch(result) {
        case null -> BlobStoreQuotaResultXO.asNoQuotaXO(name);
        case BlobStoreQuotaResult quotaResult -> BlobStoreQuotaResultXO.asQuotaXO(quotaResult);
      };
    }).join();
  }

  @Override
  @POST
  @Path("test-connection")
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @Validate
  public void verifyConnection(final @NotNull @Valid BlobStoreConnectionXO blobStoreConnectionXO) {
    // This method is I/O-bound when testing remote connections
    // Using Virtual Threads for improved concurrency without blocking platform threads
    Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        // Get the appropriate connection checker for this blob store type
        ConnectionChecker conChecker = connectionCheckers.get(blobStoreConnectionXO.getType());
        if (conChecker == null) {
          throw new IllegalArgumentException(STR."No connection checker available for type '{blobStoreConnectionXO.getType()}'.");
        }
        
        // Verify the connection using the provided attributes
        conChecker.verifyConnection(blobStoreConnectionXO.getName(), blobStoreConnectionXO.getAttributes());
        return null; // Needed for CompletableFuture<Void>
      }
      catch (Exception e) {
        // Using pattern matching for exception handling with improved logging
        if (e instanceof BlobStoreConnectionException ce) {
          // Using String Templates for structured logging
          log.error(STR."Can't connect to {blobStoreConnectionXO.getType()} blob store: {ce.getMessage()}", ce);
          throw new WebApplicationException(Response.status(BAD_REQUEST).entity(ce.getMessage()).build());
        }
        else {
          // Using String Templates for structured logging
          log.warn(STR."Can't connect to {blobStoreConnectionXO.getType()} blob store: {e.getMessage()}", e);
          throw new WebApplicationException(Response.status(BAD_REQUEST).entity(messages.connectionError()).build());
        }
      }
    }).join();
  }
}