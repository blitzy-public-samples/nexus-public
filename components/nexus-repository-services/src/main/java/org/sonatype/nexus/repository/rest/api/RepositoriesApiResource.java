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
package org.sonatype.nexus.repository.rest.api;

import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.thread.VirtualThreadExecutorService;

import org.apache.shiro.authz.annotation.RequiresAuthentication;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.NO_CONTENT;

/**
 * REST API for repository operations that leverage Virtual Threads for improved performance.
 * This implementation uses Java 21 Virtual Threads to handle HTTP requests asynchronously,
 * which significantly improves throughput for I/O-bound operations like repository management.
 * Error handling is enhanced with String Templates for more readable and maintainable messages.
 *
 * @since 3.20
 */
@Named
@Singleton
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class RepositoriesApiResource
    extends ComponentSupport
    implements Resource, RepositoriesApiResourceDoc
{
  private final AuthorizingRepositoryManager authorizingRepositoryManager;
  private final VirtualThreadExecutorService executorService;

  @Inject
  public RepositoriesApiResource(final AuthorizingRepositoryManager authorizingRepositoryManager) {
    this.authorizingRepositoryManager = checkNotNull(authorizingRepositoryManager);
    // Create a virtual thread executor service for handling HTTP requests
    // Using virtual threads improves performance for I/O-bound operations like repository operations
    this.executorService = new VirtualThreadExecutorService(Executors.newVirtualThreadPerTaskExecutor());
    log.debug("Initialized RepositoriesApiResource with Virtual Thread support");
  }

  @Override
  @DELETE
  @Path("/{repositoryName}")
  @RequiresAuthentication
  public Response deleteRepository(@PathParam("repositoryName") final String repositoryName) throws Exception {
    // Execute the delete operation using virtual threads for improved I/O performance
    boolean isDeleted = executorService.submit(() -> authorizingRepositoryManager.delete(repositoryName)).get();
    return Response.status(isDeleted ? NO_CONTENT : NOT_FOUND).build();
  }

  @POST
  @Path("/{repositoryName}/rebuild-index")
  @RequiresAuthentication
  public void rebuildIndex(@PathParam("repositoryName") final String repositoryName) {
    try {
      // Execute the rebuild index operation using virtual threads
      executorService.submit(() -> authorizingRepositoryManager.rebuildSearchIndex(repositoryName)).get();
    }
    catch (IncompatibleRepositoryException e) {
      log.debug(STR."Not a hosted or proxy repository '\{repositoryName}'", e);
      throw new WebApplicationMessageException(BAD_REQUEST, 
          STR."\"\{e.getMessage()}\"", APPLICATION_JSON);
    }
    catch (RepositoryNotFoundException e) {
      log.debug(STR."Repository not found '\{repositoryName}'", e);
      throw new WebApplicationMessageException(NOT_FOUND, 
          STR."\"\{e.getMessage()}\"", APPLICATION_JSON);
    }
    catch (Exception e) {
      log.error(STR."Error rebuilding index for repository '\{repositoryName}'", e);
      throw new WebApplicationMessageException(BAD_REQUEST, 
          STR."\"Error rebuilding index: \{e.getMessage()}\"", APPLICATION_JSON);
    }
  }

  @POST
  @Path("/{repositoryName}/invalidate-cache")
  @RequiresAuthentication
  public void invalidateCache(@PathParam("repositoryName") final String repositoryName) {
    try {
      // Execute the invalidate cache operation using virtual threads
      executorService.submit(() -> authorizingRepositoryManager.invalidateCache(repositoryName)).get();
    }
    catch (IncompatibleRepositoryException e) {
      log.debug(STR."Not a proxy nor group repository '\{repositoryName}'", e);
      throw new WebApplicationMessageException(BAD_REQUEST, 
          STR."\"\{e.getMessage()}\"", APPLICATION_JSON);
    }
    catch (RepositoryNotFoundException e) {
      log.debug(STR."Repository not found '\{repositoryName}'", e);
      throw new WebApplicationMessageException(NOT_FOUND, 
          STR."\"\{e.getMessage()}\"", APPLICATION_JSON);
    }
    catch (Exception e) {
      log.error(STR."Error invalidating cache for repository '\{repositoryName}'", e);
      throw new WebApplicationMessageException(BAD_REQUEST, 
          STR."\"Error invalidating cache: \{e.getMessage()}\"", APPLICATION_JSON);
    }
  }
}