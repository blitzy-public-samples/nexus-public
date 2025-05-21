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
package org.sonatype.nexus.internal.support;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.supportzip.SupportZipGenerator;
import org.sonatype.nexus.common.log.SupportZipGeneratorRequest;
import org.sonatype.nexus.supportzip.SupportZipGenerator.Result;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;

/**
 * Resource for support API.
 *
 * @since 3.13
 */
@Named
@Singleton
@Path(SupportResource.RESOURCE_URI)
@Api("Support")
public class SupportResource
    extends ComponentSupport
    implements Resource
{
  public static final String RESOURCE_URI = "/v1/support";
  
  /**
   * Timeout for support ZIP generation operations (5 minutes)
   */
  private static final Duration ZIP_GENERATION_TIMEOUT = Duration.ofMinutes(5);

  @Inject
  private SupportZipGenerator supportZipGenerator;
  
  /**
   * Virtual thread executor for handling support ZIP generation tasks.
   * Using virtual threads improves performance for I/O-bound operations like ZIP creation.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Creates and downloads a support zip using Java 21 Virtual Threads for improved performance.
   * 
   * @param request The support zip generator request
   * @return HTTP response with the support zip as a streaming attachment
   */
  @RequiresAuthentication
  @RequiresPermissions("nexus:atlas:create")
  @ApiOperation("Creates and downloads a support zip")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_OCTET_STREAM)
  @POST
  @Path("/supportzip")
  public Response supportzip(final SupportZipGeneratorRequest request) {
    String name = "support-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-1.zip";

    // Create a streaming output that uses virtual threads for ZIP generation
    StreamingOutput entity = output -> {
      try {
        // Submit the ZIP generation task to the virtual thread executor
        CompletableFuture<Void> future = CompletableFuture.runAsync(
            () -> {
              try {
                supportZipGenerator.generate(request, "support", output);
              } 
              catch (IOException e) {
                log.error("Error generating support ZIP: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to generate support ZIP", e);
              }
            },
            virtualThreadExecutor
        );
        
        // Wait for completion with timeout
        future.orTimeout(ZIP_GENERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
      } 
      catch (Exception e) {
        log.error("Support ZIP generation failed or timed out: {}", e.getMessage(), e);
        if (e.getCause() instanceof IOException) {
          throw (IOException) e.getCause();
        }
        throw new IOException("Support ZIP generation failed: " + e.getMessage(), e);
      }
    };
    
    return Response.ok(entity)
        .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
        .header("Content-Type", APPLICATION_OCTET_STREAM)
        .build();
  }

  /**
   * Creates a support zip and returns the path, using Java 21 Virtual Threads for improved performance.
   * 
   * @param request The support zip generator request
   * @return Support zip metadata including path, filename, size and truncation status
   */
  @RequiresAuthentication
  @RequiresPermissions("nexus:atlas:create")
  @ApiOperation("Creates a support zip and returns the path")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @POST
  @Path("/supportzippath")
  public SupportZipXO supportzippath(final SupportZipGeneratorRequest request) {
    try {
      // Submit the ZIP generation task to the virtual thread executor
      CompletableFuture<Result> future = CompletableFuture.supplyAsync(
          () -> {
            try {
              return supportZipGenerator.generate(request);
            } 
            catch (IOException e) {
              log.error("Error generating support ZIP: {}", e.getMessage(), e);
              throw new RuntimeException("Failed to generate support ZIP", e);
            }
          },
          virtualThreadExecutor
      );
      
      // Wait for completion with timeout
      Result result = future.orTimeout(ZIP_GENERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
      return new SupportZipXO(result.getLocalPath(), result.getFilename(), result.getSize(), result.isTruncated());
    } 
    catch (Exception e) {
      log.error("Support ZIP generation failed or timed out: {}", e.getMessage(), e);
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new RuntimeException("Failed to generate support ZIP: " + cause.getMessage(), cause);
    }
  }
}