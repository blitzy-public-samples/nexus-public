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
package org.sonatype.nexus.repository.httpbridge.legacy;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.common.property.SystemPropertiesHelper;
import org.sonatype.nexus.repository.httpbridge.internal.HttpBridgeModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.capability.CapabilityReferenceFilterBuilder.capabilities;

/**
 * Helper class to determine if legacy URL support is enabled.
 * Thread-safe implementation optimized for Java 21 Virtual Threads.
 */
@Named
@Singleton
public class LegacyUrlEnabledHelper
{
  /**
   * Cache expiration duration in seconds.
   */
  private static final Duration CACHE_EXPIRATION = Duration.ofSeconds(30);

  private final boolean supportLegacyContent = SystemPropertiesHelper
      .getBoolean(HttpBridgeModule.class.getName() + ".legacy", false);

  private final CapabilityRegistry capabilities;
  
  /**
   * Thread-safe cache for capability active state.
   * Uses AtomicReference to ensure visibility across threads without explicit synchronization.
   */
  private final AtomicReference<CachedState> cachedState = new AtomicReference<>();

  @Inject
  public LegacyUrlEnabledHelper(final CapabilityRegistry capabilities)
  {
    this.capabilities = checkNotNull(capabilities);
  }

  /**
   * Checks if legacy URL support is enabled, either via system property or capability.
   * Thread-safe and optimized for concurrent access from Virtual Threads.
   *
   * @return true if legacy URL support is enabled
   */
  public boolean isEnabled() {
    return supportLegacyContent || isLegacyUrlCapabilityActive();
  }

  /**
   * Checks if the legacy URL capability is active, using a cached value when possible.
   * Thread-safe implementation for Virtual Thread environments.
   *
   * @return true if the legacy URL capability is active
   */
  private boolean isLegacyUrlCapabilityActive() {
    // Fast path: check if we have a valid cached state
    CachedState currentState = cachedState.get();
    if (currentState != null && !currentState.isExpired()) {
      return currentState.isActive();
    }
    
    // Slow path: need to refresh the cache
    return refreshCachedState();
  }
  
  /**
   * Refreshes the cached state by querying the capability registry.
   * Uses double-checked locking pattern optimized for Virtual Threads to minimize contention.
   *
   * @return the current active state of the legacy URL capability
   */
  private boolean refreshCachedState() {
    // Double-checked locking pattern to minimize contention
    CachedState currentState = cachedState.get();
    if (currentState != null && !currentState.isExpired()) {
      return currentState.isActive();
    }
    
    // Synchronize only during actual refresh to minimize contention
    synchronized (this) {
      // Check again inside synchronized block
      currentState = cachedState.get();
      if (currentState != null && !currentState.isExpired()) {
        return currentState.isActive();
      }
      
      // Query capability registry (expensive operation)
      boolean active = queryCapabilityRegistry();
      
      // Update cache with new state
      cachedState.set(new CachedState(active, Instant.now().plus(CACHE_EXPIRATION)));
      return active;
    }
  }
  
  /**
   * Queries the capability registry to determine if the legacy URL capability is active.
   * This is the expensive operation we want to cache.
   *
   * @return true if the legacy URL capability is active
   */
  private boolean queryCapabilityRegistry() {
    Collection<? extends CapabilityReference> references = capabilities
        .get(capabilities().withType(LegacyUrlCapabilityDescriptor.TYPE));

    if (references.isEmpty()) {
      return false;
    }

    return references.iterator().next().context().isActive();
  }
  
  /**
   * Immutable record to hold the cached state of the legacy URL capability.
   * Uses Java 21 record pattern for efficient, thread-safe state representation.
   */
  private record CachedState(boolean active, Instant expiresAt) {
    /**
     * Checks if this cached state has expired.
     *
     * @return true if the cached state has expired and should be refreshed
     */
    boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
    
    /**
     * Gets the active state of the legacy URL capability.
     *
     * @return true if the legacy URL capability is active
     */
    boolean isActive() {
      return active;
    }
  }
}