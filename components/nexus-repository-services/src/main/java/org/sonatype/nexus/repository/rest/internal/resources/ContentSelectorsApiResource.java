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
import java.util.function.Supplier;

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
  }
  
  /**
   * Execute a database operation using Virtual Threads for improved concurrency.
   *
   * @param operation the operation to execute
   * @param <T> the return type of the operation
   * @return the result of the operation
   */
  private <T> T withVirtualThread(final Supplier<T> operation) {
    try {
      return Executors.newVirtualThreadPerTaskExecutor().submit(operation::get).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error executing operation with virtual thread", e);
    }
  }
  
  /**
   * Execute a database operation with no return value using Virtual Threads for improved concurrency.
   *
   * @param operation the operation to execute
   */
  private void withVirtualThread(final Runnable operation) {
    try {
      Executors.newVirtualThreadPerTaskExecutor().submit(operation).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error executing operation with virtual thread", e);
    }
  }

  @GET
  @RequiresAuthentication
  @RequiresPermissions("nexus:selectors:read")
  @NotCacheable
  public List<ContentSelectorApiResponse> getContentSelectors() {
    return withVirtualThread(() -> store.browse().stream()
        .map(ContentSelectorsApiResource::fromSelectorConfiguration)
        .collect(toList()));
  }

  @POST
  @RequiresAuthentication
  @Validate
  @RequiresPermissions("nexus:selectors:create")
  public void createContentSelector(@Valid final ContentSelectorApiCreateRequest request) {
    withVirtualThread(() -> {
      selectorFactory.validateSelector(CselSelector.TYPE, request.getExpression());
      selectorManager.create(request.getName(), CselSelector.TYPE, request.getDescription(),
          singletonMap(EXPRESSION, request.getExpression()));
      SelectorConfiguration configuration = findConfigurationByNameOrThrowNotFound(request.getName());
      
      // Post event using virtual thread for non-blocking event dispatch
      Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
          eventManager.post(new ContentSelectorCreatedEvent(configuration)));
      return null;
    });
  }

  @GET
  @Path("{name}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:selectors:read")
  public ContentSelectorApiResponse getContentSelector(@PathParam("name") final String name) {
    return withVirtualThread(() -> {
      SelectorConfiguration configuration = findConfigurationByNameOrThrowNotFound(name);
      return ContentSelectorsApiResource.fromSelectorConfiguration(configuration);
    });
  }

  @PUT
  @Path("{name}")
  @RequiresAuthentication
  @Validate
  @RequiresPermissions("nexus:selectors:update")
  public void updateContentSelector(@PathParam("name") final String name,
                                    @Valid final ContentSelectorApiUpdateRequest request)
  {
    withVirtualThread(() -> {
      // Use pattern matching with instanceof to handle different request types
      if (request instanceof ContentSelectorApiUpdateRequest updateRequest) {
        SelectorConfiguration configuration = findConfigurationByNameOrThrowNotFound(name);
        selectorFactory.validateSelector(configuration.getType(), updateRequest.getExpression());
        
        configuration.setDescription(updateRequest.getDescription());
        configuration.setAttributes(singletonMap(EXPRESSION, updateRequest.getExpression()));
        selectorManager.update(configuration);
        
        // Post event using virtual thread for non-blocking event dispatch
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
            eventManager.post(new ContentSelectorUpdatedEvent(configuration)));
      }
      return null;
    });
  }

  @DELETE
  @Path("{name}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:selectors:delete")
  public void deleteContentSelector(@PathParam("name") final String name) {
    withVirtualThread(() -> {
      SelectorConfiguration configuration = findConfigurationByNameOrThrowNotFound(name);
      selectorManager.delete(configuration);
      
      // Post event using virtual thread for non-blocking event dispatch
      Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
          eventManager.post(new ContentSelectorDeletedEvent(configuration)));
      return null;
    });
  }

  private SelectorConfiguration findConfigurationByNameOrThrowNotFound(final String name) {
    return selectorManager.findByName(name)
        .orElseThrow(() -> new WebApplicationMessageException(NOT_FOUND, 
            STR."No selector found for \{name}",
            APPLICATION_JSON));
  }

  private static ContentSelectorApiResponse fromSelectorConfiguration(final SelectorConfiguration selectorConfiguration) {
    ContentSelectorApiResponse response = new ContentSelectorApiResponse();
    response.setName(selectorConfiguration.getName());
    response.setType(selectorConfiguration.getType());
    response.setDescription(selectorConfiguration.getDescription());
    response.setExpression(selectorConfiguration.getAttributes().get(EXPRESSION));
    return response;
  }

}
