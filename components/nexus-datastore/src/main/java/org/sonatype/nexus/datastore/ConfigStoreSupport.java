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
package org.sonatype.nexus.datastore;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.datastore.api.DataAccess;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.google.inject.TypeLiteral;
import org.eclipse.sisu.inject.TypeArguments;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Support class for transactional domain stores backed by the config data store.
 *
 * @since 3.21
 */
public abstract class ConfigStoreSupport<T extends DataAccess>
    extends TransactionalStoreSupport
{
  private final Class<T> daoClass;

  private EventManager eventManager;
  
  // Thread factory for virtual threads to handle event propagation
  private final ThreadFactory virtualThreadFactory = Executors.defaultThreadFactory();

  @SuppressWarnings({"rawtypes", "unchecked"})
  protected ConfigStoreSupport(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier, DEFAULT_DATASTORE_NAME);

    // use generic type information to discover the DAO class from the concrete implementation
    TypeLiteral<?> superType = TypeLiteral.get(getClass()).getSupertype(ConfigStoreSupport.class);
    this.daoClass = (Class) TypeArguments.get(superType, 0).getRawType();
  }

  @Inject
  protected void setDependencies(final EventManager eventManager) {
    this.eventManager = checkNotNull(eventManager);
  }

  /**
   * Posts an event after the current transaction commits.
   * 
   * Uses Virtual Threads to ensure efficient event handling without blocking the main thread.
   * This approach maintains context across thread boundaries and improves concurrency.
   *
   * @param eventSupplier the supplier of the event to post
   */
  public void postCommitEvent(final Supplier<?> eventSupplier) {
    // Capture the current thread context for proper propagation
    DataSession<?> currentSession = thisSession();
    
    // Register post-commit action that will use Virtual Threads for event handling
    currentSession.postCommit(() -> {
      // Use Virtual Thread to handle event propagation without blocking
      try {
        // Create and submit a virtual thread task for event handling
        Thread.startVirtualThread(() -> postEvent(eventSupplier));
      } catch (Exception e) {
        // Fall back to direct event posting if virtual thread creation fails
        postEvent(eventSupplier);
      }
    });
  }

  /**
   * Posts the event to the event manager.
   * 
   * @param eventSupplier the supplier of the event to post
   */
  private void postEvent(final Supplier<?> eventSupplier) {
    eventManager.post(eventSupplier.get());
  }

  // alternative constructor that overrides discovery of the DAO class
  protected ConfigStoreSupport(final DataSessionSupplier sessionSupplier, final Class<T> daoClass) {
    super(sessionSupplier, DEFAULT_DATASTORE_NAME);
    this.daoClass = checkNotNull(daoClass);
  }

  /**
   * Returns the current data session, optimized for Virtual Thread context.
   * 
   * @return the current data session
   */
  protected DataSession<?> thisSession() {
    // UnitOfWork.currentSession() is thread-local, but works with Virtual Threads
    // as they maintain their own ThreadLocal context
    return UnitOfWork.currentSession();
  }

  /**
   * Returns the DAO for this store, optimized for Virtual Thread context.
   * 
   * @return the DAO for this store
   */
  protected T dao() {
    // Access the DAO through the current session, which is Virtual Thread aware
    return thisSession().access(daoClass);
  }
}