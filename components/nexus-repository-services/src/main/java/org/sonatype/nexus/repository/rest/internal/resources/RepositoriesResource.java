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
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.rest.api.RepositoryManagerRESTAdapter;
import org.sonatype.nexus.repository.rest.api.RepositoryXO;
import org.sonatype.nexus.repository.rest.internal.resources.doc.RepositoriesResourceDoc;
import org.sonatype.nexus.rest.Resource;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
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

  @Inject
  public RepositoriesResource(final RepositoryManagerRESTAdapter repositoryManagerRESTAdapter) {
    this.repositoryManagerRESTAdapter = checkNotNull(repositoryManagerRESTAdapter);
  }

  @GET
  @Override
  public List<RepositoryXO> getRepositories() {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> repositoryManagerRESTAdapter.getRepositories()).join();
    }
  }

  @GET
  @Override
  @Path("/{repositoryName}")
  public RepositoryXO getRepository(@PathParam("repositoryName") final String repositoryName) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> {
        Repository repository = repositoryManagerRESTAdapter.getReadableRepository(repositoryName);
        var size = repositoryManagerRESTAdapter.getRepositorySize(repositoryName).orElse(null);
        
        return switch (repository) {
          case Repository repo when repo != null -> RepositoryXO.fromRepository(repo, size);
          case null -> throw new IllegalArgumentException(STR."Repository \{repositoryName} not found");
        };
      }).join();
    }
  }
}