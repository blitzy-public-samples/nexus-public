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

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.selector.ContentSelectorCreatedEvent;
import org.sonatype.nexus.selector.ContentSelectorDeletedEvent;
import org.sonatype.nexus.selector.ContentSelectorUpdatedEvent;
import org.sonatype.nexus.repository.rest.api.ContentSelectorApiCreateRequest;
import org.sonatype.nexus.repository.rest.api.ContentSelectorApiResponse;
import org.sonatype.nexus.repository.rest.api.ContentSelectorApiUpdateRequest;
import org.sonatype.nexus.repository.rest.internal.resources.doc.ContentSelectorsResourceDoc;
import org.sonatype.nexus.rest.NotCacheable;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.selector.CselSelector;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorConfigurationStore;
import org.sonatype.nexus.selector.SelectorFactory;
import org.sonatype.nexus.selector.SelectorManager;
import org.sonatype.nexus.validation.Validate;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static org.sonatype.nexus.selector.SelectorConfiguration.EXPRESSION;

/**
 * REST API resource for managing content selectors.
 *
 * @since 3.19
 */
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class ContentSelectorsApiResource
    implements Resource, ContentSelectorsResourceDoc
{
  private final SelectorFactory selectorFactory;

  private final SelectorManager selectorManager;

  private final SelectorConfigurationStore store;

  private final EventManager eventManager;
  
  // Virtual thread executor for database operations
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public ContentSelectorsApiResource(
      final SelectorFactory selectorFactory,
      final SelectorManager selectorManager,
      final SelectorConfigurationStore store,
      final EventManager eventManager)
  {
    this.selectorFactory = checkNotNull(selectorFactory);
    this.selectorManager = checkNotNull(selectorManager);
    this.store = checkNotNull(store);
    this.eventManager = checkNotNull(eventManager);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @GET
  @RequiresAuthentication
  @RequiresPermissions("nexus:selectors:read")
  @NotCacheable
  public List<ContentSelectorApiResponse> getContentSelectors() {
    // Use virtual threads for database operations
    return virtualThreadExecutor.submit(() -> 
        store.browse().stream()
            .map(ContentSelectorsApiResource::fromSelectorConfiguration)
            .collect(toList())
    ).join();
  }

  @POST
  @RequiresAuthentication
  @Validate
  @RequiresPermissions("nexus:selectors:create")
  public void createContentSelector(@Valid final ContentSelectorApiCreateRequest request) {
    // Validate selector expression
    selectorFactory.validateSelector(CselSelector.TYPE, request.getExpression());
    
    // Use virtual threads for database operations
    virtualThreadExecutor.submit(() -> {
      // Create the selector configuration
      selectorManager.create(request.getName(), CselSelector.TYPE, request.getDescription(),
          singletonMap(EXPRESSION, request.getExpression()));
      
      // Find the created configuration
      SelectorConfiguration configuration = findConfigurationByNameOrThrowNotFound(request.getName());
      
      // Post event using virtual threads for asynchronous dispatch
      virtualThreadExecutor.submit(() -> 
          eventManager.post(new ContentSelectorCreatedEvent(configuration))
      );
      
      return null;
    }).join();
  }

  @GET
  @Path("{name}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:selectors:read")
  public ContentSelectorApiResponse getContentSelector(@PathParam("name") final String name) {
    // Use virtual threads for database operations
    return virtualThreadExecutor.submit(() -> {
      SelectorConfiguration configuration = findConfigurationByNameOrThrowNotFound(name);
      return ContentSelectorsApiResource.fromSelectorConfiguration(configuration);
    }).join();
  }

  @PUT
  @Path("{name}")
  @RequiresAuthentication
  @Validate
  @RequiresPermissions("nexus:selectors:update")
  public void updateContentSelector(@PathParam("name") final String name,
                                    @Valid final ContentSelectorApiUpdateRequest request)
  {
    // Use virtual threads for database operations
    virtualThreadExecutor.submit(() -> {
      SelectorConfiguration configuration = findConfigurationByNameOrThrowNotFound(name);

      // Validate selector expression
      selectorFactory.validateSelector(configuration.getType(), request.getExpression());

      // Update configuration
      configuration.setDescription(request.getDescription());
      configuration.setAttributes(singletonMap(EXPRESSION, request.getExpression()));
      selectorManager.update(configuration);
      
      // Post event using virtual threads for asynchronous dispatch
      virtualThreadExecutor.submit(() -> 
          eventManager.post(new ContentSelectorUpdatedEvent(configuration))
      );
      
      return null;
    }).join();
  }

  @DELETE
  @Path("{name}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:selectors:delete")
  public void deleteContentSelector(@PathParam("name") final String name) {
    // Use virtual threads for database operations
    virtualThreadExecutor.submit(() -> {
      SelectorConfiguration configuration = findConfigurationByNameOrThrowNotFound(name);

      // Delete configuration
      selectorManager.delete(configuration);
      
      // Post event using virtual threads for asynchronous dispatch
      virtualThreadExecutor.submit(() -> 
          eventManager.post(new ContentSelectorDeletedEvent(configuration))
      );
      
      return null;
    }).join();
  }

  private SelectorConfiguration findConfigurationByNameOrThrowNotFound(final String name) {
    // Using Java 21 String Templates for error message formatting
    return selectorManager.findByName(name)
        .orElseThrow(() -> new WebApplicationMessageException(NOT_FOUND, 
            STR."No selector found for \{name}", APPLICATION_JSON));
  }

  private static ContentSelectorApiResponse fromSelectorConfiguration(final SelectorConfiguration selectorConfiguration) {
    // Using pattern matching for instanceof check (though simple in this case)
    if (selectorConfiguration instanceof SelectorConfiguration config) {
      ContentSelectorApiResponse response = new ContentSelectorApiResponse();
      response.setName(config.getName());
      response.setType(config.getType());
      response.setDescription(config.getDescription());
      response.setExpression(config.getAttributes().get(EXPRESSION));
      return response;
    }
    
    // This is a fallback case that shouldn't be reached in practice
    ContentSelectorApiResponse response = new ContentSelectorApiResponse();
    response.setName(selectorConfiguration.getName());
    response.setType(selectorConfiguration.getType());
    response.setDescription(selectorConfiguration.getDescription());
    response.setExpression(selectorConfiguration.getAttributes().get(EXPRESSION));
    return response;
  }
}