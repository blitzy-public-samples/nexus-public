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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository;
import org.sonatype.nexus.rest.Resource;

import org.apache.shiro.authz.annotation.RequiresAuthentication;

import static com.google.common.base.Preconditions.checkNotNull;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Repository settings API resource that leverages Java 21 Virtual Threads for improved concurrency
 * when handling HTTP requests.
 * 
 * @since 3.26
 */
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class RepositorySettingsApiResource
    extends ComponentSupport
    implements Resource, RepositorySettingsApiResourceDoc
{
  private final AuthorizingRepositoryManager authorizingRepositoryManager;

  private final Map<String, ApiRepositoryAdapter> convertersByFormat;

  private final ApiRepositoryAdapter defaultAdapter;

  @Inject
  public RepositorySettingsApiResource(
      final AuthorizingRepositoryManager authorizingRepositoryManager,
      @Named("default") final ApiRepositoryAdapter defaultAdapter,
      final Map<String, ApiRepositoryAdapter> convertersByFormat)
  {
    this.authorizingRepositoryManager = checkNotNull(authorizingRepositoryManager);
    this.defaultAdapter = checkNotNull(defaultAdapter);
    this.convertersByFormat = checkNotNull(convertersByFormat);
  }

  /**
   * Get repositories using Virtual Threads for improved concurrency.
   * This implementation executes the repository retrieval and conversion operations
   * within a Virtual Thread, which is more efficient for I/O-bound operations.
   */
  @Override
  @RequiresAuthentication
  @GET
  public List<AbstractApiRepository> getRepositories() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> 
          authorizingRepositoryManager.getRepositoriesWithAdmin().stream()
              .map(this::convert)
              .collect(Collectors.toList())
      ).get();
    } catch (InterruptedException e) {
      log.error("Virtual thread was interrupted while retrieving repositories", e);
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to retrieve repositories", e);
    } catch (ExecutionException e) {
      log.error("Error occurred while retrieving repositories in virtual thread", e);
      throw new RuntimeException("Failed to retrieve repositories", e.getCause());
    }
  }

  private AbstractApiRepository convert(final Repository repository) {
    return convertersByFormat.getOrDefault(repository.getFormat().toString(), defaultAdapter).adapt(repository);
  }
}