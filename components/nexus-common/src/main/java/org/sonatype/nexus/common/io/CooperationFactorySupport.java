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
package org.sonatype.nexus.common.io;

import java.time.Duration;

import org.sonatype.goodies.lifecycle.LifecycleSupport;

/**
 * Common scaffolding for {@link CooperationFactory} implementations.
 * <p>
 * With Java 21, this class supports configuration of Virtual Threads for high-throughput
 * I/O operations. Virtual Threads provide significant performance benefits for I/O-bound
 * operations by allowing thousands of concurrent operations with minimal resource overhead.
 *
 * @since 3.14
 */
public abstract class CooperationFactorySupport
    extends LifecycleSupport
    implements CooperationFactory
{
  /**
   * Default number of carrier threads for Virtual Threads, based on available processors.
   */
  private static final int DEFAULT_MAX_CARRIER_THREADS = Runtime.getRuntime().availableProcessors();

  @Override
  public Builder configure() {
    return new MutableConfig();
  }

  /**
   * Builds a new {@link Cooperation} point with the given configuration.
   * <p>
   * Implementations should check the config for Virtual Thread settings and optimize
   * accordingly when running on Java 21 or later.
   *
   * @param id unique identifier for this cooperation point
   * @param config configuration for the cooperation point
   */
  protected abstract Cooperation build(String id, Config config);

  /**
   * Configuration holder for {@link Cooperation} points.
   */
  public static class Config
  {
    protected int majorTimeoutSeconds = 0;

    protected int minorTimeoutSeconds = 0;

    protected int threadsPerKey = 0;
    
    /**
     * Flag indicating whether to use Virtual Threads for I/O operations.
     * Only effective when running on Java 21 or later.
     */
    protected boolean useVirtualThreads = false;
    
    /**
     * Maximum number of carrier threads to use for Virtual Threads.
     * Only effective when useVirtualThreads is true.
     */
    protected int maxCarrierThreads = DEFAULT_MAX_CARRIER_THREADS;
    
    /**
     * Flag indicating whether to allow Virtual Thread pinning.
     * Only effective when useVirtualThreads is true.
     */
    protected boolean allowThreadPinning = true;

    public Duration majorTimeout() {
      return Duration.ofSeconds(majorTimeoutSeconds);
    }

    public Duration minorTimeout() {
      return Duration.ofSeconds(minorTimeoutSeconds);
    }

    public int threadsPerKey() {
      return threadsPerKey;
    }
    
    /**
     * Returns whether Virtual Threads should be used for I/O operations.
     * 
     * @return true if Virtual Threads should be used, false otherwise
     * @since 3.60
     */
    public boolean useVirtualThreads() {
      return useVirtualThreads;
    }
    
    /**
     * Returns the maximum number of carrier threads to use for Virtual Threads.
     * 
     * @return the maximum number of carrier threads
     * @since 3.60
     */
    public int maxCarrierThreads() {
      return maxCarrierThreads;
    }
    
    /**
     * Returns whether Virtual Thread pinning is allowed.
     * 
     * @return true if pinning is allowed, false otherwise
     * @since 3.60
     */
    public boolean allowThreadPinning() {
      return allowThreadPinning;
    }

    protected Config copy() {
      Config copy = new Config();
      copy.majorTimeoutSeconds = majorTimeoutSeconds;
      copy.minorTimeoutSeconds = minorTimeoutSeconds;
      copy.threadsPerKey = threadsPerKey;
      copy.useVirtualThreads = useVirtualThreads;
      copy.maxCarrierThreads = maxCarrierThreads;
      copy.allowThreadPinning = allowThreadPinning;
      return copy;
    }
  }

  /**
   * Mutable {@link Builder} of {@link Config}s.
   */
  private final class MutableConfig
      extends Config
      implements Builder
  {
    @Override
    public Builder majorTimeout(final Duration majorTimeout) {
      this.majorTimeoutSeconds = (int) majorTimeout.getSeconds();
      return this;
    }

    @Override
    public Builder minorTimeout(final Duration minorTimeout) {
      this.minorTimeoutSeconds = (int) minorTimeout.getSeconds();
      return this;
    }

    @Override
    public Builder threadsPerKey(final int threadsPerKey) {
      this.threadsPerKey = threadsPerKey;
      return this;
    }
    
    @Override
    public Builder virtualThreads(boolean enabled) {
      this.useVirtualThreads = enabled;
      return this;
    }
    
    @Override
    public Builder maxCarrierThreads(int maxCarrierThreads) {
      this.maxCarrierThreads = maxCarrierThreads;
      return this;
    }
    
    @Override
    public Builder allowThreadPinning(boolean allowPinning) {
      this.allowThreadPinning = allowPinning;
      return this;
    }

    @Override
    public Cooperation build(final String id) {
      return CooperationFactorySupport.this.build(id, copy());
    }
  }
}