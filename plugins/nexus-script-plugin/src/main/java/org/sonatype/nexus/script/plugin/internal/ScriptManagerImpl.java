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
package org.sonatype.nexus.script.plugin.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptCreatedEvent;
import org.sonatype.nexus.script.ScriptDeletedEvent;
import org.sonatype.nexus.script.ScriptManager;
import org.sonatype.nexus.script.ScriptUpdatedEvent;

import com.google.common.collect.ImmutableList;
import groovy.transform.CompileStatic;

import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default {@link ScriptManager} implementation using Java 21 Virtual Threads for improved
 * concurrent script management performance.
 *
 * @since 3.0
 */
@Named
@ManagedLifecycle(phase = SERVICES)
@Singleton
@CompileStatic
public class ScriptManagerImpl
    extends StateGuardLifecycleSupport
    implements ScriptManager
{
  private final EventManager eventManager;

  private final ScriptStore scriptStore;

  private final boolean allowCreation;

  /**
   * Virtual thread executor for I/O-bound operations.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public ScriptManagerImpl(
      final EventManager eventManager,
      final ScriptStore scriptStore,
      @Named("${nexus.scripts.allowCreation:-false}") final boolean allowCreation)
  {
    this.eventManager = checkNotNull(eventManager);
    this.scriptStore = checkNotNull(scriptStore);
    this.allowCreation = checkNotNull(allowCreation);
  }

  @Override
  @Guarded(by = STARTED)
  public Iterable<Script> browse() {
    // Use virtual threads for I/O-bound operation to improve concurrent performance
    CompletableFuture<Iterable<Script>> future = CompletableFuture.supplyAsync(
        () -> ImmutableList.copyOf(scriptStore.list()),
        virtualThreadExecutor
    );
    return future.join();
  }

  @Override
  @Guarded(by = STARTED)
  public Script get(final String name) {
    // Use virtual threads for I/O-bound operation to improve concurrent performance
    CompletableFuture<Script> future = CompletableFuture.supplyAsync(
        () -> scriptStore.get(name),
        virtualThreadExecutor
    );
    return future.join();
  }

  @Override
  @Guarded(by = STARTED)
  public Script create(final String name, final String content, final String type) {
    validateCreationIsAllowed();

    // Use virtual threads for I/O-bound operation to improve concurrent performance
    CompletableFuture<Script> future = CompletableFuture.supplyAsync(() -> {
      Script script = scriptStore.newScript();
      script.setName(name);
      script.setContent(content);
      script.setType(type);
      scriptStore.create(script);
      eventManager.post(new ScriptCreatedEvent(script));
      return script;
    }, virtualThreadExecutor);
    
    return future.join();
  }

  @Override
  @Guarded(by = STARTED)
  public Script update(final String name, final String content) {
    validateCreationIsAllowed();

    // Use virtual threads for I/O-bound operation to improve concurrent performance
    CompletableFuture<Script> future = CompletableFuture.supplyAsync(() -> {
      Script script = scriptStore.get(name);
      if (script == null) {
        return null;
      }
      script.setContent(content);
      scriptStore.update(script);
      eventManager.post(new ScriptUpdatedEvent(script));
      return script;
    }, virtualThreadExecutor);
    
    return future.join();
  }

  @Override
  @Guarded(by = STARTED)
  public void delete(final String name) {
    // Use virtual threads for I/O-bound operation to improve concurrent performance
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      Script script = scriptStore.get(name);
      if (script != null) {
        scriptStore.delete(script);
        eventManager.post(new ScriptDeletedEvent(script));
      }
    }, virtualThreadExecutor);
    
    future.join();
  }

  @Override
  public boolean isEnabled() {
    return allowCreation;
  }

  private void validateCreationIsAllowed() {
    if (!allowCreation) {
      // Using Java 21 String Template for improved readability
      throw new ScriptingDisabledException(STR."Creating and updating scripts is disabled. Enable with nexus.scripts.allowCreation=true");
    }
  }
  
  @Override
  protected void doStop() throws Exception {
    virtualThreadExecutor.close();
    super.doStop();
  }
}