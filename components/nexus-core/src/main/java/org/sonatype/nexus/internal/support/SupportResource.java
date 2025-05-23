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
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
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
   * Timeout for support ZIP generation operations (in seconds)
   */
  private static final long ZIP_GENERATION_TIMEOUT_SECONDS = 300; // 5 minutes

  @Inject
  private SupportZipGenerator supportZipGenerator;

  /**
   * Creates and downloads a support zip using Java 21 Virtual Threads for improved performance.
   * 
   * This implementation leverages Virtual Threads to handle the I/O-bound ZIP generation process,
   * allowing for better resource utilization and increased concurrency without the overhead of
   * traditional platform threads.
   *
   * @param request The support ZIP generation request parameters
   * @return HTTP response with the generated ZIP file as a streaming attachment
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

    // Create a StreamingOutput that uses Virtual Threads for ZIP generation
    StreamingOutput entity = new StreamingOutput() {
      @Override
      public void write(final OutputStream output) throws IOException, WebApplicationException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
          // Submit the ZIP generation task to a virtual thread
          CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
              log.debug("Starting support ZIP generation using virtual thread: {}", Thread.currentThread());
              supportZipGenerator.generate(request, "support", output);
              log.debug("Completed support ZIP generation");
            } 
            catch (IOException e) {
              log.error("Error generating support ZIP: {}", e.getMessage(), e);
              throw new RuntimeException(STR."Failed to generate support ZIP: \{e.getMessage()}", e);
            }
          }, executor);
          
          // Wait for the ZIP generation to complete with a timeout
          try {
            future.orTimeout(ZIP_GENERATION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).join();
          } 
          catch (Exception e) {
            log.error("Support ZIP generation failed or timed out: {}", e.getMessage(), e);
            throw new WebApplicationException("Support ZIP generation failed: " + e.getMessage(), 
                Response.Status.INTERNAL_SERVER_ERROR);
          }
        }
      }
    };
    
    return Response.ok(entity)
        .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
        .build();
  }

  /**
   * Creates a support zip and returns metadata about it using Java 21 Virtual Threads.
   * 
   * This implementation uses Virtual Threads to improve performance for the ZIP generation process,
   * which is primarily I/O-bound. Virtual Threads provide better resource utilization and increased
   * concurrency without the overhead of traditional platform threads.
   *
   * @param request The support ZIP generation request parameters
   * @return Metadata about the generated ZIP file
   */
  @RequiresAuthentication
  @RequiresPermissions("nexus:atlas:create")
  @ApiOperation("Creates a support zip and returns the path")
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @POST
  @Path("/supportzippath")
  public SupportZipXO supportzippath(final SupportZipGeneratorRequest request) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the ZIP generation task to a virtual thread and wait for the result
      CompletableFuture<Result> future = CompletableFuture.supplyAsync(() -> {
        log.debug("Starting support ZIP generation using virtual thread: {}", Thread.currentThread());
        Result result = supportZipGenerator.generate(request);
        log.debug("Completed support ZIP generation: {}", result.getFilename());
        return result;
      }, executor);
      
      // Wait for the ZIP generation to complete with a timeout
      try {
        Result result = future.orTimeout(ZIP_GENERATION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).join();
        return new SupportZipXO(result.getLocalPath(), result.getFilename(), result.getSize(), result.isTruncated());
      } 
      catch (Exception e) {
        log.error("Support ZIP generation failed or timed out: {}", e.getMessage(), e);
        throw new WebApplicationException(STR."Support ZIP generation failed: \{e.getMessage()}", 
            Response.Status.INTERNAL_SERVER_ERROR);
      }
    }
  }
}