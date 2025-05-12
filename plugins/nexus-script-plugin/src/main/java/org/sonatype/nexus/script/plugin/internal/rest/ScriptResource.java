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

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.script.ScriptService;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptClient;
import org.sonatype.nexus.script.ScriptManager;
import org.sonatype.nexus.script.ScriptResultXO;
import org.sonatype.nexus.script.ScriptRunEvent;
import org.sonatype.nexus.script.ScriptXO;
import org.sonatype.nexus.script.plugin.internal.ScriptingDisabledException;
import org.sonatype.nexus.script.plugin.internal.security.ScriptPermission;
import org.sonatype.nexus.security.BreadActions;
import org.sonatype.nexus.security.SecurityHelper;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static java.lang.StringTemplate.STR;

/**
 * BREAD resource for managing {@link Script} instances.
 *
 * @since 3.0
 */
@Named
@Singleton
@Path(ScriptResource.RESOURCE_URI)
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@Tag(name = "Script")
public class ScriptResource
    extends ComponentSupport
    implements ScriptClient, Resource
{
  public static final String RESOURCE_URI = "/v1/script";

  private final ScriptManager scriptManager;

  private final SecurityHelper securityHelper;

  private final ScriptService scriptService;

  private final EventManager eventManager;

  @Inject
  public ScriptResource(
      final ScriptManager scriptManager,
      final SecurityHelper securityHelper,
      final ScriptService scriptService,
      final EventManager eventManager)
  {
    this.scriptManager = checkNotNull(scriptManager);
    this.securityHelper = checkNotNull(securityHelper);
    this.scriptService = checkNotNull(scriptService);
    this.eventManager = checkNotNull(eventManager);
  }

  @Override
  @Timed
  @ExceptionMetered
  @Operation(summary = "List all stored scripts")
  @RequiresPermissions("nexus:script:*:browse")
  public List<ScriptXO> browse() {
    List<ScriptXO> storedScripts = new ArrayList<>();
    scriptManager.browse()
        .forEach(script -> storedScripts.add(convert(script)));
    return storedScripts;
  }

  @Override
  @Timed
  @ExceptionMetered
  @Operation(summary = "Read stored script by name")
  @ApiResponses(@ApiResponse(responseCode = "404", description = "No script with the specified name"))
  public ScriptXO read(@PathParam("name") final String name) {
    securityHelper.ensurePermitted(scriptPermission(name, BreadActions.READ));
    return convert(findOr404(name));
  }

  @Override
  @Timed
  @ExceptionMetered
  @Operation(summary = "Update stored script by name")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Script was updated"),
      @ApiResponse(responseCode = "404", description = "No script with the specified name"),
      @ApiResponse(responseCode = "410", description = "Script updating is disabled")
  })
  public void edit(@PathParam("name") final String name, @NotNull @Valid final ScriptXO scriptXO) {
    securityHelper.ensurePermitted(scriptPermission(name, BreadActions.EDIT));
    checkArgument(name.equals(scriptXO.getName()),
        STR."Path parameter: \{name} does not match data name: \{scriptXO.getName()}");
    findOr404(name);
    log.debug(STR."Updating Script named: \{name}");
    try {
      scriptManager.update(name, scriptXO.getContent());
    }
    catch (ScriptingDisabledException e) { // NOSONAR
      log.debug(STR."Failed to update script \{name}, creating and updating scripts is disabled", e);
      throw new WebApplicationException(
          Response.status(Response.Status.GONE).entity(new ScriptResultXO(name, e.getMessage())).build());
    }
  }

  @Override
  @Timed
  @ExceptionMetered
  @Operation(summary = "Add a new script")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Script was added"),
      @ApiResponse(responseCode = "410", description = "Script creation is disabled")
  })
  @RequiresPermissions("nexus:script:*:add")
  public void add(@NotNull @Valid final ScriptXO scriptXO) {
    log.debug(STR."Adding Script named: \{scriptXO.getName()}");
    try {
      scriptManager.create(scriptXO.getName(), scriptXO.getContent(), scriptXO.getType());
    }
    catch (ScriptingDisabledException e) { // NOSONAR
      log.debug(STR."Failed to create script \{scriptXO.getName()}, creating and updating scripts is disabled", e);
      throw new WebApplicationException(
          Response.status(Response.Status.GONE).entity(new ScriptResultXO(scriptXO.getName(), e.getMessage())).build());
    }
  }

  @Override
  @Timed
  @ExceptionMetered
  @Operation(summary = "Delete stored script by name")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Script was deleted"),
      @ApiResponse(responseCode = "404", description = "No script with the specified name")
  })
  public void delete(@PathParam("name") final String name) {
    securityHelper.ensurePermitted(scriptPermission(name, BreadActions.DELETE));
    log.debug(STR."Deleting Script named: \{name}"); // NOSONAR
    scriptManager.delete(findOr404(name).getName());
  }

  @Override
  @Timed
  @ExceptionMetered
  @Operation(summary = "Run stored script by name")
  @ApiResponses({
      @ApiResponse(responseCode = "404", description = "No script with the specified name"),
      @ApiResponse(responseCode = "500", description = "Script execution failed with exception")
  })
  public ScriptResultXO run(@PathParam("name") final String name, final String args) {
    securityHelper.ensurePermitted(scriptPermission(name, RUN_ACTION));
    log.debug(STR."Running Script named: \{name}"); // NOSONAR
    Script script = findOr404(name);

    // execute, capturing any possible errors
    Object result;
    try {
      Map<String, Object> customBindings = new HashMap<>();
      customBindings.put("log", LoggerFactory.getLogger(this.getClass()));
      customBindings.put("args", args != null ? args.trim() : null);
      customBindings.put("scriptName", script.getName());
      result = scriptService.eval(script.getType(), script.getContent(), customBindings);
      eventManager.post(new ScriptRunEvent(script));
    }
    catch (Exception e) { // NOSONAR
      log.error(STR."Exception in script execution for script named: \{name}", e);
      throw new WebApplicationException(
          Response.status(Response.Status.BAD_REQUEST)
              .entity(new ScriptResultXO(script.getName(), e.getMessage()))
              .build());
    }
    log.trace(STR."Result: \{result}");
    String resultString = Optional.ofNullable(result).map(Object::toString).orElse("");
    return new ScriptResultXO(name, resultString);
  }

  private Script findOr404(String name) {
    Script script = scriptManager.get(name);
    if (script == null) {
      throw new NotFoundException(STR."Script with name: '\{name}' not found");
    }
    return script;
  }

  private static ScriptXO convert(final Script script) {
    return new ScriptXO(script.getName(), script.getContent(), script.getType());
  }

  private static ScriptPermission scriptPermission(final String name, final String action) {
    return new ScriptPermission(name, Collections.singletonList(action));
  }
}