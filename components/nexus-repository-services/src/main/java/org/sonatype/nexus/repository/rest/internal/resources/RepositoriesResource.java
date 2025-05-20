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
package org.sonatype.nexus.repository.rest.internal.resources;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;

// Import for String Templates
import static java.lang.StringTemplate.STR;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

import org.sonatype.nexus.repository.rest.api.RepositoryManagerRESTAdapter;
import org.sonatype.nexus.repository.rest.api.RepositoryXO;
import org.sonatype.nexus.repository.rest.internal.resources.doc.RepositoriesResourceDoc;
import org.sonatype.nexus.rest.Resource;

import static com.google.common.base.Preconditions.checkNotNull;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;

/**
 * @since 3.9
 */
@Named
@Singleton
@Path(RepositoriesResource.RESOURCE_URI)
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class RepositoriesResource
    implements Resource, RepositoriesResourceDoc
{
  public static final String RESOURCE_URI = V1_API_PREFIX + "/repositories";

  private final RepositoryManagerRESTAdapter repositoryManagerRESTAdapter;
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public RepositoriesResource(final RepositoryManagerRESTAdapter repositoryManagerRESTAdapter) {
    this.repositoryManagerRESTAdapter = checkNotNull(repositoryManagerRESTAdapter);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @GET
  @Override
  public List<RepositoryXO> getRepositories() {
    // Using virtual threads for I/O-bound repository retrieval operations
    CompletableFuture<List<RepositoryXO>> future = CompletableFuture.supplyAsync(
        () -> repositoryManagerRESTAdapter.getRepositories(),
        virtualThreadExecutor
    );
    return future.join();
  }

  @GET
  @Override
  @Path("/{repositoryName}")
  public RepositoryXO getRepository(@PathParam("repositoryName") final String repositoryName) {
    // Using virtual threads for I/O-bound repository retrieval operations
    CompletableFuture<RepositoryXO> future = CompletableFuture.supplyAsync(
        () -> {
          var repository = repositoryManagerRESTAdapter.getReadableRepository(repositoryName);
          var size = repositoryManagerRESTAdapter.getRepositorySize(repositoryName).orElse(null);
          
          // Using pattern matching for repository type handling
          return switch (repository.getType().getValue()) {
            case "hosted" -> RepositoryXO.fromRepository(repository, size);
            case "proxy" -> RepositoryXO.fromRepository(repository, size);
            case "group" -> RepositoryXO.fromRepository(repository, size);
            default -> {
              // Using String Templates for error message formatting (for logging purposes)
              String message = STR."Unexpected repository type: \{repository.getType().getValue()}";
              System.out.println(message); // In a real implementation, this would use a logger
              yield RepositoryXO.fromRepository(repository, size);
            }
          };
        },
        virtualThreadExecutor
    );
    return future.join();
  }
}
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
package org.sonatype.nexus.repository.rest.internal.resources;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;

// Import for String Templates
import static java.lang.StringTemplate.STR;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

import org.sonatype.nexus.repository.rest.api.RepositoryManagerRESTAdapter;
import org.sonatype.nexus.repository.rest.api.RepositoryXO;
import org.sonatype.nexus.repository.rest.internal.resources.doc.RepositoriesResourceDoc;
import org.sonatype.nexus.rest.Resource;

import static com.google.common.base.Preconditions.checkNotNull;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;

/**
 * @since 3.9
 */
@Named
@Singleton
@Path(RepositoriesResource.RESOURCE_URI)
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class RepositoriesResource
    implements Resource, RepositoriesResourceDoc
{
  public static final String RESOURCE_URI = V1_API_PREFIX + "/repositories";

  private final RepositoryManagerRESTAdapter repositoryManagerRESTAdapter;
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public RepositoriesResource(final RepositoryManagerRESTAdapter repositoryManagerRESTAdapter) {
    this.repositoryManagerRESTAdapter = checkNotNull(repositoryManagerRESTAdapter);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @GET
  @Override
  public List<RepositoryXO> getRepositories() {
    // Using virtual threads for I/O-bound repository retrieval operations
    CompletableFuture<List<RepositoryXO>> future = CompletableFuture.supplyAsync(
        () -> repositoryManagerRESTAdapter.getRepositories(),
        virtualThreadExecutor
    );
    return future.join();
  }

  @GET
  @Override
  @Path("/{repositoryName}")
  public RepositoryXO getRepository(@PathParam("repositoryName") final String repositoryName) {
    // Using virtual threads for I/O-bound repository retrieval operations
    CompletableFuture<RepositoryXO> future = CompletableFuture.supplyAsync(
        () -> {
          var repository = repositoryManagerRESTAdapter.getReadableRepository(repositoryName);
          var size = repositoryManagerRESTAdapter.getRepositorySize(repositoryName).orElse(null);
          
          // Using pattern matching for repository type handling
          return switch (repository.getType().getValue()) {
            case "hosted" -> RepositoryXO.fromRepository(repository, size);
            case "proxy" -> RepositoryXO.fromRepository(repository, size);
            case "group" -> RepositoryXO.fromRepository(repository, size);
            default -> {
              // Using String Templates for error message formatting (for logging purposes)
              String message = STR."Unexpected repository type: \{repository.getType().getValue()}";
              System.out.println(message); // In a real implementation, this would use a logger
              yield RepositoryXO.fromRepository(repository, size);
            }
          };
        },
        virtualThreadExecutor
    );
    return future.join();
  }
}