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
package org.sonatype.nexus.repository.maven.rest;

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import org.sonatype.nexus.repository.maven.api.MavenHostedApiRepository;
import org.sonatype.nexus.repository.rest.api.AbstractHostedRepositoriesApiResource;
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
import static org.sonatype.nexus.rest.ApiDocConstants.INSUFFICIENT_PERMISSIONS;
import static org.sonatype.nexus.rest.ApiDocConstants.REPOSITORY_CREATED;
import static org.sonatype.nexus.rest.ApiDocConstants.REPOSITORY_UPDATED;

/**
 * REST resource for managing Maven hosted repositories
 *
 * @since 3.20
 */
@Tag(name = API_REPOSITORY_MANAGEMENT)
public abstract class MavenHostedRepositoriesApiResource
    extends AbstractHostedRepositoriesApiResource<MavenHostedRepositoryApiRequest>
{

  @Operation(summary = "Create Maven hosted repository")
  @ApiResponses(value = {
      @ApiResponse(responseCode = "201", description = REPOSITORY_CREATED),
      @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED),
      @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS)
  })
  @POST
  @Override
  public Response createRepository(final MavenHostedRepositoryApiRequest request) {
    return super.createRepository(request);
  }

  @Operation(summary = "Update Maven hosted repository")
  @ApiResponses(value = {
      @ApiResponse(responseCode = "204", description = REPOSITORY_UPDATED),
      @ApiResponse(responseCode = "400", description = BAD_REQUEST),
      @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED),
      @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS)
  })
  @PUT
  @Path("/{repositoryName}")
  @Override
  public Response updateRepository(
      final MavenHostedRepositoryApiRequest request,
      @Parameter(description = "Name of the repository to update") @PathParam("repositoryName") final String repositoryName)
  {
    return super.updateRepository(request, repositoryName);
  }

  @GET
  @Path("/{repositoryName}")
  @Operation(
      summary = "Get repository", 
      description = "Retrieves a Maven hosted repository by its name",
      responses = {
          @ApiResponse(
              responseCode = "200",
              description = "Repository found",
              content = @Content(schema = @Schema(implementation = MavenHostedApiRepository.class))
          ),
          @ApiResponse(responseCode = "401", description = AUTHENTICATION_REQUIRED),
          @ApiResponse(responseCode = "403", description = INSUFFICIENT_PERMISSIONS),
          @ApiResponse(responseCode = "404", description = "Repository not found")
      }
  )
  @Override
  public AbstractApiRepository getRepository(
      @Parameter(hidden = true) @BeanParam final FormatAndType formatAndType,
      @Parameter(description = "Name of the repository to retrieve") @PathParam("repositoryName") final String repositoryName)
  {
    return super.getRepository(formatAndType, repositoryName);
  }
}