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

import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import jakarta.ws.rs.core.Response;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.validation.Validate;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.mail.EmailException;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.*;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;

@Produces(APPLICATION_JSON)
public class EmailConfigurationApiResource
    implements Resource, EmailConfigurationApiResourceDoc
{
  private static final Logger log = LoggerFactory.getLogger(EmailConfigurationApiResource.class);

  private final EmailManager emailManager;

  @Inject
  public EmailConfigurationApiResource(EmailManager emailManager) {
    this.emailManager = emailManager;
  }

  @GET
  @RequiresPermissions("nexus:settings:read")
  public ApiEmailConfiguration getEmailConfiguration() {
    return convert(emailManager.getConfiguration());
  }

  @PUT
  @RequiresAuthentication
  @Validate
  @RequiresPermissions("nexus:settings:update")
  public void setEmailConfiguration(@NotNull @Valid final ApiEmailConfiguration apiEmailConfiguration) {
    emailManager.setConfiguration(convert(apiEmailConfiguration), apiEmailConfiguration.password());
  }

  @POST
  @Path("/verify")
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  public ApiEmailValidation testEmailConfiguration(@NotNull String verificationAddress)
  {
    EmailConfiguration emailConfiguration = emailManager.getConfiguration();

    if (emailConfiguration == null) {
      return new ApiEmailValidation(false, STR."Email Settings are not yet configured for verification to \{verificationAddress}");
    }

    try {
      // Use Virtual Thread for I/O-bound email verification operation
      log.debug(STR."Starting email verification to \{verificationAddress} using virtual thread");
      
      // Create a CompletableFuture that will be completed by a virtual thread
      var future = CompletableFuture.supplyAsync(() -> {
        try {
          emailManager.sendVerification(emailConfiguration, verificationAddress);
          log.debug(STR."Email verification to \{verificationAddress} completed successfully");
          return true;
        } 
        catch (Exception e) {
          log.debug(STR."Virtual thread email verification failed: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
      
      // Wait for the result with a timeout to prevent blocking indefinitely
      boolean success = future.get(30, TimeUnit.SECONDS);
      return new ApiEmailValidation(success);
    }
    catch (ExecutionException e) {
      log.debug(STR."Email verification execution failed: \{e.getMessage()}", e);

      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException && cause.getCause() instanceof EmailException emailEx) {
        Throwable emailCause = emailEx.getCause();
        if (emailCause instanceof AddressException ae) {
          String exceptionMessage = ae.getMessage();
          throw new WebApplicationMessageException(Response.Status.BAD_REQUEST, STR."\"\{exceptionMessage}\"", APPLICATION_JSON);
        } else if (emailCause == null) {
          return new ApiEmailValidation(false, emailEx.getMessage());
        } else {
          return new ApiEmailValidation(false, emailCause.getMessage());
        }
      }
      return new ApiEmailValidation(false, STR."Email verification failed: \{e.getMessage()}");

    }
    catch (TimeoutException e) {
      log.debug(STR."Email verification timed out: \{e.getMessage()}", e);
      return new ApiEmailValidation(false, STR."Email verification timed out after 30 seconds");
    }
    catch (InterruptedException e) {
      log.debug(STR."Email verification was interrupted: \{e.getMessage()}", e);
      Thread.currentThread().interrupt(); // Restore the interrupted status
      return new ApiEmailValidation(false, STR."Email verification was interrupted: \{e.getMessage()}");
    }
  }

  @DELETE
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  public void deleteEmailConfiguration() {
    emailManager.setConfiguration(emailManager.newConfiguration(), Strings2.EMPTY);
  }

  private EmailConfiguration convert(ApiEmailConfiguration apiEmailConfiguration) {
    EmailConfiguration emailConfiguration = emailManager.newConfiguration();
    emailConfiguration.setEnabled(apiEmailConfiguration.enabled());
    emailConfiguration.setHost(apiEmailConfiguration.host());
    emailConfiguration.setPort(apiEmailConfiguration.port() == null ? 0 : apiEmailConfiguration.port());
    emailConfiguration.setNexusTrustStoreEnabled(apiEmailConfiguration.nexusTrustStoreEnabled());

    if (StringUtils.isNotEmpty(apiEmailConfiguration.username())) {
      emailConfiguration.setUsername(apiEmailConfiguration.username());
    }
    else {
      emailConfiguration.setUsername("");
    }

    emailConfiguration.setFromAddress(apiEmailConfiguration.fromAddress());
    emailConfiguration.setSubjectPrefix(apiEmailConfiguration.subjectPrefix());
    emailConfiguration.setStartTlsEnabled(apiEmailConfiguration.startTlsEnabled());
    emailConfiguration.setStartTlsRequired(apiEmailConfiguration.startTlsRequired());
    emailConfiguration.setSslOnConnectEnabled(apiEmailConfiguration.sslOnConnectEnabled());
    emailConfiguration.setSslCheckServerIdentityEnabled(apiEmailConfiguration.sslServerIdentityCheckEnabled());
    return emailConfiguration;
  }

  private ApiEmailConfiguration convert(EmailConfiguration emailConfiguration) {
    if (emailConfiguration == null) {
      String password = Objects.nonNull(emailConfiguration.getPassword()) ? String.valueOf(
              emailConfiguration.getPassword().decrypt()) : Strings2.EMPTY;
      return new ApiEmailConfiguration(
              emailConfiguration.isEnabled(),
              emailConfiguration.getHost(),
              emailConfiguration.getPort(),
              password,
              emailConfiguration.getUsername(),
              emailConfiguration.getFromAddress(),
              emailConfiguration.getSubjectPrefix(),
              emailConfiguration.isStartTlsEnabled(),
              emailConfiguration.isStartTlsRequired(),
              emailConfiguration.isSslOnConnectEnabled(),
              emailConfiguration.isSslCheckServerIdentityEnabled(),
              emailConfiguration.isNexusTrustStoreEnabled()
      );
    }

    // Use Record Pattern for improved data handling with Java 21
    // Extract all properties at once using pattern matching
    record EmailConfigProperties(
        boolean enabled, String host, int port, boolean nexusTrustStoreEnabled,
        String username, String fromAddress, String subjectPrefix,
        boolean startTlsEnabled, boolean startTlsRequired, 
        boolean sslOnConnectEnabled, boolean sslCheckServerIdentityEnabled) {}
    
    var properties = new EmailConfigProperties(
        emailConfiguration.isEnabled(),
        emailConfiguration.getHost(),
        emailConfiguration.getPort(),
        emailConfiguration.isNexusTrustStoreEnabled(),
        emailConfiguration.getUsername(),
        emailConfiguration.getFromAddress(),
        emailConfiguration.getSubjectPrefix(),
        emailConfiguration.isStartTlsEnabled(),
        emailConfiguration.isStartTlsRequired(),
        emailConfiguration.isSslOnConnectEnabled(),
        emailConfiguration.isSslCheckServerIdentityEnabled()
    );
    
    // Use pattern matching to extract values
    if (properties instanceof EmailConfigProperties(
        var enabled, var host, var port, var nexusTrustStoreEnabled,
        var username, var fromAddress, var subjectPrefix,
        var startTlsEnabled, var startTlsRequired, 
        var sslOnConnectEnabled, var sslCheckServerIdentityEnabled)) {

      return new ApiEmailConfiguration(
              enabled,
              host,
              port,
              null, // password
              username,
              fromAddress,
              subjectPrefix,
              startTlsEnabled,
              startTlsRequired,
              sslOnConnectEnabled,
              sslCheckServerIdentityEnabled,
              nexusTrustStoreEnabled
      );

    }
    return null;
  }
}