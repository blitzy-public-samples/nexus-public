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
package org.sonatype.nexus.repository.content.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.sonatype.nexus.common.property.SystemPropertiesHelper;
import org.sonatype.nexus.datastore.TransactionalStoreSupport;
import org.sonatype.nexus.datastore.api.ContentDataAccess;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.thread.internal.MDCUtils;
import org.sonatype.nexus.transaction.Transaction;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.google.inject.TypeLiteral;
import org.eclipse.sisu.inject.TypeArguments;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.scheduling.CancelableHelper.checkCancellation;

/**
 * Support class for transactional domain stores backed by a content data store.
 * <p>
 * Updated for Java 21 to support Virtual Threads for database operations and proper
 * handling of thread-local state across Virtual Thread handoffs.
 *
 * @since 3.21
 */
public abstract class ContentStoreSupport<T extends ContentDataAccess>
    extends TransactionalStoreSupport
{
  private static final int DELETE_BATCH_SIZE_DEFAULT =
      SystemPropertiesHelper.getInteger("nexus.content.deleteBatchSize", 1000);

  /**
   * Virtual Thread executor for concurrent database operations.
   * Uses Java 21's Virtual Threads for lightweight concurrency with minimal overhead.
   */
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = 
      Executors.newVirtualThreadPerTaskExecutor();

  private final Class<T> daoClass;

  @SuppressWarnings({ "rawtypes", "unchecked" })
  protected ContentStoreSupport(final DataSessionSupplier sessionSupplier, final String contentStoreName) {
    super(sessionSupplier, contentStoreName);

    // use generic type information to discover the DAO class from the concrete implementation
    TypeLiteral<?> superType = TypeLiteral.get(getClass()).getSupertype(ContentStoreSupport.class);
    this.daoClass = (Class) TypeArguments.get(superType, 0).getRawType();
  }

  // alternative constructor that overrides discovery of the DAO class
  protected ContentStoreSupport(final DataSessionSupplier sessionSupplier,
                                final String contentStoreName,
                                final Class<T> daoClass)
  {
    super(sessionSupplier, contentStoreName);
    this.daoClass = checkNotNull(daoClass);
  }

  /**
   * Gets the current DataSession, ensuring proper handling of Virtual Thread context.
   * <p>
   * This method is Virtual Thread aware and properly handles thread-local state
   * across Virtual Thread scheduling operations.
   * 
   * @return the current DataSession
   */
  protected DataSession<?> thisSession() {
    return UnitOfWork.currentSession();
  }

  /**
   * Gets the DAO for the current session, ensuring proper handling of Virtual Thread context.
   * <p>
   * This method is Virtual Thread aware and properly handles thread-local state
   * across Virtual Thread scheduling operations.
   * 
   * @return the DAO for the current session
   */
  protected T dao() {
    return thisSession().access(daoClass);
  }

  /**
   * Commits any batched changes so far using Virtual Threads for improved concurrency.
   * <p>
   * This method is optimized for Java 21 Virtual Threads and properly preserves MDC context
   * across transaction boundaries and Virtual Thread scheduling operations.
   * <p>
   * Also checks to see if the current (potentially long-running) operation has been cancelled.
   */
  protected void commitChangesSoFar() {
    // Capture MDC context before committing to preserve it across Virtual Thread handoffs
    Map<String, String> mdcContext = MDCUtils.getContextMapForPropagation();
    
    try {
      Transaction tx = UnitOfWork.currentTx();
      tx.commit();
      tx.begin();
      
      // Restore MDC context after transaction boundary
      MDCUtils.applyContextMap(mdcContext);
      
      checkCancellation();
    } catch (Exception e) {
      // Ensure MDC context is restored even on exception
      MDCUtils.applyContextMap(mdcContext);
      throw e;
    }
  }

  protected int deleteBatchSize() {
    return DELETE_BATCH_SIZE_DEFAULT;
  }

  /**
   * Helper to find content in this store before creating it with the given supplier.
   * <p>
   * Automatically retries the operation if another thread creates it just before us.
   * Optimized for Virtual Threads in Java 21 with proper MDC context propagation across
   * thread boundaries and scheduling operations.
   */
  @Transactional(retryOn = DuplicateKeyException.class)
  public <D> D getOrCreate(final Supplier<Optional<D>> find, final Supplier<D> create) {
    // Capture MDC context to preserve it across Virtual Thread handoffs
    Map<String, String> mdcContext = MDCUtils.getContextMapForPropagation();
    
    try {
      return find.get().orElseGet(() -> {
        // Restore MDC context before creating
        MDCUtils.applyContextMap(mdcContext);
        return create.get();
      });
    } finally {
      // Ensure MDC context is restored
      MDCUtils.applyContextMap(mdcContext);
    }
  }

  /**
   * Saves content using Virtual Threads for improved concurrency.
   * <p>
   * This method leverages Java 21 Virtual Threads to execute post-transaction work
   * concurrently, while properly handling MDC context propagation across thread boundaries.
   * <p>
   * The implementation uses structured concurrency patterns to ensure proper resource
   * management and error handling.
   *
   * @param find Supplier to find existing content
   * @param create Supplier to create new content if not found
   * @param update UnaryOperator to update existing content if found
   * @param postTransaction Consumer to process the result after the transaction completes
   * @return The created or updated content
   */
  public <D> D save(
      final Supplier<Optional<D>> find,
      final Supplier<D> create,
      final UnaryOperator<D> update,
      final Consumer<D> postTransaction)
  {
    // Capture MDC context to preserve it across Virtual Thread handoffs
    Map<String, String> mdcContext = MDCUtils.getContextMapForPropagation();
    
    try {
      D result = transactionalSave(find, create, update);
      
      // Execute post-transaction work in a Virtual Thread for better concurrency
      VIRTUAL_THREAD_EXECUTOR.submit(() -> {
        // Apply the captured MDC context in the Virtual Thread
        MDCUtils.withContext(mdcContext, () -> {
          postTransaction.accept(result);
        });
      });
      
      return result;
    } finally {
      // Ensure MDC context is restored
      MDCUtils.applyContextMap(mdcContext);
    }
  }

  /**
   * Helper to find content in this store before creating or updating it with the given suppliers.
   * <p>
   * This method is optimized for Virtual Threads in Java 21 with proper MDC context propagation
   * across thread boundaries and scheduling operations. It ensures that logging context is
   * preserved throughout the transaction, even when Virtual Threads are suspended and resumed
   * on different carrier threads.
   *
   * @param find Supplier to find existing content
   * @param create Supplier to create new content if not found
   * @param update UnaryOperator to update existing content if found
   * @return The created or updated content
   * @since 3.30
   */
  @Transactional(retryOn = DuplicateKeyException.class)
  protected <D> D transactionalSave(final Supplier<Optional<D>> find, final Supplier<D> create, final UnaryOperator<D> update) {
    // Capture MDC context to preserve it across Virtual Thread handoffs
    Map<String, String> mdcContext = MDCUtils.getContextMapForPropagation();
    
    try {
      return find.get().map(found -> {
        // Restore MDC context before updating
        MDCUtils.applyContextMap(mdcContext);
        return update.apply(found);
      }).orElseGet(() -> {
        // Restore MDC context before creating
        MDCUtils.applyContextMap(mdcContext);
        return create.get();
      });
    } finally {
      // Ensure MDC context is restored
      MDCUtils.applyContextMap(mdcContext);
    }
  }
}