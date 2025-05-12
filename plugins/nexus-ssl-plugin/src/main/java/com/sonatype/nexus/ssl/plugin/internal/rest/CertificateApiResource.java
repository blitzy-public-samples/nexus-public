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

import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import javax.naming.InvalidNameException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.sonatype.nexus.ssl.CertificateRetriever;
import org.sonatype.nexus.ssl.ApiCertificate;
import com.sonatype.nexus.ssl.plugin.validator.HostnameOrIpAddress;
import com.sonatype.nexus.ssl.plugin.validator.PemCertificate;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.ssl.KeyNotFoundException;
import org.sonatype.nexus.ssl.KeystoreException;
import org.sonatype.nexus.ssl.TrustStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static java.lang.StringTemplate.STR;

import static org.sonatype.nexus.ssl.TrustStore.KEY_STORE_ERROR_MESSAGE;

/**
 * @since 3.19
 */
@Produces(MediaType.APPLICATION_JSON)
public class CertificateApiResource
    extends ComponentSupport
    implements Resource, CertificateApiResourceDoc
{
  // Removed static message format in favor of string templates

  private final TrustStore trustStore;

  private final CertificateRetriever certificateRetriever;

  private final ObjectWriter stringWriter = new ObjectMapper().writerFor(String.class);

  @Inject
  public CertificateApiResource(final TrustStore trustStore, final CertificateRetriever certificateRetriever) {
    this.certificateRetriever = certificateRetriever;
    this.trustStore = trustStore;
  }

  @Override
  @GET
  @RequiresAuthentication
  @RequiresPermissions("nexus:ssl-truststore:read")
  public ApiCertificate retrieveCertificate(
      @NotNull @NotEmpty @HostnameOrIpAddress @QueryParam("host") final String host,
      @DefaultValue("443") @QueryParam("port") final Integer port,
      @QueryParam("protocolHint") final String protocolHint)
  {
    try {
      Certificate[] certificates = certificateRetriever.retrieveCertificates(host, port, protocolHint);

      if (certificates == null || certificates.length == 0) {
        throw createWebException(Status.BAD_REQUEST, STR."Unable to retrieve certificate from host: \{host}");
      }

      return ApiCertificate.convert(certificates[0]);
    }
    catch (Exception e) {
      return switch (e) {
        case UnknownHostException uhe -> {
          // NOSONAR
          throw createWebException(Status.BAD_REQUEST, STR."Unknown host \{host}");
        }
        default -> {
          log.debug(STR."Failed to retrieve certificate from host:\{host} on port:\{port} with protocolHint:\{protocolHint}", e);
          throw createWebException(Status.BAD_REQUEST, e.getMessage());
        }
      };
    }
  }

  @Override
  @GET
  @Path("truststore")
  @RequiresAuthentication
  @RequiresPermissions("nexus:ssl-truststore:read")
  public List<ApiCertificate> getTrustStoreCertificates() {
    try {
      return trustStore.getTrustedCertificates()
          .stream()
          .map(this::convertOrNull)
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
    }
    catch (KeystoreException e) {
      log.error("An error occurred accessing the internal trust store.", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
    }
  }

  @Override
  @POST
  @Path("truststore/")
  @RequiresAuthentication
  @RequiresPermissions("nexus:ssl-truststore:create")
  public Response addCertificate(@NotBlank @PemCertificate final String pem) {
    Certificate certificate = null;
    String fingerprint = null;
    try {
      certificate = CertificateUtil.decodePEMFormattedCertificate(pem);
      fingerprint = CertificateUtil.calculateFingerprint(certificate);

      trustStore.getTrustedCertificate(fingerprint);
      throw createWebException(Status.CONFLICT, STR."A certificate already exists with the id: '\{fingerprint}'.");
    }
    catch (Exception e) {
      switch (e) {
        case KeyNotFoundException knfe -> {
          // Great, it doesn't exist - continue processing
        }
        case KeystoreException kse -> {
          log.error(STR."An error occurred accessing the internal trust store.", kse);
          throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
        }
        case CertificateException ce -> {
          log.debug(STR."A certificate error occurred during import", ce);
          throw createWebException(Status.BAD_REQUEST, STR."The certificate is invalid. \{ce.getMessage()}");
        }
        default -> throw e; // Rethrow any unexpected exceptions
      }
    }

    // If we get here, the certificate doesn't exist yet
    Certificate importedCertificate = importCertificate(certificate);

    return Response.status(Status.CREATED).entity(convert(fingerprint, importedCertificate)).build();
  }

  @Override
  @DELETE
  @Path("truststore/{id}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:ssl-truststore:delete")
  public void removeCertificate(@PathParam("id") final String id) {
    try {
      // check that the certificate exists
      getTrustedCertificate(id);

      trustStore.removeTrustCertificate(id);
    }
    catch (KeystoreException e) {
      log.error(STR."An error occurred accessing the internal trust store.", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
    }
  }

  private Certificate getTrustedCertificate(final String id) {
    try {
      return trustStore.getTrustedCertificate(id);
    }
    catch (KeyNotFoundException e) {
      log.debug(STR."No existing certificate with id \{id}", e);
      throw createWebException(Status.NOT_FOUND, STR."No certificate with alias '\{id}' in trust store.");
    }
    catch (KeystoreException e) {
      log.error(STR."An error occurred accessing the internal trust store.", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
    }
  }

  private Certificate importCertificate(final Certificate certificate) {
    String id = null;
    try {
      id = CertificateUtil.calculateFingerprint(certificate);
      return trustStore.importTrustCertificate(certificate, id);
    }
    catch (CertificateException e) {
      // Validation should have caught this but....
      log.info(STR."Unable to import certificate \{id}", e);
      throw createWebException(Status.BAD_REQUEST, STR."Invalid certificate: \{e.getMessage()}");
    }
    catch (KeystoreException e) {
      log.error(STR."An error occurred accessing the internal trust store.", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
    }
  }

  private ApiCertificate convert(final String id, final Certificate certificate) {
    try {
      return ApiCertificate.convert(certificate);
    }
    catch (CertificateEncodingException | InvalidNameException | IOException e) {
      log.info(STR."An error occurred serializing certificate '\{id}'", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR,
          STR."An error occurred serializing the certificate after it was updated.");
    }
  }

  private ApiCertificate convertOrNull(final Certificate certificate) {
    try {
      return ApiCertificate.convert(certificate);
    }
    catch (CertificateEncodingException | InvalidNameException | IOException e) {
      log.info(STR."Failed to convert certificate \{certificate}", e);
      return null;
    }
  }

  private WebApplicationMessageException createWebException(final Status status, final String message) {
    try {
      return new WebApplicationMessageException(status, stringWriter.writeValueAsString(message),
          MediaType.APPLICATION_JSON);
    }
    catch (JsonProcessingException e) {
      log.warn(STR."An error occurred serializing an error message", e);
      return new WebApplicationMessageException(status,
          STR."\"An error occurred serializing the error message. See nexus log.\"", MediaType.APPLICATION_JSON);
    }
  }
}