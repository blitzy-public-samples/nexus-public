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
package org.sonatype.nexus.common.cooperation2.internal;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.common.cooperation2.Config;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory.Builder;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Mutable configuration support for Cooperation2 points.
 * 
 * @since 3.41
 */
public abstract class MutableConfigSupport
    extends Config
    implements Builder
{
  /**
   * Whether the cooperation is enabled.
   */
  protected boolean enabled = true;
  
  /**
   * Whether to use Virtual Threads for I/O operations.
   * Virtual Threads are lightweight threads introduced in Java 21 that are ideal for I/O-bound operations.
   * They provide significantly improved throughput for operations that spend most of their time waiting for I/O.
   */
  protected boolean useVirtualThreads = true;
  
  /**
   * Atomic counter for concurrency control, used when limiting concurrent operations.
   * This replaces traditional thread pool counters with a more efficient atomic implementation.
   */
  protected AtomicInteger concurrencyLimit = new AtomicInteger(0);

  @Override
  public Builder majorTimeout(final Duration majorTimeout) {
    this.majorTimeoutSeconds = (int) checkNotNull(majorTimeout).getSeconds();
    return this;
  }

  @Override
  public Builder minorTimeout(final Duration minorTimeout) {
    this.minorTimeoutSeconds = (int) checkNotNull(minorTimeout).getSeconds();
    return this;
  }

  @Override
  public Builder threadsPerKey(final int threadsPerKey) {
    this.threadsPerKey = threadsPerKey;
    // Initialize the concurrency limit with the threads per key value
    this.concurrencyLimit.set(threadsPerKey);
    return this;
  }

  @Override
  public Builder enabled(final boolean enabled) {
    this.enabled = enabled;
    return this;
  }
  
  /**
   * Configures whether to use Virtual Threads for I/O operations.
   * 
   * @param useVirtualThreads true to use Virtual Threads (recommended for Java 21+), false to use platform threads
   * @return this builder for fluent API
   * @since 3.60
   */
  public Builder useVirtualThreads(final boolean useVirtualThreads) {
    this.useVirtualThreads = useVirtualThreads;
    return this;
  }
  
  /**
   * Returns whether Virtual Threads are enabled for this configuration.
   * 
   * @return true if Virtual Threads are enabled, false otherwise
   * @since 3.60
   */
  public boolean useVirtualThreads() {
    return useVirtualThreads;
  }
  
  /**
   * Gets the atomic concurrency limit counter.
   * 
   * @return the atomic concurrency limit counter
   * @since 3.60
   */
  public AtomicInteger concurrencyLimit() {
    return concurrencyLimit;
  }
  
  /**
   * Creates a copy of this configuration.
   * 
   * @return a new instance with the same configuration values
   */
  @Override
  protected Config copy() {
    // We can't use super.copy() directly as it returns a plain Config instance
    // Instead, create a new instance of the same class and copy all fields
    Config copy = new Config();
    copy.majorTimeoutSeconds = majorTimeoutSeconds;
    copy.minorTimeoutSeconds = minorTimeoutSeconds;
    copy.threadsPerKey = threadsPerKey;
    
    // Since this method is meant to be overridden by concrete subclasses,
    // they will need to copy their own fields as well
    return copy;
  }
}