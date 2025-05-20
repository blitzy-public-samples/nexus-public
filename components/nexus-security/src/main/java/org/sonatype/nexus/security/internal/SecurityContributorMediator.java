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
package org.sonatype.nexus.security.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.config.SecurityContributor;

import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.Mediator;

/**
 * Notifies {@link SecurityConfigurationManager} as {@link SecurityContributor}s come and go.
 * 
 * Updated for Java 21 with Virtual Threads for event delegation and improved concurrency model.
 *
 * @since 3.1
 */
@Named
public class SecurityContributorMediator
    extends ComponentSupport
    implements Mediator<Named, SecurityContributor, SecurityConfigurationManagerImpl>
{
  private final EventManager eventManager;
  private final ExecutorService virtualThreadExecutor;
  
  @Inject
  public SecurityContributorMediator(final EventManager eventManager) {
    this.eventManager = eventManager;
    // Create a virtual thread per task executor for handling contributor events
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @Override
  public void add(BeanEntry<Named, SecurityContributor> entry, SecurityConfigurationManagerImpl watcher) {
    // Use virtual threads for non-blocking event delegation
    virtualThreadExecutor.submit(() -> {
      log.debug("Adding security contributor: {}", entry.getValue());
      watcher.addContributor(entry.getValue());
    });
  }

  @Override
  public void remove(BeanEntry<Named, SecurityContributor> entry, SecurityConfigurationManagerImpl watcher) {
    // Use virtual threads for non-blocking event delegation
    virtualThreadExecutor.submit(() -> {
      log.debug("Removing security contributor: {}", entry.getValue());
      watcher.removeContributor(entry.getValue());
    });
  }
}