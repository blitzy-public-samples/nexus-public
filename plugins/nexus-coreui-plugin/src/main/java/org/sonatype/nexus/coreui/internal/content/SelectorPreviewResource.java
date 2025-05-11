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
package org.sonatype.nexus.coreui.internal.content;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.coreui.AssetXO;
import org.sonatype.nexus.coreui.ComponentHelper;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.query.PageResult;
import org.sonatype.nexus.repository.query.QueryOptions;
import org.sonatype.nexus.repository.security.RepositorySelector;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.selector.SelectorFactory;

import com.google.common.collect.ImmutableList;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.authz.annotation.Logical;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Streams.stream;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST endpoint for previewing content selector results.
 * 
 * This resource is designed to be compatible with Java 21 Virtual Threads,
 * allowing for efficient handling of concurrent preview requests.
 *
 * @since 3.29
 */
@Named
@Singleton
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path(SelectorPreviewResource.RESOURCE_PATH)
public class SelectorPreviewResource
    extends ComponentSupport
    implements Resource
{
  static final String RESOURCE_PATH = "internal/ui/content-selectors";

  private final ComponentHelper componentHelper;

  private final RepositoryManager repositoryManager;

  private final SelectorFactory selectorFactory;

  @Inject
  public SelectorPreviewResource(
      final ComponentHelper componentHelper,
      final RepositoryManager repositoryManager,
      final SelectorFactory selectorFactory)
  {
    this.componentHelper = checkNotNull(componentHelper);
    this.repositoryManager = checkNotNull(repositoryManager);
    this.selectorFactory = checkNotNull(selectorFactory);
  }

  /**
   * Preview content based on a selector expression.
   * 
   * This endpoint is compatible with the virtual thread-per-request model in Java 21,
   * allowing for efficient handling of concurrent preview requests.
   *
   * @param request the selector preview request containing repository, type and expression
   * @return page result containing matching assets
   */
  @POST
  @Path("/preview")
  @RequiresAuthentication
  @RequiresPermissions(value = {"nexus:selectors:create", "nexus:selectors:update"}, logical = Logical.OR)
  public PageResult<AssetXO> previewContent(SelectorPreviewRequest request)
  {
    String selectorType = request.getType().toLowerCase();
    String expression = request.getExpression();
    
    log.debug(STR."Validating selector of type \{selectorType} with expression: \{expression}");
    selectorFactory.validateSelector(selectorType, expression);

    RepositorySelector repositorySelector = RepositorySelector.fromSelector(request.getRepository());
    List<Repository> selectedRepositories = getPreviewRepositories(repositorySelector);
    
    if (selectedRepositories.isEmpty()) {
      log.debug(STR."No repositories matched selector: \{repositorySelector}");
      return new PageResult<>(0, emptyList());
    }

    log.debug(STR."Previewing assets for \{selectedRepositories.size()} repositories with expression: \{expression}");
    return componentHelper.previewAssets(
        repositorySelector,
        selectedRepositories,
        expression,
        new QueryOptions(null, null, null, 0, 10)
    );
  }

  /**
   * Get repositories matching the repository selector using pattern matching.
   * 
   * @param repositorySelector the repository selector to match against
   * @return list of matching repositories
   */
  private List<Repository> getPreviewRepositories(final RepositorySelector repositorySelector) {
    return switch (repositorySelector) {
      // Case 1: Specific repository selected
      case RepositorySelector selector when !selector.isAllRepositories() ->
        ImmutableList.of(repositoryManager.get(selector.getName()));
      
      // Case 2: All repositories of a specific format
      case RepositorySelector selector when !selector.isAllFormats() ->
        stream(repositoryManager.browse())
            .filter(repository -> repository.getFormat().toString().equals(selector.getFormat()))
            .collect(toList());
      
      // Case 3: All repositories of all formats
      case RepositorySelector selector when selector.isAllRepositories() && selector.isAllFormats() ->
        stream(repositoryManager.browse()).collect(toList());
      
      // Default case (should not happen with proper RepositorySelector instances)
      default -> {
        log.warn(STR."Unexpected repository selector configuration: \{repositorySelector}");
        yield emptyList();
      }
    };
  }
}