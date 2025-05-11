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
package org.sonatype.nexus.blobstore.s3.rest.internal;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.rest.BlobStoreResourceUtil;
import org.sonatype.nexus.blobstore.s3.internal.S3BlobStore;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiModel;
import org.sonatype.nexus.crypto.secrets.SecretsFactory;
import org.sonatype.nexus.rapture.PasswordPlaceholder;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.rest.WebApplicationMessageException;

import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.CREATED;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static jakarta.ws.rs.core.Response.status;
import static org.apache.commons.lang3.StringUtils.equalsIgnoreCase;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.SECRET_ACCESS_KEY_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.TYPE;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiConstants.NOT_AN_S3_BLOB_STORE_MSG_FORMAT;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiModelMapper.map;

/**
 * REST API endpoints for creating, reading, updating and deleting an S3 blob store.
 * 
 * This implementation has been updated for Java 21 compatibility, leveraging Virtual Threads
 * for I/O-bound operations and pattern matching for improved code clarity.
 *
 * @since 3.20
 * @see <a href="https://openjdk.org/projects/jdk/21/">Java 21 Features</a>
 */
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class S3BlobStoreApiResource
    extends ComponentSupport
    implements Resource, S3BlobStoreApiResourceDoc
{
  private final S3BlobStoreApiUpdateValidation s3BlobStoreApiUpdateValidation;

  private final BlobStoreManager blobStoreManager;

  private final SecretsFactory secretsFactory;
  
  /**
   * Executor service that creates a new virtual thread for each task.
   * Virtual threads are lightweight and managed by the JVM, making them ideal for I/O-bound operations.
   */
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public S3BlobStoreApiResource(
      final BlobStoreManager blobStoreManager,
      final S3BlobStoreApiUpdateValidation validation,
      final SecretsFactory secretsFactory)
  {
    this.blobStoreManager = checkNotNull(blobStoreManager);
    this.s3BlobStoreApiUpdateValidation = checkNotNull(validation);
    this.secretsFactory = checkNotNull(secretsFactory);
  }

  @POST
  @Override
  @RequiresAuthentication
  @Path("/s3")
  @RequiresPermissions("nexus:blobstores:create")
  public Response createBlobStore(@Valid final S3BlobStoreApiModel request) {
    try {
      s3BlobStoreApiUpdateValidation.validateCreateRequest(request);
      final BlobStoreConfiguration blobStoreConfiguration = map(blobStoreManager.newConfiguration(), request);
      
      // Use Virtual Thread for I/O-bound operation
      // This avoids blocking platform threads during S3 operations
      CompletableFuture<Void> future = supplyAsync(() -> {
        blobStoreManager.create(blobStoreConfiguration);
        return null;
      }, virtualThreadExecutor);
      
      // Wait for the operation to complete
      future.join();
      
      return status(CREATED).build();
    }
    catch (Exception e) {
      log.error("Failed to create S3 blob store", e);
      throw new WebApplicationMessageException(BAD_REQUEST, e.getMessage());
    }
  }

  @PUT
  @Override
  @RequiresAuthentication
  @Path("/s3/{name}")
  @RequiresPermissions("nexus:blobstores:update")
  public void updateBlobStore(
      @Valid final S3BlobStoreApiModel request,
      @PathParam("name") final String blobStoreName) throws Exception
  {
    s3BlobStoreApiUpdateValidation.validateUpdateRequest(request, blobStoreName);

    if (isPasswordUntouched(request)) {
      // Did not update the password, just use the password we already have
      BlobStore currentS3Blobstore = blobStoreManager.get(blobStoreName);
      
      // Use pattern matching for instanceof check (Java 21 feature)
      if (currentS3Blobstore instanceof BlobStore store) {
        String secretId = store.getBlobStoreConfiguration()
            .getAttributes()
            .get(TYPE.toLowerCase())
            .get(SECRET_ACCESS_KEY_KEY)
            .toString();

        String decryptedSecretKey = new String(secretsFactory.from(secretId).decrypt());

        request.getBucketConfiguration()
            .getBucketSecurity()
            .setSecretAccessKey(decryptedSecretKey);
      }
    }

    try {
      final BlobStoreConfiguration blobStoreConfiguration = map(blobStoreManager.newConfiguration(), request);
      
      // Use Virtual Thread for I/O-bound operation
      // This avoids blocking platform threads during S3 operations
      CompletableFuture<Void> future = supplyAsync(() -> {
        try {
          blobStoreManager.update(blobStoreConfiguration);
          return null;
        } catch (Exception e) {
          throw new RuntimeException("Failed to update S3 blob store: " + e.getMessage(), e);
        }
      }, virtualThreadExecutor);
      
      // Wait for the operation to complete
      future.join();
    }
    catch (Exception e) {
      log.error("Failed to update S3 blob store {}", blobStoreName, e);
      throw new WebApplicationMessageException(INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  private boolean isPasswordUntouched(final S3BlobStoreApiModel request) {
    return request.getBucketConfiguration() != null && 
           request.getBucketConfiguration().getBucketSecurity() != null &&
           PasswordPlaceholder.is(request.getBucketConfiguration().getBucketSecurity().getSecretAccessKey());
  }

  @GET
  @Override
  @RequiresAuthentication
  @Path("/s3/{name}")
  @RequiresPermissions("nexus:blobstores:read")
  public S3BlobStoreApiModel getBlobStore(@PathParam("name") final String blobStoreName) {
    return fetchBlobStoreConfiguration(blobStoreName)
        .orElseThrow(() -> BlobStoreResourceUtil.createBlobStoreNotFoundException(S3BlobStore.TYPE, blobStoreName));
  }

  private Optional<S3BlobStoreApiModel> fetchBlobStoreConfiguration(final String blobStoreName) {
    // Use Virtual Thread for I/O-bound operation
    // This avoids blocking platform threads during S3 operations
    CompletableFuture<Optional<S3BlobStoreApiModel>> future = supplyAsync(() -> {
      Optional<S3BlobStoreApiModel> result = ofNullable(blobStoreManager.get(blobStoreName))
          .map(BlobStore::getBlobStoreConfiguration)
          .map(this::ensureBlobStoreTypeIsS3)
          .map(S3BlobStoreApiConfigurationMapper::map);
          
      if (result.isPresent() && isAuthenticationDataPresent(result.get())) {
        result.get().getBucketConfiguration().getBucketSecurity().setSecretAccessKey(PasswordPlaceholder.get());

        if (hasSessionToken(result.get())) {
          result.get().getBucketConfiguration().getBucketSecurity().setSessionToken(PasswordPlaceholder.get());
        }
      }
      return result;
    }, virtualThreadExecutor);
    
    // Wait for the operation to complete
    return future.join();
  }

  private boolean isAuthenticationDataPresent(final S3BlobStoreApiModel s3BlobStoreApiModel) {
    return s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity() != null &&
        s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getAccessKeyId() != null &&
        isNotEmpty(s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getAccessKeyId());
  }

  private boolean hasSessionToken(final S3BlobStoreApiModel s3BlobStoreApiModel) {
    return s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getSessionToken() != null &&
        isNotEmpty(s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getSessionToken());
  }

  private BlobStoreConfiguration ensureBlobStoreTypeIsS3(final BlobStoreConfiguration configuration) {
    final String type = configuration.getType();
    if (!equalsIgnoreCase(TYPE, type)) {
      throw new WebApplicationMessageException(BAD_REQUEST,
          String.format(NOT_AN_S3_BLOB_STORE_MSG_FORMAT, configuration.getName()), APPLICATION_JSON);
    }
    return configuration;
  }

  @DELETE
  @RequiresAuthentication
  @Path("/s3")
  @RequiresPermissions("nexus:blobstores:delete")
  @ApiOperation(value = "Delete a blob store with an empty name", hidden = true)
  public Response deleteBlobStoreWithEmptyName() {
    String blobStoreName = "";
    try {
      // Use Virtual Thread for I/O-bound operation
      // This avoids blocking platform threads during S3 operations
      CompletableFuture<Response> future = supplyAsync(() -> {
        BlobStore blobStore = blobStoreManager.get(blobStoreName);
        if (blobStore == null) {
          return Response.status(Response.Status.NOT_FOUND)
              .entity("Blob store not found")
              .build();
        }
        try {
          blobStoreManager.delete(blobStoreName);
          return Response.status(Response.Status.NO_CONTENT).build();
        } catch (Exception e) {
          throw new RuntimeException("Failed to delete S3 blob store: " + e.getMessage(), e);
        }
      }, virtualThreadExecutor);
      
      // Wait for the operation to complete
      return future.join();
    }
    catch (Exception e) {
      log.error("Failed to delete S3 blob store with empty name", e);
      throw new WebApplicationMessageException(BAD_REQUEST, e.getMessage());
    }
  }
}