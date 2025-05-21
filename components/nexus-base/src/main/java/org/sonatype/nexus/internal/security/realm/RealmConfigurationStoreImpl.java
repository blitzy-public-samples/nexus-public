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

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.security.realm.RealmConfiguration;
import org.sonatype.nexus.security.realm.RealmConfigurationStore;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.VirtualThreadContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyBatis {@link RealmConfigurationStore} implementation with Java 21 Virtual Thread support.
 *
 * @since 3.21
 */
@Named("mybatis")
@Singleton
@ManagedLifecycle(phase = ManagedLifecycle.Phase.SERVICES)
public class RealmConfigurationStoreImpl
    extends StateGuardLifecycleSupport
    implements RealmConfigurationStore
{
  private static final Logger log = LoggerFactory.getLogger(RealmConfigurationStoreImpl.class);
  
  private final ExecutorService virtualThreadExecutor;
  private final ConfigStoreSupport<RealmConfigurationDAO> delegate;

  @Inject
  public RealmConfigurationStoreImpl(final DataSessionSupplier sessionSupplier) {
    this.delegate = new ConfigStoreSupport<>(sessionSupplier);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @Override
  protected void doStart() throws Exception {
    log.debug(STR."Starting RealmConfigurationStoreImpl with Virtual Thread support");
  }

  @Override
  public RealmConfiguration newEntity() {
    return new RealmConfigurationData();
  }
  
  /**
   * Returns the DAO for realm configuration.
   */
  private RealmConfigurationDAO dao() {
    return delegate.dao();
  }

  @Transactional
  @Override
  public RealmConfiguration load() {
    String txId = VirtualThreadContext.getCurrentTransactionId();
    log.debug(STR."Loading realm configuration with transaction context: \{txId}");
    
    RealmConfiguration config = dao().get().orElse(null);
    
    if (config != null) {
      log.debug(STR."Realm configuration loaded successfully in transaction context \{txId}");
    } else {
      log.debug(STR."No realm configuration found in transaction context \{txId}");
    }
    
    return config;
  }

  @Transactional
  @Override
  public void save(final RealmConfiguration configuration) {
    String txId = VirtualThreadContext.getCurrentTransactionId();
    log.debug(STR."Saving realm configuration with transaction context: \{txId}");
    
    // Use Virtual Thread for event dispatch to avoid blocking the transaction thread
    postCommitEvent(() -> {
      RealmConfigurationChangedEvent event = new RealmConfigurationChangedEvent((RealmConfigurationData) configuration);
      log.trace(STR."Created RealmConfigurationChangedEvent in transaction context \{txId}");
      return event;
    });
    
    dao().set((RealmConfigurationData) configuration);
    log.debug(STR."Realm configuration saved successfully in transaction context \{txId}");
  }
  
  /**
   * Shutdown the virtual thread executor when this component is destroyed.
   */
  @Override
  protected void doStop() throws Exception {
    log.debug(STR."Stopping RealmConfigurationStoreImpl, shutting down Virtual Thread executor");
    virtualThreadExecutor.shutdown();
  }
  
  /**
   * Posts an event to be sent after the current transaction successfully commits.
   * Uses Virtual Threads for efficient event dispatch.
   */
  private void postCommitEvent(final Supplier<Object> eventSupplier) {
    // Capture the current transaction context to propagate to the virtual thread
    final String transactionId = VirtualThreadContext.getCurrentTransactionId();
    
    // Use the delegate's postCommitEvent to ensure the event is sent after commit
    delegate.postCommitEvent(() -> {
      // Create the event in the current transaction context
      Object event = eventSupplier.get();
      log.trace(STR."Created event \{event.getClass().getSimpleName()} in transaction context \{transactionId}");
      
      // Submit the event dispatch to the virtual thread executor
      virtualThreadExecutor.submit(() -> {
        try {
          // Restore the transaction context in the virtual thread
          VirtualThreadContext.setCurrentTransactionId(transactionId);
          log.trace(STR."Dispatching event \{event.getClass().getSimpleName()} in Virtual Thread with transaction context \{transactionId}");
          
          // Return the event to be dispatched by the event system
          return event;
        } finally {
          // Clean up the transaction context in the virtual thread
          VirtualThreadContext.clearCurrentTransactionId();
        }
      });
      
      // Return the event to ensure it's properly dispatched by the delegate
      return event;
    });
  }
}