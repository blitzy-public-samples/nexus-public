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
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.rest.api.AuthorizingRepositoryManager;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.validation.Validate;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import org.apache.shiro.authz.annotation.RequiresAuthentication;

import static com.google.common.base.Preconditions.checkNotNull;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.COMPONENT;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.PROPRIETARY_COMPONENTS;
import static org.sonatype.nexus.rest.APIConstants.INTERNAL_API_PREFIX;

/**
 * Internal API for UI that allows bulk (un)marking repositories as source of proprietary content to, in conjunction
 * with IQ Server, protect against namespace conflict attacks.
 *
 * @since 3.30
 */
@Named
@Singleton
@Path(RepositoryProprietaryContentResource.RESOURCE_URI)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RepositoryProprietaryContentResource
    extends ComponentSupport
    implements Resource
{
  public static final String RESOURCE_URI = INTERNAL_API_PREFIX + "/proprietary-content";

  private final AuthorizingRepositoryManager repositoryManager;

  @Inject
  public RepositoryProprietaryContentResource(final AuthorizingRepositoryManager repositoryManager) {
    this.repositoryManager = checkNotNull(repositoryManager);
  }

  /**
   * @return list of repository names that are set as source of proprietary content
   */
  @GET
  public List<String> get() {
    return repositoryManager.getRepositoriesWithAdmin().stream()
        .filter(repository -> repository.getConfiguration().attributes(COMPONENT)
            .get(PROPRIETARY_COMPONENTS, Boolean.class, false))
        .map(Repository::getName)
        .collect(Collectors.toList());
  }

  /**
   * Mark or unmark provided repositories as source of proprietary content
   */
  @RequiresAuthentication
  @POST
  @Validate
  public void set(@NotNull @Valid final ProprietaryContentRequest request) {
    List<String> failed = Lists.newArrayList();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Process proprietary repositories with virtual threads
      request.getProprietary().stream()
          .map(repositoryManager::getRepositoryWithAdmin)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .filter(this::isHostedRepository)
          .forEach(repository -> {
            executor.submit(() -> {
              Configuration configuration = repository.getConfiguration().copy();
              configuration.attributes(COMPONENT).set(PROPRIETARY_COMPONENTS, true);
              String repositoryName = repository.getName();
              try {
                repositoryManager.update(configuration);
                log.info(STR."Marked '\{repositoryName}' repository as source of proprietary content");
              }
              catch (Exception e) {
                log.error(STR."Failed to mark '\{repositoryName}' repository as source of proprietary content, \{log.isDebugEnabled() ? e : e.getMessage()}");
                synchronized (failed) {
                  failed.add(repositoryName);
                }
              }
            });
          });

      // Process non-proprietary repositories with virtual threads
      request.getNonProprietary().stream()
          .map(repositoryManager::getRepositoryWithAdmin)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .filter(this::isHostedRepository)
          .forEach(repository -> {
            executor.submit(() -> {
              Configuration configuration = repository.getConfiguration().copy();
              configuration.attributes(COMPONENT).set(PROPRIETARY_COMPONENTS, false);
              String repositoryName = repository.getName();
              try {
                repositoryManager.update(configuration);
                log.info(STR."Unmarked '\{repositoryName}' repository as source of proprietary content");
              }
              catch (Exception e) {
                log.error(STR."Failed to unmark '\{repositoryName}' repository as source of proprietary content, \{log.isDebugEnabled() ? e : e.getMessage()}");
                synchronized (failed) {
                  failed.add(repositoryName);
                }
              }
            });
          });
    }

    if (!failed.isEmpty()) {
      throw new WebApplicationMessageException(Status.INTERNAL_SERVER_ERROR,
          STR."\"Problem updating following repositories: \{Joiner.on(", ").join(failed)}\"", APPLICATION_JSON);
    }
  }

  private boolean isHostedRepository(final Repository repository) {
    return switch (repository.getType().getValue()) {
      case HostedType.NAME -> {
        yield true;
      }
      default -> {
        log.warn(STR."Repository \{repository.getName()} is not a hosted repository, proprietary component flag is not valid for non-hosted repositories");
        yield false;
      }
    };
  }
}