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
package org.sonatype.nexus.common.app;

import java.util.SequencedCollection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ManagedLifecycle.Phase;

/**
 * Manages {@link ManagedLifecycle} components.
 *
 * @since 3.3
 */
public abstract class ManagedLifecycleManager
    extends ComponentSupport
{
  /**
   * Returns the current phase.
   */
  public abstract Phase getCurrentPhase();

  /**
   * Attempts to move to the target phase by starting (or stopping) components phase-by-phase. If any components have
   * appeared since the last request which belong to the current phase or earlier then they are automatically started
   * before the current phase is changed. Similarly components that have disappeared are stopped.
   */
  public abstract void to(final Phase targetPhase) throws Exception;

  /**
   * Attempts to bounce the given phase by moving the lifecycle just before it then back towards the current phase,
   * re-running all the phases in between. If the bounce phase is after the current phase then it simply moves the
   * lifecycle forwards like {@link #to(Phase)}.
   *
   * @since 3.16
   */
  public abstract void bounce(final Phase bouncePhase) throws Exception;
  
  /**
   * Returns an ordered collection of all available phases.
   * 
   * @return a sequenced collection of phases in order from OFF to TASKS
   * @since 3.60
   */
  public abstract SequencedCollection<Phase> getPhases();

  /**
   * Are we in the process of shutting down? (ie. moving to the {@code OFF} phase)
   *
   * @since 3.16
   */
  public static boolean isShuttingDown() {
    return shuttingDown.get();
  }

  private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

  protected ManagedLifecycleManager() {
    shuttingDown.set(false);
  }

  /**
   * Flag that we are in the process of shutting down.
   *
   * @since 3.16
   */
  protected void declareShutdown() {
    log.info("Shutting down");
    shuttingDown.set(true);
  }

  /**
   * Shutdown Nexus, and provide a custom exit code to the calling system/process. This should ensure that all services
   * and phases are stopped and safe before ending.
   * 
   * @param exitCode the exit code to provide to the calling system/process
   * @throws Exception the lifecycle manager may propagate exceptions if the change is not possible
   */
  public void shutdownWithExitCode(final int exitCode) throws Exception {
    System.setProperty("nexus.overrideExitCode", Integer.toString(exitCode));
    log.info(STR."Shutdown requested with an exit code of \{exitCode}");
    this.to(Phase.OFF);
  }
  
  /**
   * Asynchronously initiates a shutdown with the specified exit code using a virtual thread.
   * This allows the caller to continue processing while the shutdown sequence runs in the background.
   *
   * @param exitCode the exit code to provide to the calling system/process
   * @return a CompletableFuture that completes when the shutdown process is finished
   * @since 3.60
   */
  public CompletableFuture<Void> shutdownWithExitCodeAsync(final int exitCode) {
    return CompletableFuture.runAsync(() -> {
      try {
        shutdownWithExitCode(exitCode);
      } 
      catch (Exception e) {
        log.error("Error during async shutdown", e);
        throw new RuntimeException("Shutdown failed", e);
      }
    }, Thread.ofVirtual().name("lifecycle-shutdown-").factory());
  }
}