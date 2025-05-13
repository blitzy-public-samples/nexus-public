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
package org.sonatype.nexus.coreui;

import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousManager;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST resource for managing anonymous access settings.
 * 
 * @since 3.19
 */
@Named
@Singleton
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path(AnonymousSettingsResource.RESOURCE_PATH)
public class AnonymousSettingsResource
    extends ComponentSupport
    implements Resource
{
  static final String RESOURCE_PATH = "internal/ui/anonymous-settings";

  private final AnonymousManager anonymousManager;
  
  /**
   * Virtual thread executor for handling I/O operations asynchronously.
   * Java 21 Virtual Threads provide lightweight concurrency with minimal overhead,
   * allowing for efficient handling of many concurrent requests.
   */
  private final var virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public AnonymousSettingsResource(final AnonymousManager anonymousManager) {
    this.anonymousManager = checkNotNull(anonymousManager);
  }

  /**
   * Retrieves the current anonymous access configuration.
   * 
   * @return The anonymous settings data transfer object
   */
  @GET
  @RequiresPermissions("nexus:settings:read")
  public void read(@Suspended final AsyncResponse response) {
    // Use virtual threads for I/O operations to improve scalability
    virtualExecutor.submit(() -> {
      try {
        AnonymousConfiguration config = anonymousManager.getConfiguration();
        log.debug(STR."Retrieved anonymous configuration: enabled=\{config.isEnabled()}, userId=\{config.getUserId()}, realm=\{config.getRealmName()}");
        
        // Create AnonymousSettingsXO record with values from configuration
        var result = new AnonymousSettingsXO(
            config.isEnabled(),
            config.getUserId(),
            config.getRealmName()
        );
        
        response.resume(result);
      } catch (Exception e) {
        log.error(STR."Error retrieving anonymous settings: \{e.getMessage()}", e);
        response.resume(e);
      }
    });
  }

  /**
   * Updates the anonymous access configuration.
   * 
   * @param anonymousXO The anonymous settings to apply
   */
  @PUT
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  public void update(@NotNull @Valid final AnonymousSettingsXO anonymousXO, @Suspended final AsyncResponse response) {
    // Use virtual threads for I/O operations to improve scalability
    virtualExecutor.submit(() -> {
      try {
        // Use record pattern matching to extract fields from the record
        if (anonymousXO instanceof AnonymousSettingsXO(var enabled, var userId, var realmName)) {
          log.debug(STR."Updating anonymous configuration: enabled=\{enabled}, userId=\{userId}, realm=\{realmName}");
          
          AnonymousConfiguration configuration = anonymousManager.newConfiguration();
          configuration.setEnabled(enabled);
          configuration.setRealmName(realmName);
          configuration.setUserId(userId);
          
          anonymousManager.setConfiguration(configuration);
          log.info(STR."Anonymous access settings updated: enabled=\{enabled}");
          
          response.resume("OK");
        } else {
          // This should never happen with record pattern matching, but included for completeness
          throw new IllegalArgumentException("Invalid anonymous settings format");
        }
      } catch (Exception e) {
        log.error(STR."Error updating anonymous settings: \{e.getMessage()}", e);
        response.resume(e);
      }
    });
  }
}
