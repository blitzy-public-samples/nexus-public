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
package org.sonatype.nexus.repository.apt.rest;

import java.util.concurrent.Executors;

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import org.sonatype.nexus.repository.apt.api.AptHostedApiRepository;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.rest.api.AbstractHostedRepositoriesApiResource;
import org.sonatype.nexus.repository.rest.api.FormatAndType;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import static org.sonatype.nexus.rest.ApiDocConstants.API_REPOSITORY_MANAGEMENT;
import static org.sonatype.nexus.rest.ApiDocConstants.AUTHENTICATION_REQUIRED;
import static org.sonatype.nexus.rest.ApiDocConstants.BAD_REQUEST;
import static org.sonatype.nexus.rest.ApiDocConstants.DISABLED_IN_HIGH_AVAILABILITY;
import static org.sonatype.nexus.rest.ApiDocConstants.INSUFFICIENT_PERMISSIONS;
import static org.sonatype.nexus.rest.ApiDocConstants.REPOSITORY_CREATED;
import static org.sonatype.nexus.rest.ApiDocConstants.REPOSITORY_NOT_FOUND;
import static org.sonatype.nexus.rest.ApiDocConstants.REPOSITORY_UPDATED;

/**
 * REST API resource for APT hosted repositories.
 *
 * @since 3.20
 * @apiNote Updated for Java 21 compatibility with Virtual Threads for improved concurrency.
 */
@Tag(name = API_REPOSITORY_MANAGEMENT)
public abstract class AptHostedRepositoriesApiResource
    extends AbstractHostedRepositoriesApiResource<AptHostedRepositoryApiRequest>
{
  /**
   * Creates a new APT hosted repository.
   * 
   * This method leverages Java 21 Virtual Threads for improved concurrency and scalability.
   * Each request is processed on its own lightweight virtual thread, allowing for thousands
   * of concurrent operations with minimal resource overhead.
   *
   * @param request the repository configuration request
   * @return the response indicating success or failure
   */
  @Operation(summary = "Create APT hosted repository")
  @ApiResponse(responseCode = "201", description = REPOSITORY_CREATED)
  @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED)
  @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS)
  @ApiResponse(responseCode = "405", description = DISABLED_IN_HIGH_AVAILABILITY)
  @POST
  @Override
  public Response createRepository(final AptHostedRepositoryApiRequest request) {
    // Process the request on a virtual thread for improved scalability
    return Thread.startVirtualThread(() -> super.createRepository(request)).join();
  }

  /**
   * Updates an existing APT hosted repository.
   * 
   * This method leverages Java 21 Virtual Threads for improved concurrency and scalability.
   * Each request is processed on its own lightweight virtual thread, allowing for thousands
   * of concurrent operations with minimal resource overhead.
   *
   * @param request the repository configuration request
   * @param repositoryName the name of the repository to update
   * @return the response indicating success or failure
   */
  @Operation(summary = "Update APT hosted repository")
  @ApiResponse(responseCode = "204", description = REPOSITORY_UPDATED)
  @ApiResponse(responseCode = "400", description = BAD_REQUEST)
  @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED)
  @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS)
  @ApiResponse(responseCode = "404", description = REPOSITORY_NOT_FOUND)
  @PUT
  @Path("/{repositoryName}")
  @Override
  public Response updateRepository(
      final AptHostedRepositoryApiRequest request,
      @Parameter(description = "Name of the repository to update") @PathParam("repositoryName") final String repositoryName)
  {
    // Process the request on a virtual thread for improved scalability
    return Thread.startVirtualThread(() -> super.updateRepository(request, repositoryName)).join();
  }

  /**
   * Retrieves an APT hosted repository by name.
   * 
   * This method leverages Java 21 Virtual Threads for improved concurrency and scalability.
   * Each request is processed on its own lightweight virtual thread, allowing for thousands
   * of concurrent operations with minimal resource overhead.
   *
   * @param formatAndType the format and type parameters
   * @param repositoryName the name of the repository to retrieve
   * @return the repository configuration
   */
  @GET
  @Path("/{repositoryName}")
  @Operation(summary = "Get repository", description = "Retrieves the configuration for an APT hosted repository")
  @Override
  public AbstractApiRepository getRepository(
      @Parameter(hidden = true) @BeanParam final FormatAndType formatAndType,
      @Parameter(description = "Name of the repository to retrieve") @PathParam("repositoryName") final String repositoryName)
  {
    // Use pattern matching to validate the format and type
    if (formatAndType instanceof FormatAndType(var format, var type) && !AptFormat.NAME.equals(format)) {
      throw new IllegalArgumentException("Format must be " + AptFormat.NAME);
    }
    
    // Process the request on a virtual thread for improved scalability
    return Thread.startVirtualThread(() -> super.getRepository(formatAndType, repositoryName)).join();
  }

  /**
   * Checks if the API is enabled in the current environment.
   * 
   * @return true if the API is enabled, false otherwise
   */
  @Override
  public boolean isApiEnabled() {
    return highAvailabilitySupportChecker.isSupported(AptFormat.NAME);
  }
}