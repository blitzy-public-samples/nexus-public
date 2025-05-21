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
package org.sonatype.nexus.internal.status;

import java.util.SortedMap;

import javax.ws.rs.GET;
import javax.ws.rs.core.Response;

import com.codahale.metrics.health.HealthCheck.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for status operations
 *
 * @since 3.15
 */
@Tag(name = "Status")
public interface StatusResourceDoc
{
  /**
   * @return 200 if the server is available to serve read requests, 503 otherwise
   */
  @GET
  @Operation(summary = "Health check endpoint that validates server can respond to read requests",
      description = "Executes using Java 21 Virtual Threads for improved concurrency and performance")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Available to service requests"),
      @ApiResponse(responseCode = "503", description = "Unavailable to service requests")
  })
  Response isAvailable();

  /**
   * @return 200 if the server is available to serve read and write requests, 503 otherwise
   *
   * @since 3.16
   */
  @GET
  @Operation(summary = "Health check endpoint that validates server can respond to read and write requests",
      description = "Executes using Java 21 Virtual Threads for improved concurrency and performance")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Available to service requests"),
      @ApiResponse(responseCode = "503", description = "Unavailable to service requests")
  })
  Response isWritable();

  /**
   * @since 3.20
   */
  @GET
  @Operation(summary = "Health check endpoint that returns the results of the system status checks",
      description = "Executes using Java 21 Virtual Threads for improved concurrency and performance")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The system status check results", 
          content = @Content(schema = @Schema(implementation = Result.class, type = "object", additionalProperties = @Schema(implementation = Result.class))))
  })
  SortedMap<String, Result> getSystemStatusChecks();

}