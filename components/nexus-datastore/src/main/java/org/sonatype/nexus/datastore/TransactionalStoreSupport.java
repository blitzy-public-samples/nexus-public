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

import java.util.concurrent.Callable;

import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.transaction.TransactionIsolation;
import org.sonatype.nexus.transaction.TransactionalStore;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Support class for transactional stores that provides database session management.
 * Uses Java 21 Virtual Threads for improved I/O performance with database operations.
 */
public abstract class TransactionalStoreSupport
    extends StateGuardLifecycleSupport
    implements TransactionalStore<DataSession<?>>
{
  protected final DataSessionSupplier sessionSupplier;

  private final String storeName;

  // Thread-local context to maintain transaction context across virtual thread handoffs
  private static final ThreadLocal<Object> TRANSACTION_CONTEXT = new ThreadLocal<>();

  protected TransactionalStoreSupport(final DataSessionSupplier sessionSupplier, final String storeName) {
    this.sessionSupplier = checkNotNull(sessionSupplier);
    this.storeName = checkNotNull(storeName);
  }

  @Override
  public DataSession<?> openSession() {
    return executeInVirtualThread(() -> {
      log.debug(STR."Opening session for store \{storeName}");
      return sessionSupplier.openSession(storeName);
    });
  }

  @Override
  public DataSession<?> openSession(final TransactionIsolation isolationLevel) {
    return executeInVirtualThread(() -> {
      return switch (isolationLevel) {
        case SERIALIZABLE -> {
          log.debug(STR."Opening session with serializable transaction isolation for store \{storeName}");
          yield sessionSupplier.openSerializableTransactionSession(storeName);
        }
        default -> {
          log.debug(STR."Opening session with default transaction isolation for store \{storeName}");
          yield sessionSupplier.openSession(storeName);
        }
      };
    });
  }

  /**
   * Executes the given database operation in a virtual thread.
   * Propagates the transaction context across thread boundaries to maintain transaction integrity.
   *
   * @param operation the database operation to execute
   * @return the result of the operation
   * @param <T> the type of the result
   */
  private <T> T executeInVirtualThread(Callable<T> operation) {
    try {
      // Capture the current transaction context
      Object context = TRANSACTION_CONTEXT.get();
      
      // Create a virtual thread to execute the database operation
      return Thread.startVirtualThread(() -> {
        try {
          // Propagate the transaction context to the virtual thread
          if (context != null) {
            TRANSACTION_CONTEXT.set(context);
          }
          
          // Execute the database operation
          return operation.call();
        } catch (Exception e) {
          if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
          }
          throw new RuntimeException(STR."Error executing database operation: \{e.getMessage()}", e);
        } finally {
          // Clean up the thread-local context
          if (context != null) {
            TRANSACTION_CONTEXT.remove();
          }
        }
      }).join();
    } catch (Exception e) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new RuntimeException(STR."Error starting virtual thread: \{e.getMessage()}", e);
    }
  }
}