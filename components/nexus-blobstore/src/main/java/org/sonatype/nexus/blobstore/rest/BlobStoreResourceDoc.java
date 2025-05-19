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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * This interface defines the API operations for blob store management.
 * All operations leverage Java 21 Virtual Threads for improved concurrency and performance,
 * allowing for more efficient handling of I/O-bound operations without consuming platform threads.
 *
 * @since 3.14
 */
@Tag(name = "Blob store")
public interface BlobStoreResourceDoc
{
  /**
   * List all configured blob stores.
   * 
   * This operation is executed on a Virtual Thread, providing improved scalability
   * for concurrent API requests without blocking platform threads.
   *
   * @return List of blob store information
   */
  @Operation(summary = "List the blob stores", 
      description = "Returns a list of all configured blob stores. Executes on Virtual Threads for improved performance.")
  @ApiResponse(responseCode = "200", description = "List of blob stores", 
      content = @Content(mediaType = "application/json", 
      schema = @Schema(implementation = GenericBlobStoreApiResponse.class)))
  List<GenericBlobStoreApiResponse> listBlobStores();

  /**
   * Delete a blob store by name.
   * 
   * This operation is executed on a Virtual Thread, providing improved scalability
   * for concurrent API requests without blocking platform threads.
   *
   * @param name The name of the blob store to delete
   * @throws Exception if deletion fails
   */
  @Operation(summary = "Delete a blob store by name", 
      description = "Deletes the specified blob store. Executes on Virtual Threads for improved performance.")
  @ApiResponse(responseCode = "204", description = "Blob store was successfully deleted")
  @ApiResponse(responseCode = "400", description = "Blob store is in use or other constraint violation")
  @ApiResponse(responseCode = "404", description = "Blob store not found")
  void deleteBlobStore(@Parameter(description = "The name of the blob store to delete") String name) throws Exception;

  /**
   * Get quota status for a given blob store.
   * 
   * This operation is executed on a Virtual Thread, providing improved scalability
   * for concurrent API requests without blocking platform threads.
   *
   * @param id The blob store id
   * @return Quota status information
   */
  @Operation(summary = "Get quota status for a given blob store", 
      description = "Returns quota status information for the specified blob store. Executes on Virtual Threads for improved performance.")
  @ApiResponse(responseCode = "200", description = "Quota status information", 
      content = @Content(mediaType = "application/json", 
      schema = @Schema(implementation = BlobStoreQuotaResultXO.class)))
  @ApiResponse(responseCode = "404", description = "Blob store not found")
  BlobStoreQuotaResultXO quotaStatus(String id);

  /**
   * Verify connection using supplied Blob Store settings.
   * 
   * This operation is executed on a Virtual Thread, providing improved scalability
   * for concurrent API requests without blocking platform threads, which is especially
   * beneficial for network operations like connection verification.
   *
   * @param blobStoreConnectionXO The connection settings to verify
   */
  @Operation(summary = "Verify connection using supplied Blob Store settings", 
      description = "Tests the connection to the blob store using the provided settings. Executes on Virtual Threads for improved performance.", 
      hidden = true)
  @ApiResponses(value = {
      @ApiResponse(responseCode = "204", description = "Blob Store connection was successful"),
      @ApiResponse(responseCode = "400", description = "Blob Store connection failed"),
      @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED),
      @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS)
  })
  void verifyConnection(final @NotNull @Valid BlobStoreConnectionXO blobStoreConnectionXO);
}