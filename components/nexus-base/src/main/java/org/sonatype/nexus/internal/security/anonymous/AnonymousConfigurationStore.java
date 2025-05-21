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

import javax.annotation.Nullable;

import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;

/**
 * {@link AnonymousConfiguration} store.
 * <p>
 * Implementations of this interface must be thread-safe, as they may be accessed concurrently
 * from multiple threads, including Java 21 Virtual Threads. Special care should be taken to ensure
 * proper synchronization when accessing shared state, especially when running in environments with
 * high concurrency enabled by Virtual Threads.
 *
 * @since 3.0
 */
public interface AnonymousConfigurationStore
{
  // TODO: Sort out exceptions, both of these should have some expected exceptions

  /**
   * Load the anonymous configuration from the store.
   * <p>
   * This method may be called from Virtual Threads in high-concurrency scenarios.
   * Implementations should ensure efficient I/O operations and avoid blocking platform threads.
   * 
   * @return The loaded configuration, or null if no configuration exists
   * @since 3.0
   */
  @Nullable
  AnonymousConfiguration load();

  /**
   * Save the anonymous configuration to the store.
   * <p>
   * This method may be called from Virtual Threads in high-concurrency scenarios.
   * Implementations should ensure proper synchronization for thread safety and
   * use non-blocking I/O operations where possible.
   * 
   * @param configuration The configuration to save (must not be null)
   * @since 3.0
   */
  void save(AnonymousConfiguration configuration);

  /**
   * Provide a new instance of {@link AnonymousConfiguration} applicable for use with this backing store.
   * <p>
   * This method should be lightweight and thread-safe, as it may be called frequently
   * from multiple threads, including Virtual Threads.
   *
   * @return A new configuration instance
   * @since 3.20
   */
  AnonymousConfiguration newConfiguration();
}