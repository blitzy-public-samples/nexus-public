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
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousManager;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Anonymous Security Settings {@link DirectComponent}.
 * 
 * Updated for Java 21 compatibility with Virtual Threads for improved concurrency
 * and pattern matching for type-safe data handling.
 */
@Named
@Singleton
@DirectAction(action = "coreui_AnonymousSettings")
public class AnonymousSettingsComponent
    extends DirectComponentSupport
{
  private final AnonymousManager anonymousManager;

  @Inject
  public AnonymousSettingsComponent(final AnonymousManager anonymousManager) {
    this.anonymousManager = checkNotNull(anonymousManager);
  }

  /**
   * Retrieves anonymous security settings.
   *
   * @return anonymous security settings
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:settings:read")
  public AnonymousSettingsXO read() {
    // Get configuration from the manager
    AnonymousConfiguration config = anonymousManager.getConfiguration();
    
    // Create a new record instance with the configuration values
    // Using Java 21 record pattern for immutable data transfer
    return new AnonymousSettingsXO(
        config.isEnabled(),
        config.getUserId(),
        config.getRealmName()
    );
  }

  /**
   * Updates anonymous security settings.
   *
   * @param anonymousXO the settings to update
   * @return updated anonymous security settings
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  public AnonymousSettingsXO update(@NotNull @Valid final AnonymousSettingsXO anonymousXO) {
    // Using Java 21 pattern matching for improved type safety and readability
    // This deconstructs the record into its components in a type-safe manner
    if (anonymousXO instanceof AnonymousSettingsXO(Boolean enabled, String userId, String realmName)) {
      // Create and configure a new configuration instance
      AnonymousConfiguration configuration = anonymousManager.newConfiguration();
      configuration.setEnabled(enabled);
      configuration.setRealmName(realmName);
      configuration.setUserId(userId);
      
      // Use a virtual thread for this I/O-bound operation
      // Virtual threads are lightweight and perfect for I/O operations
      // We create a new virtual thread for each task rather than maintaining an executor
      try {
        Future<?> future = Executors.newVirtualThreadPerTaskExecutor()
            .submit(() -> anonymousManager.setConfiguration(configuration));
        
        // Wait for the operation to complete
        future.get();
      }
      catch (Exception e) {
        log.error("Failed to update anonymous configuration: {}", e.getMessage(), e);
        throw new RuntimeException("Failed to update anonymous configuration", e);
      }
    }
    
    // Return the updated configuration
    return read();
  }
}
