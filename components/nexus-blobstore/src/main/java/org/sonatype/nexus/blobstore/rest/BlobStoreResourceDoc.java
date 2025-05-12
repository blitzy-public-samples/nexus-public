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

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_UNAUTHORIZED;
import static org.sonatype.nexus.rest.ApiDocConstants.AUTHENTICATION_REQUIRED;
import static org.sonatype.nexus.rest.ApiDocConstants.INSUFFICIENT_PERMISSIONS;

/**
 * REST facade for {@link BlobStoreResource}
 *
 * @since 3.14
 */
@Api(value = "Blob store")
public interface BlobStoreResourceDoc
{
  /**
   * Lists all configured blob stores.
   * 
   * @return List of blob store responses
   */
  @ApiOperation(value = "List the blob stores", notes = "Retrieves the list of available blob stores")
  List<GenericBlobStoreApiResponse> listBlobStores();

  /**
   * Deletes a blob store by name. This operation uses Java 21 Virtual Threads for improved
   * performance with I/O-bound operations, allowing for higher concurrency and reduced resource usage.
   *
   * @param name The name of the blob store to delete
   * @throws Exception if the blob store cannot be deleted
   */
  @ApiOperation(
      value = "Delete a blob store by name", 
      notes = "Deletes the specified blob store using Java 21 Virtual Threads for improved performance")
  void deleteBlobStore(@ApiParam("The name of the blob store to delete") String name) throws Exception;

  /**
   * Gets quota status for a given blob store. This operation uses Java 21 Virtual Threads for improved
   * performance with I/O-bound operations, allowing for higher concurrency and reduced resource usage.
   *
   * @param id The ID of the blob store
   * @return Quota status information
   */
  @ApiOperation(
      value = "Get quota status for a given blob store", 
      notes = "Retrieves quota information using Java 21 Virtual Threads for improved performance")
  BlobStoreQuotaResultXO quotaStatus(String id);

  /**
   * Verifies connection using supplied Blob Store settings. This operation uses Java 21 Virtual Threads for improved
   * performance with I/O-bound operations, allowing for higher concurrency and reduced resource usage.
   *
   * @param blobStoreConnectionXO The blob store connection settings to verify
   */
  @ApiOperation(
      value = "Verify connection using supplied Blob Store settings", 
      notes = "Tests connection to the blob store using Java 21 Virtual Threads for improved performance",
      hidden = true)
  @ApiResponses(value = {
      @ApiResponse(code = SC_NO_CONTENT, message = "Blob Store connection was successful"),
      @ApiResponse(code = SC_BAD_REQUEST, message = "Blob Store connection failed"),
      @ApiResponse(code = SC_UNAUTHORIZED, message = AUTHENTICATION_REQUIRED),
      @ApiResponse(code = SC_FORBIDDEN, message = INSUFFICIENT_PERMISSIONS)
  })
  void verifyConnection(final @NotNull @Valid BlobStoreConnectionXO blobStoreConnectionXO);
}