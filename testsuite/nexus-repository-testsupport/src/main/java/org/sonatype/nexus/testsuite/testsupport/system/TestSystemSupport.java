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

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.sonatype.nexus.common.event.EventManager;

import org.junit.rules.ExternalResource;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

/**
 * Any TestSystem impl in the new IT framework that needs to cleanup when done should extend this support class
 * to make sure that any async events that may get spawned are left to complete before moving on.  So that we
 * (for example) don't have a cleanuppolicy get removed which kicks off an async event handler that is updating
 * repositories to remove that cleanuppolicy from repo config, while at the same time the teardown process has moved
 * to deleting repositories, which may fail because the repo is also being updated at same time, i.e.
 * https://issues.sonatype.org/browse/NEXUS-27379
 * 
 * <p>This class has been updated for Java 21 to leverage virtual threads for improved concurrency when waiting
 * for event completion. Virtual threads are lightweight threads that are managed by the JVM and are ideal for
 * I/O-bound operations like waiting for events to complete.</p>
 * 
 * <p>Note: While this class extends JUnit 4's ExternalResource for backward compatibility, it can also be used
 * with JUnit Jupiter 5.10.1 by manually calling the before() and after() methods from @BeforeEach and @AfterEach
 * annotated methods, or by using JUnit Jupiter's ExtendWith mechanism with a custom extension.</p>
 */
public abstract class TestSystemSupport
    extends ExternalResource
{
  private final EventManager eventManager;

  protected TestSystemSupport(final EventManager eventManager) {
    this.eventManager = checkNotNull(eventManager);
  }

  /**
   * Waits for a calm period in the event system using a virtual thread.
   * 
   * <p>This method leverages Java 21 virtual threads to efficiently wait for event completion
   * without blocking platform threads. Virtual threads are ideal for this kind of I/O-bound
   * waiting operation.</p>
   */
  protected void waitForCalmPeriod() {
    // Use a virtual thread to wait for the calm period
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> future = executor.submit(() -> {
        await()
            .pollInterval(Duration.ofMillis(100))
            .atMost(5, SECONDS)
            .until(eventManager::isCalmPeriod);
        return null;
      });
      
      // Wait for the virtual thread to complete
      future.get();
    } catch (Exception e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to wait for calm period", e);
    }
  }

  @Override
  public void before() {
    doBefore();
  }

  protected void doBefore() {
    // Do nothing by default
  }

  protected abstract void doAfter();

  @Override
  public void after() {
    doAfter();
    waitForCalmPeriod();
  }
}