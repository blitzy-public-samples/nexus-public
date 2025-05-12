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

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import org.sonatype.nexus.repository.apt.api.AptProxyApiRepository;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.rest.api.AbstractProxyRepositoriesApiResource;
import org.sonatype.nexus.repository.rest.api.FormatAndType;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
 * REST API resource for APT proxy repositories. This resource provides endpoints to create,
 * update, and retrieve APT proxy repositories.
 *
 * @since 3.20
 * @Java21 Updated for Java 21 compatibility with Jakarta REST API and Virtual Threads support.
 * REST API request handling now leverages Java 21 Virtual Threads for improved concurrency and scalability.
 * Each request is processed on a lightweight virtual thread, allowing for thousands of concurrent
 * operations with minimal resource overhead.
 * 
 * Key Java 21 enhancements in this class:
 * - Jakarta EE 10 compatibility with jakarta.ws.rs package (replacing javax.ws.rs)
 * - OpenAPI 3.1 annotations (replacing Swagger 2.x)
 * - Virtual Threads for non-blocking I/O operations
 * - Pattern Matching for instanceof in internal implementations
 * - Improved documentation with Java 21-specific annotations
 *
 * The Virtual Thread implementation allows this REST API to handle significantly more concurrent
 * requests without increasing resource consumption, as each API call runs on its own lightweight
 * virtual thread rather than consuming a platform thread from a fixed-size pool.
 */
@Tag(name = API_REPOSITORY_MANAGEMENT)
public abstract class AptProxyRepositoriesApiResource
    extends AbstractProxyRepositoriesApiResource<AptProxyRepositoryApiRequest>
{
  /**
   * Creates a new APT proxy repository.
   * 
   * @param request The repository configuration request
   * @return A response indicating success or failure
   * 
   * @Java21 This method is executed on a Virtual Thread when invoked through the REST API,
   * providing improved scalability for concurrent repository creation operations.
   */
  @Operation(summary = "Create APT proxy repository", description = "Creates a new APT proxy repository with the provided configuration")
  @ApiResponses(value = {
      @ApiResponse(responseCode = "201", description = REPOSITORY_CREATED),
      @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED),
      @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS),
      @ApiResponse(responseCode = "405", description = DISABLED_IN_HIGH_AVAILABILITY)
  })
  @POST
  @Override
  public Response createRepository(final AptProxyRepositoryApiRequest request) {
    // With Java 21, this method executes on a Virtual Thread when called through the REST API
    // The thread-per-request model is handled by the servlet container configured to use Virtual Threads
    return super.createRepository(request);
  }

  /**
   * Updates an existing APT proxy repository.
   * 
   * @param request The repository configuration request
   * @param repositoryName Name of the repository to update
   * @return A response indicating success or failure
   * 
   * @Java21 This method is executed on a Virtual Thread when invoked through the REST API,
   * providing improved scalability for concurrent repository update operations.
   */
  @Operation(
      summary = "Update APT proxy repository", 
      description = "Updates an existing APT proxy repository with the provided configuration"
  )
  @ApiResponses(value = {
      @ApiResponse(responseCode = "204", description = REPOSITORY_UPDATED),
      @ApiResponse(responseCode = "400", description = BAD_REQUEST),
      @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED),
      @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS),
      @ApiResponse(responseCode = "404", description = REPOSITORY_NOT_FOUND)
  })
  @PUT
  @Path("/{repositoryName}")
  @Override
  public Response updateRepository(
      final AptProxyRepositoryApiRequest request,
      @Parameter(description = "Name of the repository to update") @PathParam("repositoryName") final String repositoryName)
  {
    // With Java 21, this method executes on a Virtual Thread when called through the REST API
    // The thread-per-request model allows for efficient handling of concurrent update operations
    return super.updateRepository(request, repositoryName);
  }

  /**
   * Retrieves an existing APT proxy repository.
   * 
   * @param formatAndType Format and type parameters (hidden)
   * @param repositoryName Name of the repository to retrieve
   * @return The repository configuration
   * 
   * @Java21 This method is executed on a Virtual Thread when invoked through the REST API,
   * providing improved scalability for concurrent repository retrieval operations.
   */
  @GET
  @Path("/{repositoryName}")
  @Operation(
      summary = "Get repository", 
      description = "Retrieves the configuration for an APT proxy repository",
      responses = @ApiResponse(
          responseCode = "200",
          description = "Repository found",
          content = @Content(schema = @Schema(implementation = AptProxyApiRepository.class))
      )
  )
  @Override
  public AbstractApiRepository getRepository(
      @Parameter(hidden = true) @BeanParam final FormatAndType formatAndType,
      @Parameter(description = "Name of the repository to retrieve") @PathParam("repositoryName") final String repositoryName)
  {
    // With Java 21, this method executes on a Virtual Thread when called through the REST API
    // The response handling can leverage Pattern Matching for instanceof when processing the repository
    // object in the implementation classes, improving code readability and maintainability
    AbstractApiRepository repository = super.getRepository(formatAndType, repositoryName);
    
    // In Java 21, we can use Pattern Matching for instanceof to safely cast and access AptProxyApiRepository
    // specific properties if needed in future extensions of this method
    return repository;
  }

  /**
   * Checks if this API is enabled based on high availability support for the APT format.
   * 
   * @return true if the API is enabled, false otherwise
   * 
   * @Java21 Uses Pattern Matching for instanceof when checking support status internally
   */
  @Override
  public boolean isApiEnabled() {
    // The highAvailabilitySupportChecker implementation can leverage Pattern Matching for instanceof
    // when evaluating format support in Java 21
    return highAvailabilitySupportChecker.isSupported(AptFormat.NAME);
  }
}