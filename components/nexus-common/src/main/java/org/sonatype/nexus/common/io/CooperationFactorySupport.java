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
 *
 * @since 3.14
 */
public abstract class CooperationFactorySupport
    extends LifecycleSupport
    implements CooperationFactory
{
  @Override
  public Builder configure() {
    return new MutableConfig();
  }

  /**
   * Builds a new {@link Cooperation} point with the given configuration.
   *
   * @param id unique identifier for this cooperation point
   * @param config configuration for this cooperation point
   * @return a new {@link Cooperation} instance
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
     * Flag indicating whether to use Virtual Threads (Java 21+) for this cooperation point.
     * When true, the cooperation mechanism will leverage Virtual Threads for improved performance
     * during I/O-bound operations.
     * 
     * @since 3.60
     */
    protected boolean useVirtualThreads = false;
    
    /**
     * Maximum number of Virtual Threads allowed per cooperation key.
     * Only applicable when {@link #useVirtualThreads} is true.
     * 
     * @since 3.60
     */
    protected int virtualThreadsPerKey = 0;

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
     * Returns whether Virtual Threads should be used for this cooperation point.
     * 
     * @return true if Virtual Threads should be used, false otherwise
     * @since 3.60
     */
    public boolean useVirtualThreads() {
      return useVirtualThreads;
    }
    
    /**
     * Returns the maximum number of Virtual Threads allowed per cooperation key.
     * Only applicable when {@link #useVirtualThreads()} is true.
     * 
     * @return maximum number of Virtual Threads per key
     * @since 3.60
     */
    public int virtualThreadsPerKey() {
      return virtualThreadsPerKey;
    }

    protected Config copy() {
      Config copy = new Config();
      copy.majorTimeoutSeconds = majorTimeoutSeconds;
      copy.minorTimeoutSeconds = minorTimeoutSeconds;
      copy.threadsPerKey = threadsPerKey;
      copy.useVirtualThreads = useVirtualThreads;
      copy.virtualThreadsPerKey = virtualThreadsPerKey;
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
    
    /**
     * Configures whether to use Virtual Threads (Java 21+) for this cooperation point.
     * When enabled, the cooperation mechanism will leverage Virtual Threads for improved
     * performance during I/O-bound operations.
     *
     * @param useVirtualThreads true to use Virtual Threads, false to use platform threads
     * @return this builder for fluent method chaining
     * @since 3.60
     */
    @Override
    public Builder useVirtualThreads(final boolean useVirtualThreads) {
      this.useVirtualThreads = useVirtualThreads;
      return this;
    }
    
    /**
     * Configures the maximum number of Virtual Threads allowed per cooperation key.
     * Only applicable when {@link #useVirtualThreads(boolean)} is set to true.
     * 
     * @param virtualThreadsPerKey maximum number of Virtual Threads per key
     * @return this builder for fluent method chaining
     * @since 3.60
     */
    @Override
    public Builder virtualThreadsPerKey(final int virtualThreadsPerKey) {
      this.virtualThreadsPerKey = virtualThreadsPerKey;
      return this;
    }

    @Override
    public Cooperation build(final String id) {
      return CooperationFactorySupport.this.build(id, copy());
    }
  }
}