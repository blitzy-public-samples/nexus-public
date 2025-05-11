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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.security.realm.RealmManager;
import org.sonatype.nexus.validation.Validate;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.google.inject.Key;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.realm.Realm;
import org.eclipse.sisu.inject.BeanLocator;

import java.util.List;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.StreamSupport.stream;

/**
 * Realm Security Settings {@link DirectComponentSupport}.
 * 
 * This component manages security realm settings, leveraging Java 21 features like
 * Virtual Threads for improved concurrency and Pattern Matching for type checking.
 * 
 * @since 3.0
 */
@Named
@Singleton
@DirectAction(action = "coreui_RealmSettings")
public class RealmSettingsComponent
    extends DirectComponentSupport
{
  private final RealmManager realmManager;

  private final BeanLocator beanLocator;

  @Inject
  public RealmSettingsComponent(final RealmManager realmManager, final BeanLocator beanLocator) {
    this.realmManager = checkNotNull(realmManager);
    this.beanLocator = checkNotNull(beanLocator);
  }

  /**
   * Retrieves security realm settings.
   *
   * @return security realm settings
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:settings:read")
  public RealmSettingsXO read() {
    // Use Virtual Thread for potentially blocking operations
    // Reading configuration might involve database access in clustered environments
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
        // Create a new RealmSettingsXO record with the configured realm IDs
        new RealmSettingsXO(realmManager.getConfiguredRealmIds())
    ).join(); // Join to get the result from the Virtual Thread
  }

  /**
   * Retrieves realm types.
   *
   * @return a list of realm types
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:settings:read")
  public List<ReferenceXO> readRealmTypes() {
    // Use Java 21 features for more concise stream operations with Virtual Thread execution for potentially blocking operations
    // This method involves service discovery which could be I/O bound in large installations
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
        stream(beanLocator.locate(Key.get(Realm.class, Named.class)).spliterator(), false)
            .map(entry -> {
              // Use pattern matching for instanceof to simplify the code
              if (entry.getKey() instanceof Named named) {
                return new ReferenceXO(named.value(), entry.getDescription());
              }
              // Fallback case (should not happen with properly configured beans)
              return new ReferenceXO(entry.getKey().toString(), entry.getDescription());
            })
            .sorted(Comparator.comparing(ReferenceXO::name, String::compareToIgnoreCase))
            .collect(Collectors.toList())
    ).join(); // Join to get the result from the Virtual Thread
  }

  /**
   * Updates security realm settings.
   *
   * @return updated security realm settings
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  @Validate
  public RealmSettingsXO update(@NotNull @Valid final RealmSettingsXO realmSettingsXO) {
    // Use Virtual Thread for potentially blocking operations
    // Configuration updates might involve database operations or distributed coordination in clustered environments
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      // Update the configured realm IDs
      realmManager.setConfiguredRealmIds(realmSettingsXO.realms());
      // Return the current settings
      return read();
    }).join(); // Join to get the result from the Virtual Thread
  }
}