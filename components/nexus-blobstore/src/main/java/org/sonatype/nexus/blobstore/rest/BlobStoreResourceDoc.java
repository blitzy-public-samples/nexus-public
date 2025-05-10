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

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_UNAUTHORIZED;
import static org.sonatype.nexus.rest.ApiDocConstants.AUTHENTICATION_REQUIRED;
import static org.sonatype.nexus.rest.ApiDocConstants.INSUFFICIENT_PERMISSIONS;

/**
 * REST facade for {@link BlobStoreResource}
 *
 * This interface defines the REST API operations for BlobStore management.
 * Implementation leverages Java 21 Virtual Threads for improved performance
 * on I/O-bound operations such as blob store connections and quota status checks.
 *
 * @since 3.14
 */
@Tag(name = "Blob store")
public interface BlobStoreResourceDoc
{
  /**
   * List all configured blob stores.
   * 
   * This operation benefits from Virtual Threads to efficiently handle concurrent requests
   * without blocking platform threads, especially when many blob stores need to be listed.
   *
   * @return List of blob store information
   */
  @Operation(summary = "List the blob stores")
  List<GenericBlobStoreApiResponse> listBlobStores();

  /**
   * Delete a blob store by name.
   * 
   * This operation uses Virtual Threads to handle the potentially long-running I/O operations
   * involved in blob store deletion without blocking platform threads.
   *
   * @param name The name of the blob store to delete
   * @throws Exception if deletion fails
   */
  @Operation(summary = "Delete a blob store by name")
  void deleteBlobStore(@Parameter(description = "The name of the blob store to delete") String name) throws Exception;

  /**
   * Get quota status for a given blob store.
   * 
   * This operation uses Virtual Threads to efficiently retrieve quota information
   * without blocking platform threads, especially important for remote blob stores like S3.
   *
   * @param id The blob store identifier
   * @return Quota status information
   */
  @Operation(summary = "Get quota status for a given blob store")
  BlobStoreQuotaResultXO quotaStatus(String id);

  /**
   * Verify connection using supplied Blob Store settings.
   * 
   * This operation leverages Virtual Threads to perform connection testing without blocking
   * platform threads, allowing for efficient handling of multiple concurrent connection tests
   * and improved responsiveness under load.
   *
   * @param blobStoreConnectionXO The connection settings to verify
   */
  @Operation(summary = "Verify connection using supplied Blob Store settings", hidden = true)
  @ApiResponses(value = {
      @ApiResponse(responseCode = "204", description = "Blob Store connection was successful"),
      @ApiResponse(responseCode = "400", description = "Blob Store connection failed"),
      @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED),
      @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS)
  })
  void verifyConnection(final @NotNull @Valid BlobStoreConnectionXO blobStoreConnectionXO);
}
