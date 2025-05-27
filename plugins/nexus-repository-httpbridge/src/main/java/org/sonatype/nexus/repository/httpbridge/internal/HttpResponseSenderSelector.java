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
package org.sonatype.nexus.repository.httpbridge.internal;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.httpbridge.HttpResponseSender;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Map.copyOf;

/**
 * Response sender selector.
 * <p>
 * This class is thread-safe and optimized for use in a Java 21 Virtual Thread environment.
 * It uses an immutable map for response senders to ensure thread safety without synchronization
 * overhead, making it suitable for high-concurrency scenarios with Virtual Threads.
 *
 * @since 3.0
 */
@Singleton
@Named
class HttpResponseSenderSelector
    extends ComponentSupport
{
  /**
   * Immutable map of format-specific response senders.
   * Using an immutable copy ensures thread safety in a Virtual Thread environment
   * without requiring explicit synchronization during read operations.
   */
  private final Map<String, HttpResponseSender> responseSenders;

  private final DefaultHttpResponseSender defaultHttpResponseSender;

  @Inject
  public HttpResponseSenderSelector(final Map<String, HttpResponseSender> responseSenders,
                                    final DefaultHttpResponseSender defaultHttpResponseSender)
  {
    checkNotNull(responseSenders);
    checkNotNull(defaultHttpResponseSender);
    
    // Create an immutable copy of the map to ensure thread safety
    this.responseSenders = copyOf(responseSenders);
    this.defaultHttpResponseSender = defaultHttpResponseSender;
    
    log.debug("Initialized HttpResponseSenderSelector with {} format-specific senders", this.responseSenders.size());
  }

  /**
   * Returns the default sender.
   * This method is thread-safe and can be called from multiple Virtual Threads concurrently.
   */
  @Nonnull
  public HttpResponseSender defaultSender() {
    return defaultHttpResponseSender;
  }

  /**
   * Find sender for repository format.
   * <p>
   * If no format-specific sender is configured, the default is used.
   * <p>
   * This method is thread-safe and optimized for concurrent access from Virtual Threads.
   * It uses an immutable map to avoid synchronization overhead during lookups.
   *
   * @param repository The repository to find a sender for
   * @return The appropriate HttpResponseSender for the repository format
   */
  @Nonnull
  public HttpResponseSender sender(final Repository repository) {
    String format = repository.getFormat().getValue();
    boolean isVirtualThread = Thread.currentThread().isVirtual();
    
    if (isVirtualThread) {
      log.debug("Virtual Thread [{}] looking for HTTP response sender: {}", 
          Thread.currentThread().threadId(), format);
    } else {
      log.debug("Platform Thread [{}] looking for HTTP response sender: {}", 
          Thread.currentThread().threadId(), format);
    }
    
    HttpResponseSender sender = responseSenders.get(format);
    if (sender == null) {
      if (isVirtualThread) {
        log.debug("Virtual Thread [{}] using default HTTP response sender for format: {}", 
            Thread.currentThread().threadId(), format);
      }
      return defaultHttpResponseSender;
    }
    
    if (isVirtualThread) {
      log.debug("Virtual Thread [{}] using format-specific HTTP response sender: {}", 
          Thread.currentThread().threadId(), format);
    }
    return sender;
  }
}