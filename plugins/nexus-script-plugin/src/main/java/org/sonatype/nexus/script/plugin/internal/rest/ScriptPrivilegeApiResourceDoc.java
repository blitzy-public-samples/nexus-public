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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.core.Response;

import org.sonatype.nexus.security.internal.rest.NexusSecurityApiConstants;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Documentation interface for Script Privilege API Resource.
 * Updated for compatibility with RESTEasy 6.2.7.Final and Swagger 2.2.20.
 *
 * @since 3.19
 */
@Tag(name = "Security management: privileges")
public interface ScriptPrivilegeApiResourceDoc
{
  @Operation(summary = "Create a script type privilege.")
  @ApiResponse(responseCode = "400", description = NexusSecurityApiConstants.PRIVILEGE_MISCONFIGURED)
  @ApiResponse(responseCode = "403", description = NexusSecurityApiConstants.INVALID_PERMISSIONS)
  Response createPrivilege(
      @Parameter(description = "The privilege to create.") 
      @NotNull @Valid final ApiPrivilegeScriptRequest privilege);

  @Operation(summary = "Update a script type privilege.")
  @ApiResponse(responseCode = "400", description = NexusSecurityApiConstants.PRIVILEGE_MISCONFIGURED)
  @ApiResponse(responseCode = "403", description = NexusSecurityApiConstants.INVALID_PERMISSIONS)
  @ApiResponse(responseCode = "404", description = NexusSecurityApiConstants.PRIVILEGE_NOT_FOUND)
  void updatePrivilege(
      @Parameter(description = "The name of the privilege to update.") 
      @NotNull final String privilegeName,
      @Parameter(description = "The privilege to update.") 
      @NotNull @Valid final ApiPrivilegeScriptRequest privilege);
}