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
package org.sonatype.nexus.internal.email.rest;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import static org.sonatype.nexus.repository.http.HttpStatus.BAD_REQUEST;
import static org.sonatype.nexus.repository.http.HttpStatus.FORBIDDEN;
import static org.sonatype.nexus.repository.http.HttpStatus.NO_CONTENT;
import static org.sonatype.nexus.repository.http.HttpStatus.OK;

/**
 * Swagger documentation for {@link EmailConfigurationApiResource}
 *
 * @since 3.19
 */
@Tag(name = "Email")
public interface EmailConfigurationApiResourceDoc
{
  @Operation(
      summary = "Retrieve the current email configuration",
      description = "Fetches the current email configuration using Virtual Threads for improved performance")
  @ApiResponses(value = {
      @ApiResponse(
          responseCode = "200",
          description = "Email configuration retrieved successfully",
          content = @Content(schema = @Schema(implementation = ApiEmailConfiguration.class))),
      @ApiResponse(
          responseCode = FORBIDDEN,
          description = "Insufficient permissions to retrieve the email configuration")
  })
  ApiEmailConfiguration getEmailConfiguration();

  @Operation(
      summary = "Set the current email configuration",
      description = "Updates the email configuration using non-blocking I/O operations with Virtual Threads")
  @ApiResponses(value = {
      @ApiResponse(
          responseCode = NO_CONTENT,
          description = "Email configuration was successfully updated"),
      @ApiResponse(
          responseCode = BAD_REQUEST,
          description = "Invalid request"),
      @ApiResponse(
          responseCode = FORBIDDEN,
          description = "Insufficient permissions to update the email configuration")
  })
  void setEmailConfiguration(
      @Parameter(description = "Email configuration to set", required = true)
      @NotNull @Valid ApiEmailConfiguration emailConfiguration);

  @Operation(
      summary = "Send a test email to the email address provided in the request body",
      description = "Validates email configuration by sending a test email using Virtual Threads for non-blocking operations")
  @ApiResponses(value = {
      @ApiResponse(
          responseCode = OK,
          description = "Validation was complete, look at the body to determine success",
          content = @Content(schema = @Schema(implementation = ApiEmailValidation.class))),
      @ApiResponse(
          responseCode = FORBIDDEN,
          description = "Insufficient permissions to verify the email configuration")
  })
  ApiEmailValidation testEmailConfiguration(
      @Parameter(description = "An email address to send a test email to", required = true)
      @NotNull String validationEmail
  );

  @Operation(
      summary = "Disable and clear the email configuration",
      description = "Efficiently clears email configuration using Virtual Threads for improved performance")
  @ApiResponses(value = {
      @ApiResponse(
          responseCode = NO_CONTENT,
          description = "Email configuration was successfully cleared")
  })
  void deleteEmailConfiguration();
}