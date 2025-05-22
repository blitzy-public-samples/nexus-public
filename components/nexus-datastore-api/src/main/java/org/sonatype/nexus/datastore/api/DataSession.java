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
package org.sonatype.nexus.datastore.api;

import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;
import java.util.function.Supplier;

import org.sonatype.nexus.transaction.Transaction;
import org.sonatype.nexus.transaction.TransactionalSession;

/**
 * Represents a session with a {@link DataStore}.
 *
 * @since 3.19
 */
public interface DataSession<T extends Transaction>
    extends TransactionalSession<T>
{
  /**
   * {@link DataAccess} mapping for the given type.
   */
  <D extends DataAccess> D access(Class<D> type);

  /**
   * Registers a hook to run before any changes are committed in this session.
   * <p>
   * The hook will preserve thread context when executed across Virtual Thread handoffs.
   *
   * @since 3.26
   */
  void preCommit(Runnable hook);

  /**
   * Registers a hook to run after changes have been committed in this session.
   * <p>
   * The hook will preserve thread context when executed across Virtual Thread handoffs.
   *
   * @since 3.26
   */
  void postCommit(Runnable hook);

  /**
   * Registers a hook to run after changes have been rolled back in this session.
   * <p>
   * The hook will preserve thread context when executed across Virtual Thread handoffs.
   *
   * @since 3.26
   */
  void onRollback(Runnable hook);
  
  /**
   * Executes the given task within the transaction context, preserving thread local variables
   * and contextual information when executed in Virtual Threads.
   *
   * @param <R> the result type
   * @param task the task to execute
   * @return the result of the task
   * @throws Exception if the task throws an exception
   * @since 3.31
   */
  default <R> R executeWithContext(Callable<R> task) throws Exception {
    return task.call();
  }
  
  /**
   * Executes multiple tasks concurrently within the transaction context using structured concurrency.
   * This method ensures that all tasks share the same transaction context and properly propagate
   * thread local variables across Virtual Thread handoffs.
   *
   * @param <R> the result type
   * @param tasks the tasks to execute concurrently
   * @return a supplier that provides the results of all tasks
   * @throws Exception if any task throws an exception
   * @since 3.31
   */
  default <R> Supplier<R[]> executeConcurrently(Callable<R>... tasks) throws Exception {
    try (ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
      @SuppressWarnings("unchecked")
      Supplier<R>[] suppliers = new Supplier[tasks.length];
      
      for (int i = 0; i < tasks.length; i++) {
        final int index = i;
        final Callable<R> task = tasks[i];
        suppliers[i] = scope.fork(() -> executeWithContext(task));
      }
      
      scope.join().throwIfFailed();
      
      @SuppressWarnings("unchecked")
      R[] results = (R[]) new Object[tasks.length];
      for (int i = 0; i < suppliers.length; i++) {
        results[i] = suppliers[i].get();
      }
      
      return () -> results;
    }
  }

  /**
   * Returns the SQL dialect of the database backing this session.
   *
   * @since 3.26
   */
  String sqlDialect();
}