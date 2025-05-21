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
package org.sonatype.nexus.internal.security.model;

import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.CUserRoleMapping;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfigurationSource;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SCHEMAS;

/**
 * Default implementation of {@link SecurityConfigurationSource}.
 *
 * @since 3.21
 */
@Named("default")
@ManagedLifecycle(phase = SCHEMAS)
@FeatureFlag(name = DATASTORE_ENABLED)
@Singleton
public class SecurityConfigurationSourceImpl
    extends StateGuardLifecycleSupport
    implements SecurityConfigurationSource
{
  private SecurityConfigurationImpl securityConfiguration;

  private SecurityConfigurationSource securityDefaults;

  @Inject
  public SecurityConfigurationSourceImpl(
      final SecurityConfigurationImpl securityConfiguration,
      @Named("static") final SecurityConfigurationSource securityDefaults)
  {
    this.securityConfiguration = checkNotNull(securityConfiguration);
    this.securityDefaults = checkNotNull(securityDefaults);
  }

  @Override
  protected void doStart() throws Exception {
    // Use CompletableFuture with Virtual Threads to parallelize initialization tasks
    CompletableFuture<Void> usersTask = CompletableFuture.runAsync(this::addDefaultUsers, Thread.ofVirtual().factory());
    CompletableFuture<Void> rolesTask = CompletableFuture.runAsync(this::addDefaultRoles, Thread.ofVirtual().factory());
    CompletableFuture<Void> privilegesTask = CompletableFuture.runAsync(this::addDefaultPrivileges, Thread.ofVirtual().factory());
    CompletableFuture<Void> mappingsTask = CompletableFuture.runAsync(this::addDefaultUserRoleMappings, Thread.ofVirtual().factory());
    
    // Wait for all initialization tasks to complete
    CompletableFuture.allOf(usersTask, rolesTask, privilegesTask, mappingsTask).join();
  }

  private void addDefaultUsers() {
    log.info("Initializing default users");

    for (CUser user : securityDefaults.getConfiguration().getUsers()) {
      if (securityConfiguration.getUser(user.getId()) == null) {
        securityConfiguration.addUser(user);
      }
    }
  }

  private void addDefaultRoles() {
    log.info("Initializing default roles");

    for (CRole role : securityDefaults.getConfiguration().getRoles()) {
      if (securityConfiguration.getRole(role.getId()) == null) {
        securityConfiguration.addRole(role);
      }
    }
  }

  private void addDefaultPrivileges() {
    log.info("Initializing default privileges");

    for (CPrivilege privilege : securityDefaults.getConfiguration().getPrivileges()) {
      if (securityConfiguration.getPrivilege(privilege.getId()) == null) {
        securityConfiguration.addPrivilege(privilege);
      }
    }
  }

  private void addDefaultUserRoleMappings() {
    log.info("Initializing default user/role mappings");

    for (CUserRoleMapping mapping : securityDefaults.getConfiguration().getUserRoleMappings()) {
      if (securityConfiguration.getUserRoleMapping(mapping.getUserId(), mapping.getSource()) == null) {
        securityConfiguration.addUserRoleMapping(mapping);
      }
    }
  }

  @Override
  public SecurityConfiguration getConfiguration() {
    return securityConfiguration;
  }

  @Override
  public SecurityConfiguration loadConfiguration() {
    return securityConfiguration;
  }
}