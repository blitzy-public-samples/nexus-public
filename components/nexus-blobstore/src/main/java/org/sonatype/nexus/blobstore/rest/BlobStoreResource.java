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
import java.util.concurrent.Executor;
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
import static java.util.stream.Collectors.toList;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;

/**
 * REST resource for blob store management operations.
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
  
  // Virtual thread executor for I/O-bound operations
  private final Executor virtualThreadExecutor;

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
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @GET
  public List<GenericBlobStoreApiResponse> listBlobStores() {
    Map<String, BlobStore> blobstoresByName = blobStoreManager.getByName();
    return store.list()
        .stream()
        .map(
            configuration -> new GenericBlobStoreApiResponse(configuration,
                blobstoresByName.get(configuration.getName())))
        .collect(toList());
  }

  @Override
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:delete")
  @DELETE
  @Path("/{name}")
  public void deleteBlobStore(@PathParam("name") final String name) throws Exception {
    if (!blobStoreManager.exists(name)) {
      BlobStoreResourceUtil.throwCreateBlobStoreNotFoundException("", name);
    }
    try {
      // Use virtual threads for I/O-bound blob store deletion operation
      virtualThreadExecutor.execute(() -> {
        try {
          blobStoreManager.delete(name);
        } 
        catch (Exception e) {
          // Propagate exception to the calling thread
          if (e instanceof BlobStoreException) {
            log.error(STR."Error deleting blob store \{name}: \{e.getMessage()}", e);
          } else {
            log.error(STR."Unexpected error deleting blob store \{name}", e);
          }
          throw new RuntimeException(e);
        }
      });
    }
    catch (RuntimeException e) {
      // Unwrap the cause if it's a BlobStoreException
      if (e.getCause() instanceof BlobStoreException) {
        BlobStoreResourceUtil.throwBlobStoreBadRequestException(e.getCause().getMessage());
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
    BlobStore blobStore = blobStoreManager.get(name);

    if (blobStore == null) {
      throw new WebApplicationException(STR."No blob store found for id '\{name}'", NOT_FOUND);
    }

    // Use pattern matching for more concise type checking
    var result = quotaService.checkQuota(blobStore);
    
    return switch(result) {
      case null -> BlobStoreQuotaResultXO.asNoQuotaXO(name);
      case BlobStoreQuotaResult quotaResult -> BlobStoreQuotaResultXO.asQuotaXO(quotaResult);
    };
  }

  @Override
  @POST
  @Path("test-connection")
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @Validate
  public void verifyConnection(final @NotNull @Valid BlobStoreConnectionXO blobStoreConnectionXO) {
    try {
      // Use pattern matching to simplify null check
      ConnectionChecker conChecker = switch(connectionCheckers.get(blobStoreConnectionXO.getType())) {
        case null -> throw new IllegalArgumentException(STR."No connection checker found for type \{blobStoreConnectionXO.getType()}");
        case ConnectionChecker checker -> checker;
      };
      
      // Use virtual threads for I/O-bound connection testing
      virtualThreadExecutor.execute(() -> {
        try {
          conChecker.verifyConnection(blobStoreConnectionXO.getName(), blobStoreConnectionXO.getAttributes());
        } catch (Exception e) {
          // Propagate exception to the calling thread
          throw new RuntimeException(e);
        }
      });
    }
    catch (RuntimeException e) {
      // Unwrap the cause if it exists
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      
      if (cause instanceof BlobStoreConnectionException ce) {
        log.error(STR."Can't connect to \{blobStoreConnectionXO.getType()} blob store", ce);
        throw new WebApplicationException(Response.status(BAD_REQUEST).entity(ce.getMessage()).build());
      }
      else {
        log.warn(STR."Can't connect to \{blobStoreConnectionXO.getType()} blob store", cause);
        throw new WebApplicationException(Response.status(BAD_REQUEST).entity(messages.connectionError()).build());
      }
    }
  }
}