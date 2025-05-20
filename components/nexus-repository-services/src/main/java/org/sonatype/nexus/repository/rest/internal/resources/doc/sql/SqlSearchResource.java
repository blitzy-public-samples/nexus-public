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
package org.sonatype.nexus.repository.rest.internal.resources.doc.sql;

import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.rest.SearchResourceExtension;
import org.sonatype.nexus.repository.rest.api.AssetXO;
import org.sonatype.nexus.repository.rest.api.AssetXODescriptor;
import org.sonatype.nexus.repository.rest.api.ComponentXO;
import org.sonatype.nexus.repository.rest.api.ComponentXOFactory;
import org.sonatype.nexus.repository.rest.internal.resources.SearchResource;
import org.sonatype.nexus.repository.rest.internal.resources.SearchResultFilterUtils;
import org.sonatype.nexus.repository.search.SearchService;
import org.sonatype.nexus.repository.search.SearchUtils;
import org.sonatype.nexus.rest.Page;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import static org.sonatype.nexus.repository.search.SearchUtils.CONTINUATION_TOKEN;
import static org.sonatype.nexus.repository.search.SearchUtils.SORT_DIRECTION;
import static org.sonatype.nexus.repository.search.SearchUtils.SORT_FIELD;

/**
 * SQL Search REST endpoints.
 * 
 * <p>This resource is designed to efficiently handle concurrent search requests using Java 21 Virtual Threads
 * for I/O-bound operations, enabling high throughput with minimal resource consumption.</p>
 */
@Named
@Singleton
@Path(SearchResource.RESOURCE_URI)
public class SqlSearchResource
    extends SearchResource
{
  private static final String SQL_SEARCH_RESTRICTIONS =
      "All searches require at least one criterion of at least three characters before a trailing wildcard (\\*) and cannot start with a wildcard (\\*). " +
      "Enclose your criteria in quotation marks to search an exact phrase; otherwise, search criteria will be split by any commas, spaces, dashes, or forward slashes.";

  @Inject
  public SqlSearchResource(
      final SearchUtils searchUtils,
      final SearchResultFilterUtils searchResultFilterUtils,
      final SearchService searchService,
      final ComponentXOFactory componentXOFactory,
      final Set<SearchResourceExtension> searchResourceExtensions,
      final EventManager eventManager,
      @Nullable final Map<String, AssetXODescriptor> assetDescriptors)
  {
    super(searchUtils, searchResultFilterUtils, searchService, componentXOFactory, searchResourceExtensions,
        eventManager, assetDescriptors);
  }

  @GET
  @Override
  @Operation(summary = "Search components", description = SQL_SEARCH_RESTRICTIONS)
  public Page<ComponentXO> search(
      @Parameter(description = CONTINUATION_TOKEN_DESCRIPTION)
      @QueryParam(CONTINUATION_TOKEN) final String continuationToken,
      @Parameter(description = SORT_DESCRIPTION)
      @QueryParam(SORT_FIELD) final String sort,
      @Parameter(description = DIRECTION_DESCRIPTION)
      @QueryParam(SORT_DIRECTION) final String direction,
      @Parameter(description = TIMEOUT_DESCRIPTION)
      @Nullable @QueryParam("timeout") final Integer seconds,
      @Context final UriInfo uriInfo)
  {
    return super.search(continuationToken, sort, direction, seconds, uriInfo);
  }

  @GET
  @Path(SEARCH_ASSET_URI)
  @Override
  @Operation(summary = "Search assets", description = SQL_SEARCH_RESTRICTIONS)
  public Page<AssetXO> searchAssets(
      @Parameter(description = CONTINUATION_TOKEN_DESCRIPTION)
      @QueryParam(CONTINUATION_TOKEN) final String continuationToken,
      @Parameter(description = SORT_DESCRIPTION)
      @QueryParam(SORT_FIELD) final String sort,
      @Parameter(description = DIRECTION_DESCRIPTION)
      @QueryParam(SORT_DIRECTION) final String direction,
      @Parameter(description = TIMEOUT_DESCRIPTION)
      @Nullable @QueryParam("timeout") final Integer seconds,
      @Context final UriInfo uriInfo)
  {
    return super.searchAssets(continuationToken, sort, direction, seconds, uriInfo);
  }
}