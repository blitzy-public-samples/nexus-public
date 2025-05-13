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
 * <p>This class has been updated for Java 21 compatibility with the following enhancements:</p>
 * <ul>
 *   <li>Virtual Threads support for all I/O-bound operations to improve throughput and scalability</li>
 *   <li>Pattern matching for instanceof to simplify type checking and conditional logic</li>
 *   <li>String templates for more readable error messages</li>
 *   <li>Updated to use Jakarta EE 9+ APIs instead of deprecated Java EE APIs</li>
 *   <li>Updated to use commons-lang3 instead of deprecated commons-lang</li>
 * </ul>
 *
 * <p>These changes allow the S3 blob store API to handle more concurrent requests with lower resource
 * consumption, particularly for operations that interact with AWS S3 services.</p>
 *
 * @since 3.20
 */
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class S3BlobStoreApiResource
    extends ComponentSupport
    implements Resource, S3BlobStoreApiResourceDoc
{
  private final S3BlobStoreApiUpdateValidation s3BlobStoreApiUpdateValidation;

  private final BlobStoreManager blobStoreManager;

  private SecretsFactory secretsFactory;

  public S3BlobStoreApiResource(
      final BlobStoreManager blobStoreManager,
      final S3BlobStoreApiUpdateValidation validation,
      final SecretsFactory secretsFactory)
  {
    this.blobStoreManager = blobStoreManager;
    this.s3BlobStoreApiUpdateValidation = validation;
    this.secretsFactory = checkNotNull(secretsFactory);
  }

  /**
   * Creates a new S3 blob store using the provided configuration.
   * Uses Virtual Threads for I/O-bound operations to improve throughput.
   *
   * @param request The S3 blob store configuration model
   * @return Response with created status on success
   */
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
      var future = Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        blobStoreManager.create(blobStoreConfiguration);
        return status(CREATED).build();
      });
      
      return future.get(); // Wait for the virtual thread to complete
    }
    catch (Exception e) {
      log.error("Failed to create S3 blob store", e);
      throw new WebApplicationMessageException(BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Updates an existing S3 blob store with the provided configuration.
   * Uses Virtual Threads for I/O-bound operations to improve throughput.
   *
   * @param request The S3 blob store configuration model
   * @param blobStoreName The name of the blob store to update
   * @throws Exception if an error occurs during update
   */
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

    // Use pattern matching for type checking (Java 21 feature)
    if (request.getBucketConfiguration() instanceof var bucketConfig && 
        bucketConfig != null && 
        bucketConfig.getBucketSecurity() instanceof var security && 
        security != null && 
        PasswordPlaceholder.is(security.getSecretAccessKey())) {
      
      // Did not update the password, just use the password we already have
      BlobStore currentS3Blobstore = blobStoreManager.get(blobStoreName);
      var attributes = currentS3Blobstore.getBlobStoreConfiguration().getAttributes();
      
      if (attributes.get(TYPE.toLowerCase()) instanceof var typeAttrs && typeAttrs != null) {
        String secretId = typeAttrs.get(SECRET_ACCESS_KEY_KEY).toString();
        String decryptedSecretKey = new String(secretsFactory.from(secretId).decrypt());
        security.setSecretAccessKey(decryptedSecretKey);
      }
    }

    try {
      final BlobStoreConfiguration blobStoreConfiguration = map(blobStoreManager.newConfiguration(), request);
      
      // Use Virtual Thread for I/O-bound operation
      Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        try {
          blobStoreManager.update(blobStoreConfiguration);
        } catch (Exception e) {
          log.error("Error updating S3 blob store in virtual thread", e);
          throw new RuntimeException(e);
        }
      }).get(); // Wait for the virtual thread to complete
    }
    catch (Exception e) {
      log.error("Failed to update S3 blob store", e);
      throw new WebApplicationMessageException(INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Checks if the password in the request is untouched (placeholder).
   * This method is now deprecated as the logic has been moved to the updateBlobStore method
   * using Java 21 pattern matching for instanceof.
   *
   * @param request The S3 blob store configuration model
   * @return true if the password is untouched (placeholder), false otherwise
   * @deprecated Use pattern matching in updateBlobStore method instead
   */
  @Deprecated(since = "Java 21 update")
  private boolean isPasswordUntouched(final S3BlobStoreApiModel request) {
    return request.getBucketConfiguration() != null && request.getBucketConfiguration().getBucketSecurity() != null &&
        PasswordPlaceholder.is(request.getBucketConfiguration().getBucketSecurity().getSecretAccessKey());
  }

  /**
   * Retrieves an S3 blob store configuration by name.
   * Uses Virtual Threads for I/O-bound operations to improve throughput.
   *
   * @param blobStoreName The name of the blob store to retrieve
   * @return The S3 blob store configuration model
   */
  @GET
  @Override
  @RequiresAuthentication
  @Path("/s3/{name}")
  @RequiresPermissions("nexus:blobstores:read")
  public S3BlobStoreApiModel getBlobStore(@PathParam("name") final String blobStoreName) {
    try {
      // Use Virtual Thread for I/O-bound operation
      var future = Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
          fetchBlobStoreConfiguration(blobStoreName)
              .orElseThrow(() -> BlobStoreResourceUtil.createBlobStoreNotFoundException(S3BlobStore.TYPE, blobStoreName)));
      
      return future.get(); // Wait for the virtual thread to complete
    } catch (Exception e) {
      log.error("Failed to get S3 blob store configuration", e);
      if (e.getCause() instanceof WebApplicationMessageException) {
        throw (WebApplicationMessageException) e.getCause();
      }
      throw new WebApplicationMessageException(INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Fetches the S3 blob store configuration by name and processes it.
   * Uses Java 21 pattern matching for more concise code.
   *
   * @param blobStoreName The name of the blob store to fetch
   * @return Optional containing the S3 blob store configuration model if found
   */
  private Optional<S3BlobStoreApiModel> fetchBlobStoreConfiguration(final String blobStoreName) {
    Optional<S3BlobStoreApiModel> result = ofNullable(blobStoreManager.get(blobStoreName))
        .map(BlobStore::getBlobStoreConfiguration)
        .map(this::ensureBlobStoreTypeIsS3)
        .map(S3BlobStoreApiConfigurationMapper::map);
    
    // Use pattern matching for more concise code (Java 21 feature)
    if (result.isPresent()) {
      var model = result.get();
      if (model.getBucketConfiguration() instanceof var bucketConfig && 
          bucketConfig != null && 
          bucketConfig.getBucketSecurity() instanceof var security && 
          security != null && 
          security.getAccessKeyId() != null && 
          isNotEmpty(security.getAccessKeyId())) {
        
        security.setSecretAccessKey(PasswordPlaceholder.get());
        
        if (security.getSessionToken() != null && isNotEmpty(security.getSessionToken())) {
          security.setSessionToken(PasswordPlaceholder.get());
        }
      }
    }
    
    return result;
  }

  /**
   * Checks if authentication data is present in the S3 blob store configuration model.
   * This method is now deprecated as the logic has been moved to the fetchBlobStoreConfiguration method
   * using Java 21 pattern matching for instanceof.
   *
   * @param s3BlobStoreApiModel The S3 blob store configuration model
   * @return true if authentication data is present, false otherwise
   * @deprecated Use pattern matching in fetchBlobStoreConfiguration method instead
   */
  @Deprecated(since = "Java 21 update")
  private boolean isAuthenticationDataPresent(final S3BlobStoreApiModel s3BlobStoreApiModel) {
    return s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity() != null &&
        s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getAccessKeyId() != null &&
        isNotEmpty(s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getAccessKeyId());
  }

  /**
   * Checks if a session token is present in the S3 blob store configuration model.
   * This method is now deprecated as the logic has been moved to the fetchBlobStoreConfiguration method
   * using Java 21 pattern matching for instanceof.
   *
   * @param s3BlobStoreApiModel The S3 blob store configuration model
   * @return true if a session token is present, false otherwise
   * @deprecated Use pattern matching in fetchBlobStoreConfiguration method instead
   */
  @Deprecated(since = "Java 21 update")
  private boolean hasSessionToken(final S3BlobStoreApiModel s3BlobStoreApiModel) {
    return s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getSessionToken() != null &&
        isNotEmpty(s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getSessionToken());
  }

  /**
   * Ensures that the blob store configuration is of type S3.
   * Uses Java 21 string templates for improved error message formatting.
   *
   * @param configuration The blob store configuration to check
   * @return The blob store configuration if it is of type S3
   * @throws WebApplicationMessageException if the blob store is not of type S3
   */
  private BlobStoreConfiguration ensureBlobStoreTypeIsS3(final BlobStoreConfiguration configuration) {
    final String type = configuration.getType();
    if (!equalsIgnoreCase(TYPE, type)) {
      // Using Java 21 string template for improved error message formatting
      String errorMessage = STR."""
          The blob store '{configuration.getName()}' is not an S3 blob store. 
          Expected type: {TYPE}, actual type: {type}
          """;
      throw new WebApplicationMessageException(BAD_REQUEST, errorMessage, APPLICATION_JSON);
    }
    return configuration;
  }

  /**
   * Deletes a blob store with an empty name.
   * Uses Virtual Threads for I/O-bound operations to improve throughput.
   *
   * @return Response with no content status on success
   */
  @DELETE
  @RequiresAuthentication
  @Path("/s3")
  @RequiresPermissions("nexus:blobstores:delete")
  @ApiOperation(value = "Delete a blob store with an empty name", hidden = true)
  public Response deleteBlobStoreWithEmptyName() {
    String blobStoreName = "";
    try {
      // Use Virtual Thread for I/O-bound operation
      var future = Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        BlobStore blobStore = blobStoreManager.get(blobStoreName);
        if (blobStore == null) {
          return Response.status(Response.Status.NOT_FOUND)
              .entity("Blob store not found")
              .build();
        }
        blobStoreManager.delete(blobStoreName);
        return Response.status(Response.Status.NO_CONTENT).build();
      });
      
      return future.get(); // Wait for the virtual thread to complete
    }
    catch (Exception e) {
      log.error("Failed to delete S3 blob store with empty name", e);
      if (e.getCause() instanceof WebApplicationMessageException) {
        throw (WebApplicationMessageException) e.getCause();
      }
      throw new WebApplicationMessageException(BAD_REQUEST, e.getMessage());
    }
  }
}