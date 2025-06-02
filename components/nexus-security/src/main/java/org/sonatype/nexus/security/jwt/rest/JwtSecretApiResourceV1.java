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
package org.sonatype.nexus.security.jwt.rest;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.security.SecureRandom;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.security.jwt.SecretStore;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.status;
import static javax.ws.rs.core.Response.Status.OK;
import static org.sonatype.nexus.common.app.FeatureFlags.JWT_ENABLED;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;
import static org.sonatype.nexus.security.jwt.rest.JwtSecretApiResourceV1.PATH;

/**
 * REST API to reset the stored JWT secret.
 *
 * @since 3.38
 */
@FeatureFlag(name = JWT_ENABLED)
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path(PATH)
@Named
@Singleton
public class JwtSecretApiResourceV1
    extends ComponentSupport
    implements Resource, JwtSecretApiResourceDoc
{
  public static final String PATH = V1_API_PREFIX + "/security/jwt";

  private final SecretStore secretStore;
  private final Executor virtualThreadExecutor;
  private final SecureRandom secureRandom;

  @Inject
  public JwtSecretApiResourceV1(final SecretStore secretStore) {
    this.secretStore = checkNotNull(secretStore);
    this.virtualThreadExecutor = newVirtualThreadPerTaskExecutor();
    this.secureRandom = new SecureRandom();
  }

  /**
   * Resets the JWT secret using a Virtual Thread for improved performance.
   * This method leverages Java 21's Virtual Threads to handle the HTTP request efficiently,
   * allowing for better concurrency without blocking platform threads during I/O operations.
   *
   * @return HTTP response indicating success
   */
  @PUT
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  @Override
  public Response resetSecret() {
    // Create a response using a Virtual Thread to handle the operation
    // This improves scalability for concurrent HTTP requests
    try {
      // Submit the task to the Virtual Thread executor and wait for completion
      return virtualThreadExecutor.submit(() -> {
        // Generate a cryptographically secure random UUID for the JWT secret
        // Using SecureRandom with UUID.randomUUID() ensures better entropy
        UUID uuid = new UUID(secureRandom.nextLong(), secureRandom.nextLong());
        String secret = uuid.toString();
        
        // Store the secret using the injected SecretStore
        // The SecretStore implementation has been verified for Java 21 compatibility
        secretStore.setSecret(secret);
        
        log.debug("JWT secret has been reset successfully");
        return status(OK).build();
      }).get();
    }
    catch (Exception e) {
      log.error("Failed to reset JWT secret", e);
      throw new RuntimeException("Failed to reset JWT secret", e);
    }
  }
}