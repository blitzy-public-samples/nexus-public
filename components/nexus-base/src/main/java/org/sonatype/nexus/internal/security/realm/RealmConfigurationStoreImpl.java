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
package org.sonatype.nexus.internal.security.realm;

import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.security.realm.RealmConfiguration;
import org.sonatype.nexus.security.realm.RealmConfigurationStore;
import org.sonatype.nexus.thread.internal.MDCUtils;
import org.sonatype.nexus.transaction.Transactional;

/**
 * MyBatis {@link RealmConfigurationStore} implementation.
 *
 * @since 3.21
 */
@Named("mybatis")
@Singleton
public class RealmConfigurationStoreImpl
    extends ConfigStoreSupport<RealmConfigurationDAO>
    implements RealmConfigurationStore
{
  @Inject
  public RealmConfigurationStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier);
  }

  @Override
  public RealmConfiguration newEntity() {
    return new RealmConfigurationData();
  }

  @Transactional
  @Override
  public RealmConfiguration load() {
    if (log.isDebugEnabled()) {
      log.debug(STR."Loading realm configuration from store");
    }
    return dao().get().orElse(null);
  }

  @Transactional
  @Override
  public void save(final RealmConfiguration configuration) {
    if (log.isDebugEnabled()) {
      log.debug(STR."Saving realm configuration to store: \{configuration}");
    }
    // Optimize event dispatch for Virtual Thread context
    postCommitEvent(() -> createEventWithVirtualThreadContext(configuration));
    dao().set((RealmConfigurationData) configuration);
  }
  
  /**
   * Creates a RealmConfigurationChangedEvent with proper Virtual Thread context propagation.
   * This ensures MDC context and transaction boundaries are preserved when the event is processed.
   *
   * @param configuration The realm configuration that was changed
   * @return A new RealmConfigurationChangedEvent with the proper context
   */
  private RealmConfigurationChangedEvent createEventWithVirtualThreadContext(final RealmConfiguration configuration) {
    // Capture the current MDC context for propagation to event handlers
    // This is especially important for Virtual Threads which may be scheduled on different carrier threads
    return MDCUtils.isVirtualThread() 
        ? MDCUtils.withMdcContext(() -> new RealmConfigurationChangedEvent((RealmConfigurationData) configuration)).run()
        : new RealmConfigurationChangedEvent((RealmConfigurationData) configuration);
  }
}