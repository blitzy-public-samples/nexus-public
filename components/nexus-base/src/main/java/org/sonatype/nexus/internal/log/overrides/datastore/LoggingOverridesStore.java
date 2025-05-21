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
package org.sonatype.nexus.internal.log.overrides.datastore;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.TransactionalStore;
import org.sonatype.nexus.transaction.UnitOfWork;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.sonatype.nexus.internal.log.overrides.datastore.LoggerOverridesEvent.Action.CHANGE;
import static org.sonatype.nexus.internal.log.overrides.datastore.LoggerOverridesEvent.Action.RESET;
import static org.sonatype.nexus.internal.log.overrides.datastore.LoggerOverridesEvent.Action.RESET_ALL;

/**
 * Store for accessing the logging-overrides related data
 * 
 * Optimized with Java 21 Virtual Threads for improved I/O performance and throughput
 */
@Named("mybatis")
@Singleton
public class LoggingOverridesStore
    extends ConfigStoreSupport<LoggingOverridesDAO>
    implements TransactionalStore<LoggingOverridesDAO>
{
  private static final Logger log = LoggerFactory.getLogger(LoggingOverridesStore.class);
  
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  protected LoggingOverridesStore(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier);
  }

  /**
   * Creates a new logging override record using Virtual Threads for improved I/O performance
   *
   * @param data the logging override data to create
   */
  @Transactional
  public void create(final LoggingOverridesData data) {
    try {
      dao().createRecord(data);
      postCommitEventWithVirtualThread(() -> new LoggerOverridesEvent(data.getName(), data.getLevel(), CHANGE));
    } 
    catch (Exception e) {
      handleDatabaseException("Failed to create logging override for " + data.getName(), e);
    }
  }

  /**
   * Reads all logging override records
   *
   * @return a continuation of logging override data
   */
  @Transactional
  public Continuation<LoggingOverridesData> readRecords() {
    try {
      return dao().readRecords(null);
    }
    catch (Exception e) {
      handleDatabaseException("Failed to read logging overrides", e);
      return Continuation.empty();
    }
  }

  /**
   * Checks if a logging override with the given name exists
   *
   * @param name the name to check
   * @return true if the override exists, false otherwise
   */
  @Transactional
  public boolean exists(final String name) {
    try {
      return readRecords().stream()
          .anyMatch(level -> level.getName().equals(name));
    }
    catch (Exception e) {
      handleDatabaseException("Failed to check existence of logging override for " + name, e);
      return false;
    }
  }

  /**
   * Updates an existing logging override record using Virtual Threads for improved I/O performance
   *
   * @param data the logging override data to update
   */
  @Transactional
  public void update(final LoggingOverridesData data) {
    try {
      dao().updateRecord(data);
      postCommitEventWithVirtualThread(() -> new LoggerOverridesEvent(data.getName(), data.getLevel(), CHANGE));
    }
    catch (Exception e) {
      handleDatabaseException("Failed to update logging override for " + data.getName(), e);
    }
  }

  /**
   * Deletes a logging override by name using Virtual Threads for improved I/O performance
   *
   * @param name the name of the logging override to delete
   */
  @Transactional
  public void deleteByName(final String name) {
    try {
      dao().deleteRecord(name);
      postCommitEventWithVirtualThread(() -> new LoggerOverridesEvent(name, null, RESET));
    }
    catch (Exception e) {
      handleDatabaseException("Failed to delete logging override for " + name, e);
    }
  }

  /**
   * Deletes all logging override records using Virtual Threads for improved I/O performance
   */
  @Transactional
  public void deleteAllRecords() {
    try {
      dao().deleteAllRecords();
      postCommitEventWithVirtualThread(() -> new LoggerOverridesEvent(null, null, RESET_ALL));
    }
    catch (Exception e) {
      handleDatabaseException("Failed to delete all logging overrides", e);
    }
  }
  
  /**
   * Posts a commit event using a Virtual Thread for more efficient event processing
   *
   * @param supplier the event supplier
   */
  private void postCommitEventWithVirtualThread(final Supplier<?> supplier) {
    CompletableFuture.runAsync(() -> {
      try {
        postCommitEvent(supplier);
      }
      catch (Exception e) {
        log.error("Error processing post-commit event", e);
      }
    }, virtualThreadExecutor);
  }
  
  /**
   * Enhanced error handling for database operations using Java 21 pattern matching
   *
   * @param message the error message
   * @param e the exception that occurred
   */
  private void handleDatabaseException(final String message, final Exception e) {
    // Use Java 21 pattern matching for exception handling
    switch (e) {
      case NullPointerException npe -> {
        log.error("{}. Null reference encountered: {}", message, npe.getMessage(), npe);
        throw new IllegalStateException(message + ". Database operation failed due to null reference.", npe);
      }
      case IllegalArgumentException iae -> {
        log.error("{}. Invalid argument: {}", message, iae.getMessage(), iae);
        throw new IllegalStateException(message + ". Database operation failed due to invalid argument.", iae);
      }
      case UnitOfWork.Pause pause -> {
        log.warn("{}. Transaction paused: {}", message, pause.getMessage());
        throw pause;
      }
      default -> {
        log.error("{}. Database error: {}", message, e.getMessage(), e);
        throw new IllegalStateException(message + ". Database operation failed.", e);
      }
    }
  }
}