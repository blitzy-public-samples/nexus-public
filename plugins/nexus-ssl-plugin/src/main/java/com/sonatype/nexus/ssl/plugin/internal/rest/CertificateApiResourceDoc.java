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
package com.sonatype.nexus.ssl.plugin.internal.rest;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import javax.ws.rs.core.Response;

import org.sonatype.nexus.ssl.ApiCertificate;
import com.sonatype.nexus.ssl.plugin.validator.HostnameOrIpAddress;
import com.sonatype.nexus.ssl.plugin.validator.PemCertificate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_CONFLICT;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;

/**
 * @since 3.19
 */
@Tag(name = "Security: certificates")
public interface CertificateApiResourceDoc
{
  @Operation(summary = "Helper method to retrieve certificate details from a remote system.")
  @ApiResponses(value = {
      @ApiResponse(responseCode = "403", description = "Insufficient permissions to retrieve remote certificate."),
      @ApiResponse(responseCode = "400",
          description = "A certificate could not be retrieved, see the message for details.")})
  ApiCertificate retrieveCertificate(
      @Parameter(description = "The remote system's host name") @NotNull @NotEmpty @HostnameOrIpAddress String host,
      @Parameter(description = "The port on the remote system to connect to") Integer port,
      @Parameter(description = "An optional hint of the protocol to try for the connection") String protocolHint);

  @Operation(summary = "Retrieve a list of certificates added to the trust store.")
  @ApiResponses(value = {
      @ApiResponse(responseCode = "403", description = "Insufficient permissions to list certificates in the trust store.")})
  List<ApiCertificate> getTrustStoreCertificates();

  @Operation(summary = "Add a certificate to the trust store.")
  @ApiResponses(value = {
      @ApiResponse(responseCode = "201", description = "The certificate was successfully added.",
          content = @Content(schema = @Schema(implementation = ApiCertificate.class))),
      @ApiResponse(responseCode = "409",
          description = "The certificate already exists in the system."),
      @ApiResponse(responseCode = "403", description = "Insufficient permissions to add certificate to the trust store.")})
  Response addCertificate(
      @Parameter(description = "The certificate to add encoded in PEM format") @NotBlank @PemCertificate String pem);

  @Operation(summary = "Remove a certificate in the trust store.")
  @ApiResponses(value = {
      @ApiResponse(responseCode = "403",
          description = "Insufficient permissions to remove certificate from the trust store")})
  void removeCertificate(@Parameter(description = "The id of the certificate that should be removed.") String id);
}