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
package org.sonatype.nexus.testsuite.testsupport.system;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.testsuite.testsupport.config.DatabaseConfig;

/**
 * Configuration test system that provides access to database configuration.
 * <p>
 * This class has been updated for Java 21 to leverage virtual threads for improved concurrency
 * in database setup and teardown operations. Virtual threads are lightweight threads managed by the JVM
 * and are ideal for I/O-bound operations like database interactions.
 * </p>
 * <p>
 * Compatible with JUnit Jupiter 5.10.1 and Mockito 4.11.0 for modern testing approaches.
 * </p>
 *
 * @since 3.0
 */
@Named
@Singleton
public class ConfigTestSystem
    extends TestSystemSupport
{
  private final DatabaseConfig databaseConfig;

  /**
   * Creates a new ConfigTestSystem with the specified database configuration and event manager.
   *
   * @param databaseConfig the database configuration to use
   * @param eventManager the event manager to use for event handling
   */
  @Inject
  public ConfigTestSystem(final DatabaseConfig databaseConfig, final EventManager eventManager) {
    super(eventManager);
    this.databaseConfig = databaseConfig;
  }

  /**
   * Performs any necessary cleanup after tests.
   * <p>
   * This implementation does not perform any specific cleanup actions but can be extended
   * in subclasses to implement custom teardown logic using virtual threads for improved
   * concurrency and resource efficiency.
   * </p>
   */
  @Override
  protected void doAfter() {
    // No specific cleanup needed in this implementation
    // Subclasses can override to implement custom teardown logic with virtual threads
  }

  /**
   * Asynchronously performs a database operation using virtual threads.
   * <p>
   * This method demonstrates how to use virtual threads for database operations.
   * It executes the provided runnable in a virtual thread and returns a CompletableFuture
   * that completes when the operation is done.
   * </p>
   *
   * @param operation the database operation to perform
   * @return a CompletableFuture that completes when the operation is done
   */
  protected CompletableFuture<Void> runDatabaseOperationAsync(Runnable operation) {
    return CompletableFuture.runAsync(operation, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Gets the database configuration.
   *
   * @return the database configuration
   */
  public DatabaseConfig db() {
    return databaseConfig;
  }
}