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
import java.util.concurrent.Future;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.log.LogManager;
import org.sonatype.nexus.common.log.LoggerLevel;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Test system for managing loggers during tests.
 * <p>
 * This class has been updated for Java 21 to leverage virtual threads for improved concurrency when
 * managing loggers. Virtual threads are lightweight threads that are managed by the JVM and are ideal for
 * I/O-bound operations like logger management.
 * </p>
 * <p>
 * Compatible with JUnit Jupiter 5.10.1 and Mockito 4.11.0 for modern testing approaches.
 * </p>
 *
 * @since 3.60
 */
@Named
@Singleton
public class LogTestSystem
    extends TestSystemSupport
{
  private final LogManager logManager;

  @Inject
  public LogTestSystem(final LogManager logManager, final EventManager eventManager) {
    super(eventManager);
    this.logManager = checkNotNull(logManager);
  }

  /**
   * Sets the logger level for the specified logger name.
   * <p>
   * This operation is performed asynchronously using a virtual thread to avoid blocking
   * the calling thread, especially useful for tests that need to configure multiple loggers.
   * </p>
   *
   * @param name the logger name
   * @param level the logger level to set
   */
  public void set(final String name, final LoggerLevel level) {
    CompletableFuture.runAsync(
        () -> logManager.setLoggerLevel(name, level),
        Thread.ofVirtual().name("logger-config-" + name + "-").factory())
        .exceptionally(ex -> {
          // Log and rethrow to ensure test failures are visible
          System.err.println("Failed to set logger level for " + name + ": " + ex.getMessage());
          if (ex instanceof RuntimeException) {
            throw (RuntimeException) ex;
          }
          throw new RuntimeException(ex);
        });
  }

  /**
   * Resets all loggers to their default levels using a virtual thread.
   * <p>
   * This implementation leverages Java 21 virtual threads to efficiently reset loggers
   * without blocking platform threads. Virtual threads are ideal for this kind of I/O-bound
   * operation.
   * </p>
   */
  @Override
  protected void doAfter() {
    // Use a virtual thread to reset loggers
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        logManager.resetLoggers();
        return null;
      });
      
      // Wait for the virtual thread to complete
      future.get();
    } catch (Exception e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to reset loggers", e);
    }
  }
}
