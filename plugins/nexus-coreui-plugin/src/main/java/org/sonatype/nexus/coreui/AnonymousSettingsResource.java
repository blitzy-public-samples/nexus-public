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

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousManager;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static java.util.Objects.requireNonNull;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

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

  @Inject
  public AnonymousSettingsResource(final AnonymousManager anonymousManager) {
    this.anonymousManager = requireNonNull(anonymousManager, "anonymousManager cannot be null");
  }

  /**
   * Retrieves the current anonymous access settings.
   *
   * @return the current anonymous settings as an {@link AnonymousSettingsXO} record
   */
  @GET
  @RequiresPermissions("nexus:settings:read")
  public AnonymousSettingsXO read() {
    AnonymousConfiguration config = anonymousManager.getConfiguration();
    log.debug(STR."Reading anonymous configuration: enabled=\{config.isEnabled()}, userId=\{config.getUserId()}, realm=\{config.getRealmName()}");
    
    // Create a new record instance with the current configuration values
    return new AnonymousSettingsXO(
        config.isEnabled(),
        config.getUserId(),
        config.getRealmName()
    );
  }

  /**
   * Updates the anonymous access settings.
   *
   * @param anonymousXO the new anonymous settings to apply
   */
  @PUT
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  public void update(@NotNull @Valid final AnonymousSettingsXO anonymousXO) {
    log.debug(STR."Updating anonymous configuration: enabled=\{anonymousXO.enabled()}, userId=\{anonymousXO.userId()}, realm=\{anonymousXO.realmName()}");
    
    AnonymousConfiguration configuration = anonymousManager.newConfiguration();
    configuration.setEnabled(anonymousXO.enabled());
    configuration.setRealmName(anonymousXO.realmName());
    configuration.setUserId(anonymousXO.userId());
    anonymousManager.setConfiguration(configuration);
  }
}