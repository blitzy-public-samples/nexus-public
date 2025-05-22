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
import java.util.concurrent.Executors;
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
  private static final String CERTIFICATE_MISSING_MESSAGE = "No certificate with alias '%s' in trust store.";

  private TrustStore trustStore;

  private CertificateRetriever certificateRetriever;

  private ObjectWriter stringWriter = new ObjectMapper().writerFor(String.class);

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
      // Use Virtual Thread for I/O-bound certificate retrieval operation
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      try (executor) {
        Certificate[] certificates = executor.submit(() -> 
            certificateRetriever.retrieveCertificates(host, port, protocolHint)).get();

        if (certificates == null || certificates.length == 0) {
          throw createWebException(Status.BAD_REQUEST, STR."Unable to retrieve certificate from host: \{host}");
        }

        return ApiCertificate.convert(certificates[0]);
      }
    }
    catch (UnknownHostException e) { // NOSONAR
      throw createWebException(Status.BAD_REQUEST, STR."Unknown host \{host}");
    }
    catch (Exception e) {
      log.debug("Failed to retrieve certificate from host:{} on port:{} with protocolHint:{}", host, port, protocolHint,
          e);
      throw createWebException(Status.BAD_REQUEST, e.getMessage());
    }
  }

  @Override
  @GET
  @Path("truststore")
  @RequiresAuthentication
  @RequiresPermissions("nexus:ssl-truststore:read")
  public List<ApiCertificate> getTrustStoreCertificates() {
    try {
      // Use Virtual Thread for I/O-bound certificate retrieval operation
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      try (executor) {
        return executor.submit(() -> trustStore.getTrustedCertificates()
            .stream()
            .map(this::convertOrNull)
            .filter(Objects::nonNull)
            .collect(Collectors.toList())).get();
      }
    }
    catch (KeystoreException e) {
      log.error("An error occurred accessing the internal trust store.", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
    }
    catch (Exception e) {
      log.error("An error occurred retrieving certificates", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, STR."Error retrieving certificates: \{e.getMessage()}");
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
    catch (KeyNotFoundException e) { // NOSONAR
      // Great, it doesn't exist
    }
    catch (KeystoreException e) {
      log.error("An error occurred accessing the internal trust store.", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
    }
    catch (CertificateException e) {
      log.debug("A certificate error occurred during import", e);
      throw createWebException(Status.BAD_REQUEST, STR."The certificate is invalid. \{e.getMessage()}");
    }

    importCertificate(certificate);

    return Response.status(Status.CREATED).entity(convert(fingerprint, certificate)).build();
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
      
      // Use Virtual Thread for I/O-bound certificate removal operation
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      try (executor) {
        executor.submit(() -> trustStore.removeTrustCertificate(id)).get();
      }
    }
    catch (KeystoreException e) {
      log.error("An error occurred accessing the internal trust store.", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
    }
    catch (Exception e) {
      log.error("An error occurred removing certificate", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, STR."Error removing certificate: \{e.getMessage()}");
    }
  }

  private Certificate getTrustedCertificate(final String id) {
    try {
      // Use Virtual Thread for I/O-bound certificate retrieval operation
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      try (executor) {
        return executor.submit(() -> trustStore.getTrustedCertificate(id)).get();
      }
    }
    catch (KeyNotFoundException e) {
      log.debug("No existing certificate with id {}", id, e);
      throw createWebException(Status.NOT_FOUND, STR."No certificate with alias '\{id}' in trust store.");
    }
    catch (KeystoreException e) {
      log.error("An error occurred accessing the internal trust store.", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
    }
    catch (Exception e) {
      log.error("An error occurred retrieving certificate", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, STR."Error retrieving certificate: \{e.getMessage()}");
    }
  }

  private Certificate importCertificate(final Certificate certificate) {
    String id = null;
    try {
      id = CertificateUtil.calculateFingerprint(certificate);
      // Use Virtual Thread for I/O-bound certificate import operation
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      try (executor) {
        return executor.submit(() -> trustStore.importTrustCertificate(certificate, id)).get();
      }
    }
    catch (CertificateException e) {
      // Validation should have caught this but....
      log.info("Unable to import certificate {}", id, e);
      throw createWebException(Status.BAD_REQUEST, STR."Invalid certificate: \{e.getMessage()}");
    }
    catch (KeystoreException e) {
      log.error("An error occurred accessing the internal trust store.", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, KEY_STORE_ERROR_MESSAGE);
    }
    catch (Exception e) {
      log.error("An error occurred importing certificate", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, STR."Error importing certificate: \{e.getMessage()}");
    }
  }

  private ApiCertificate convert(final String id, final Certificate certificate) {
    try {
      return ApiCertificate.convert(certificate);
    }
    catch (CertificateEncodingException | InvalidNameException | IOException e) {
      log.info("An error occurred serializing certificate '{}'", id, e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR,
          STR."An error occurred serializing the certificate after it was updated.");
    }
  }

  private ApiCertificate convertOrNull(final Certificate certificate) {
    try {
      return ApiCertificate.convert(certificate);
    }
    catch (CertificateEncodingException | InvalidNameException | IOException e) {
      log.info("Failed to convert certificate {}", certificate, e);
      return null;
    }
  }

  private WebApplicationMessageException createWebException(final Status status, final String message) {
    try {
      return new WebApplicationMessageException(status, stringWriter.writeValueAsString(message),
          MediaType.APPLICATION_JSON);
    }
    catch (JsonProcessingException e) {
      log.warn("An error occurred serializing an error message", e);
      return new WebApplicationMessageException(status,
          STR."\"An error occurred serializing the error message. See nexus log.\"", MediaType.APPLICATION_JSON);
    }
  }
}