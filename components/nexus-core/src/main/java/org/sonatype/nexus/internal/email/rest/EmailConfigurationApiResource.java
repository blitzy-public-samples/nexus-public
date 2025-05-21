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
    emailManager.setConfiguration(convert(apiEmailConfiguration), apiEmailConfiguration.getPassword());
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
      var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try {
          emailManager.sendVerification(emailConfiguration, verificationAddress);
          log.debug(STR."Email verification to \{verificationAddress} completed successfully");
          return true;
        } 
        catch (Exception e) {
          log.debug(STR."Virtual thread email verification failed: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
      
      // Wait for the result with a timeout to prevent blocking indefinitely
      boolean success = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
      return new ApiEmailValidation(success);
    }
    catch (java.util.concurrent.ExecutionException e) {
      log.debug(STR."Email verification execution failed: \{e.getMessage()}", e);
      
      // Use Pattern Matching for switch to improve error handling robustness
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException && cause.getCause() instanceof EmailException emailEx) {
        return switch (emailEx.getCause()) {
          case AddressException ae -> {
            String exceptionMessage = ae.getMessage();
            throw new WebApplicationMessageException(BAD_REQUEST, STR."\"\{exceptionMessage}\"" , MediaType.APPLICATION_JSON);
          }
          case null -> new ApiEmailValidation(false, emailEx.getMessage());
          case Exception exc -> new ApiEmailValidation(false, exc.getMessage());
        };
      }
      return new ApiEmailValidation(false, STR."Email verification failed: \{e.getMessage()}");
    }
    catch (java.util.concurrent.TimeoutException e) {
      log.debug(STR."Email verification timed out: \{e.getMessage()}", e);
      return new ApiEmailValidation(false, STR."Email verification timed out after 30 seconds");
    }
    catch (InterruptedException e) {
      log.debug(STR."Email verification was interrupted: \{e.getMessage()}", e);
      Thread.currentThread().interrupt(); // Restore the interrupted status
      return new ApiEmailValidation(false, STR."Email verification was interrupted: \{e.getMessage()}");
    }
    catch (EmailException e) {
      log.debug(STR."Unable to send verification: \{e.getMessage()}", e);
      
      // Use Pattern Matching for switch to improve error handling robustness
      return switch (e.getCause()) {
        case AddressException ae -> {
          String exceptionMessage = ae.getMessage();
          throw new WebApplicationMessageException(BAD_REQUEST, '"' + exceptionMessage + '"', MediaType.APPLICATION_JSON);
        }
        case null -> new ApiEmailValidation(false, e.getMessage());
        case Exception cause -> new ApiEmailValidation(false, cause.getMessage());
      };
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
    emailConfiguration.setEnabled(apiEmailConfiguration.isEnabled());
    emailConfiguration.setHost(apiEmailConfiguration.getHost());
    emailConfiguration.setPort(apiEmailConfiguration.getPort() == null ? 0 : apiEmailConfiguration.getPort());
    emailConfiguration.setNexusTrustStoreEnabled(apiEmailConfiguration.isNexusTrustStoreEnabled());

    if (StringUtils.isNotEmpty(apiEmailConfiguration.getUsername())) {
      emailConfiguration.setUsername(apiEmailConfiguration.getUsername());
    }
    else {
      emailConfiguration.setUsername("");
    }

    emailConfiguration.setFromAddress(apiEmailConfiguration.getFromAddress());
    emailConfiguration.setSubjectPrefix(apiEmailConfiguration.getSubjectPrefix());
    emailConfiguration.setStartTlsEnabled(apiEmailConfiguration.isStartTlsEnabled());
    emailConfiguration.setStartTlsRequired(apiEmailConfiguration.isStartTlsRequired());
    emailConfiguration.setSslOnConnectEnabled(apiEmailConfiguration.isSslOnConnectEnabled());
    emailConfiguration.setSslCheckServerIdentityEnabled(apiEmailConfiguration.isSslServerIdentityCheckEnabled());
    return emailConfiguration;
  }

  private ApiEmailConfiguration convert(EmailConfiguration emailConfiguration) {
    if (emailConfiguration == null) {
      return new ApiEmailConfiguration();
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
    var apiEmailConfiguration = new ApiEmailConfiguration();
    if (properties instanceof EmailConfigProperties(
        var enabled, var host, var port, var nexusTrustStoreEnabled,
        var username, var fromAddress, var subjectPrefix,
        var startTlsEnabled, var startTlsRequired, 
        var sslOnConnectEnabled, var sslCheckServerIdentityEnabled)) {
      
      apiEmailConfiguration.setEnabled(enabled);
      apiEmailConfiguration.setHost(host);
      apiEmailConfiguration.setPort(port);
      apiEmailConfiguration.setNexusTrustStoreEnabled(nexusTrustStoreEnabled);
      apiEmailConfiguration.setUsername(username);
      apiEmailConfiguration.setPassword(null);
      apiEmailConfiguration.setFromAddress(fromAddress);
      apiEmailConfiguration.setSubjectPrefix(subjectPrefix);
      apiEmailConfiguration.setStartTlsEnabled(startTlsEnabled);
      apiEmailConfiguration.setStartTlsRequired(startTlsRequired);
      apiEmailConfiguration.setSslOnConnectEnabled(sslOnConnectEnabled);
      apiEmailConfiguration.setSslServerIdentityCheckEnabled(sslCheckServerIdentityEnabled);
    }
    
    return apiEmailConfiguration;
  }
}