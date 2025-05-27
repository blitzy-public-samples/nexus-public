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
package org.sonatype.nexus.internal.security.anonymous;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.transaction.Transactional;

/**
 * MyBatis {@link AnonymousConfigurationStore} implementation.
 *
 * @since 3.21
 */
@Named("mybatis")
@Singleton
public class AnonymousConfigurationStoreImpl
    extends ConfigStoreSupport<AnonymousConfigurationDAO>
    implements AnonymousConfigurationStore
{
  @Inject
  public AnonymousConfigurationStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier);
  }

  @Override
  public AnonymousConfiguration newConfiguration() {
    return new AnonymousConfigurationData();
  }

  /**
   * Load the anonymous configuration.
   * 
   * With Java 21, this method benefits from Virtual Threads automatically.
   * When running on a Virtual Thread, I/O operations will automatically unmount the thread,
   * allowing the carrier thread to be reused for other tasks, improving performance.
   */
  @Transactional
  @Override
  public AnonymousConfiguration load() {
    // In Java 21, database I/O operations automatically leverage Virtual Threads
    // when this method is called from a Virtual Thread context
    return dao().get().orElse(null);
  }

  /**
   * Save the anonymous configuration.
   * 
   * Uses Java 21 pattern matching for instanceof to improve code readability and flow control.
   * With Java 21, this method benefits from Virtual Threads automatically when called from a Virtual Thread context.
   * Ensures proper handling of post-commit events with transaction boundaries maintained.
   */
  @Transactional
  @Override
  public void save(final AnonymousConfiguration configuration) {
    // Use pattern matching for instanceof to improve code readability and eliminate casting
    if (configuration instanceof AnonymousConfigurationData configData) {
      // Register post-commit event handler that will be executed after successful transaction commit
      // In Java 21, this properly handles Virtual Thread scheduling and continuation
      postCommitEvent(() -> new AnonymousConfigurationUpdatedEvent(configData));
      
      // In Java 21, database I/O operations automatically leverage Virtual Threads
      // when this method is called from a Virtual Thread context
      dao().set(configData);
    }
    else {
      throw new IllegalArgumentException("Configuration must be an instance of AnonymousConfigurationData");
    }
  }
}