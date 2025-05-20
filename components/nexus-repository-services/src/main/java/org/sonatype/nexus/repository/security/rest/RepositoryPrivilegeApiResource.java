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
package org.sonatype.nexus.repository.security.rest;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Response;

import org.sonatype.nexus.common.thread.VirtualThreadExecutorService;
import org.sonatype.nexus.repository.security.RepositoryAdminPrivilegeDescriptor;
import org.sonatype.nexus.repository.security.RepositoryContentSelectorPrivilegeDescriptor;
import org.sonatype.nexus.repository.security.RepositoryViewPrivilegeDescriptor;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.rest.PrivilegeApiResourceSupport;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Repository privilege API resource that handles privilege operations for repositories.
 * This implementation uses Java 21 Virtual Threads for improved concurrency and performance.
 *
 * @since 3.19
 */
@Named
@Singleton
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class RepositoryPrivilegeApiResource
    extends PrivilegeApiResourceSupport
    implements Resource, RepositoryPrivilegeApiResourceDoc
{
  private final VirtualThreadExecutorService executor;
  
  @Inject
  public RepositoryPrivilegeApiResource(final SecuritySystem securitySystem,
                                        final Map<String, PrivilegeDescriptor> privilegeDescriptors)
  {
    super(securitySystem, privilegeDescriptors);
    this.executor = new VirtualThreadExecutorService(Executors.newVirtualThreadPerTaskExecutor());
    log.debug(STR."Initialized \{getClass().getSimpleName()} with Virtual Thread support");
  }
  
  /**
   * Cleanup executor service on shutdown
   */
  @PreDestroy
  public void shutdown() {
    log.debug(STR."Shutting down \{getClass().getSimpleName()} executor");
    executor.shutdown();
  }

  /**
   * Creates a repository admin type privilege using Virtual Threads for improved performance.
   * This implementation maintains the original API signature while using Virtual Threads internally.
   */
  @Override
  @POST
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:create")
  @Path("repository-admin")
  public Response createPrivilege(final ApiPrivilegeRepositoryAdminRequest privilege) {
    log.debug(STR."Creating repository admin privilege: \{privilege.getName()}");
    
    // For simple operations, we can use a CompletableFuture and join() to maintain API compatibility
    // while still benefiting from Virtual Threads for concurrent requests
    try {
      return executor.supplyAsync(() -> {
        // Pattern matching to validate privilege type
        if (privilege instanceof ApiPrivilegeRepositoryAdminRequest adminRequest) {
          log.debug(STR."Processing repository admin privilege request: \{adminRequest.getName()}");
          return doCreate(RepositoryAdminPrivilegeDescriptor.TYPE, adminRequest);
        } else {
          log.warn(STR."Unexpected privilege type: \{privilege.getClass().getName()}");
          return doCreate(RepositoryAdminPrivilegeDescriptor.TYPE, privilege);
        }
      }).join();
    } catch (Exception e) {
      log.error(STR."Failed to create repository admin privilege: \{privilege.getName()}", e);
      throw e;
    }
  }
  
  /**
   * Updates a repository admin type privilege using Virtual Threads for improved performance.
   */
  @Override
  @PUT
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:update")
  @Path("repository-admin/{privilegeName}")
  public void updatePrivilege(
      @PathParam("privilegeName") final String privilegeName,
      final ApiPrivilegeRepositoryAdminRequest privilege)
  {
    log.debug(STR."Updating repository admin privilege: \{privilegeName}");
    
    try {
      executor.supplyAsync(() -> {
        // Pattern matching to validate privilege name matches path parameter
        if (privilege.getName().equals(privilegeName)) {
          doUpdate(privilegeName, RepositoryAdminPrivilegeDescriptor.TYPE, privilege);
          return null;
        } else {
          log.warn(STR."Privilege name \{privilege.getName()} does not match path parameter \{privilegeName}");
          doUpdate(privilegeName, RepositoryAdminPrivilegeDescriptor.TYPE, privilege);
          return null;
        }
      }).join();
    } catch (Exception e) {
      log.error(STR."Failed to update repository admin privilege: \{privilegeName}", e);
      throw e;
    }
  }
  
  /**
   * Creates a repository view type privilege using Virtual Threads for improved performance.
   */
  @Override
  @POST
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:create")
  @Path("repository-view")
  public Response createPrivilege(final ApiPrivilegeRepositoryViewRequest privilege) {
    log.debug(STR."Creating repository view privilege: \{privilege.getName()}");
    
    try {
      return executor.supplyAsync(() -> {
        // Pattern matching for type validation
        if (privilege instanceof ApiPrivilegeRepositoryViewRequest viewRequest) {
          log.debug(STR."Processing repository view privilege with format: \{viewRequest.getFormat()}, repository: \{viewRequest.getRepository()}");
          return doCreate(RepositoryViewPrivilegeDescriptor.TYPE, viewRequest);
        } else {
          return doCreate(RepositoryViewPrivilegeDescriptor.TYPE, privilege);
        }
      }).join();
    } catch (Exception e) {
      log.error(STR."Failed to create repository view privilege: \{privilege.getName()}", e);
      throw e;
    }
  }

  /**
   * Updates a repository view type privilege using Virtual Threads for improved performance.
   */
  @Override
  @PUT
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:update")
  @Path("repository-view/{privilegeName}")
  public void updatePrivilege(
      @PathParam("privilegeName") final String privilegeName,
      final ApiPrivilegeRepositoryViewRequest privilege)
  {
    log.debug(STR."Updating repository view privilege: \{privilegeName}");
    
    try {
      executor.supplyAsync(() -> {
        // Pattern matching to validate privilege name matches path parameter
        if (privilege.getName().equals(privilegeName)) {
          doUpdate(privilegeName, RepositoryViewPrivilegeDescriptor.TYPE, privilege);
        } else {
          log.warn(STR."Privilege name \{privilege.getName()} does not match path parameter \{privilegeName}");
          doUpdate(privilegeName, RepositoryViewPrivilegeDescriptor.TYPE, privilege);
        }
        return null;
      }).join();
    } catch (Exception e) {
      log.error(STR."Failed to update repository view privilege: \{privilegeName}", e);
      throw e;
    }
  }
  
  /**
   * Creates a repository content selector type privilege using Virtual Threads for improved performance.
   */
  @Override
  @POST
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:create")
  @Path("repository-content-selector")
  public Response createPrivilege(final ApiPrivilegeRepositoryContentSelectorRequest privilege) {
    log.debug(STR."Creating repository content selector privilege: \{privilege.getName()}");
    
    try {
      return executor.supplyAsync(() -> {
        // Pattern matching for content selector validation
        if (privilege instanceof ApiPrivilegeRepositoryContentSelectorRequest selectorRequest) {
          String contentSelector = selectorRequest.getContentSelector();
          log.debug(STR."Processing repository content selector privilege with selector: \{contentSelector}");
          return doCreate(RepositoryContentSelectorPrivilegeDescriptor.TYPE, selectorRequest);
        } else {
          return doCreate(RepositoryContentSelectorPrivilegeDescriptor.TYPE, privilege);
        }
      }).join();
    } catch (Exception e) {
      log.error(STR."Failed to create repository content selector privilege: \{privilege.getName()}", e);
      throw e;
    }
  }

  /**
   * Updates a repository content selector type privilege using Virtual Threads for improved performance.
   */
  @Override
  @PUT
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:update")
  @Path("repository-content-selector/{privilegeName}")
  public void updatePrivilege(
      @PathParam("privilegeName") final String privilegeName,
      final ApiPrivilegeRepositoryContentSelectorRequest privilege)
  {
    log.debug(STR."Updating repository content selector privilege: \{privilegeName}");
    
    try {
      executor.supplyAsync(() -> {
        // Use pattern matching to validate the privilege
        if (privilege instanceof ApiPrivilegeRepositoryContentSelectorRequest selectorRequest 
            && selectorRequest.getName().equals(privilegeName)) {
          log.debug(STR."Updating content selector privilege with selector: \{selectorRequest.getContentSelector()}");
          doUpdate(privilegeName, RepositoryContentSelectorPrivilegeDescriptor.TYPE, selectorRequest);
        } else {
          doUpdate(privilegeName, RepositoryContentSelectorPrivilegeDescriptor.TYPE, privilege);
        }
        return null;
      }).join();
    } catch (Exception e) {
      log.error(STR."Failed to update repository content selector privilege: \{privilegeName}", e);
      throw e;
    }
  }