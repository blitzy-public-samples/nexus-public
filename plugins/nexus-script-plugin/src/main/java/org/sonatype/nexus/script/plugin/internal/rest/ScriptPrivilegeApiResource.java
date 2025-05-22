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
package org.sonatype.nexus.script.plugin.internal.rest;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.script.plugin.internal.security.ScriptPrivilegeDescriptor;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.rest.PrivilegeApiResourceSupport;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Script privilege API resource with Java 21 Virtual Threads support for I/O-bound operations.
 * Updated for compatibility with RESTEasy 6.2.7.Final.
 *
 * @since 3.19
 */
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class ScriptPrivilegeApiResource
    extends PrivilegeApiResourceSupport
    implements Resource, ScriptPrivilegeApiResourceDoc
{
  /**
   * Default executor service for virtual threads if not overridden by subclasses.
   */
  private final ExecutorService defaultExecutorService;

  @Inject
  public ScriptPrivilegeApiResource(final SecuritySystem securitySystem,
                                    final Map<String, PrivilegeDescriptor> privilegeDescriptors)
  {
    super(securitySystem, privilegeDescriptors);
    this.defaultExecutorService = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Get the executor service to use for privilege operations.
   * Can be overridden by subclasses to provide a different executor.
   *
   * @return the executor service to use
   */
  protected ExecutorService getExecutorService() {
    return defaultExecutorService;
  }

  @Override
  @POST
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:create")
  @Path("script")
  public Response createPrivilege(final ApiPrivilegeScriptRequest privilege) {
    // Use virtual threads for I/O-bound privilege creation
    CompletableFuture<Response> future = CompletableFuture.supplyAsync(
        () -> doCreate(ScriptPrivilegeDescriptor.TYPE, privilege),
        getExecutorService());
    
    return future.join(); // Wait for the operation to complete
  }

  @Override
  @PUT
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:update")
  @Path("script/{privilegeName}")
  public void updatePrivilege(@PathParam("privilegeName") final String privilegeName,
                              final ApiPrivilegeScriptRequest privilege)
  {
    // Use virtual threads for I/O-bound privilege update
    CompletableFuture<Void> future = CompletableFuture.runAsync(
        () -> doUpdate(privilegeName, ScriptPrivilegeDescriptor.TYPE, privilege),
        getExecutorService());
    
    future.join(); // Wait for the operation to complete
  }
}