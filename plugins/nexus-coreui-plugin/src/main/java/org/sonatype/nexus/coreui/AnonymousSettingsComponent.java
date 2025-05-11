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
import java.util.concurrent.ExecutorService;
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
 * Updated for Java 21 to use Virtual Threads for I/O operations and leverage record patterns
 * for improved validation and data handling.
 */
@Named
@Singleton
@DirectAction(action = "coreui_AnonymousSettings")
public class AnonymousSettingsComponent
    extends DirectComponentSupport
{
  private final AnonymousManager anonymousManager;
  
  // Virtual thread executor for I/O-bound operations
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public AnonymousSettingsComponent(final AnonymousManager anonymousManager) {
    this.anonymousManager = checkNotNull(anonymousManager);
    // Create a virtual thread executor using Java 21's Executors.newVirtualThreadPerTaskExecutor()
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Retrieves anonymous security settings.
   *
   * @return anonymous security settings as an immutable record
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:settings:read")
  public AnonymousSettingsXO read() {
    AnonymousConfiguration config = anonymousManager.getConfiguration();
    // Create a new record instance with the configuration values
    return new AnonymousSettingsXO(config.isEnabled(), config.getUserId(), config.getRealmName());
  }

  /**
   * Updates anonymous security settings using Virtual Threads for improved performance.
   * Uses pattern matching for validation and record deconstruction for cleaner code.
   *
   * @param anonymousXO the anonymous settings to update (validated by Bean Validation)
   * @return updated anonymous security settings
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  public AnonymousSettingsXO update(@NotNull @Valid final AnonymousSettingsXO anonymousXO) {
    try {
      // Use pattern matching to deconstruct the record
      if (anonymousXO instanceof AnonymousSettingsXO(Boolean enabled, String userId, String realmName)) {
        // Submit the update task to the virtual thread executor for better I/O performance
        return virtualThreadExecutor.submit(() -> {
          AnonymousConfiguration configuration = anonymousManager.newConfiguration();
          configuration.setEnabled(enabled);
          configuration.setRealmName(realmName);
          configuration.setUserId(userId);
          anonymousManager.setConfiguration(configuration);
          return read();
        }).get(); // Wait for the result
      } else {
        // This should never happen with a valid record, but added for completeness
        throw new IllegalArgumentException("Invalid anonymous settings format");
      }
    } catch (Exception e) {
      log.error("Failed to update anonymous settings", e);
      throw new RuntimeException("Failed to update anonymous settings", e);
    }
  }
}