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

import org.sonatype.nexus.common.cooperation2.Config;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory.Builder;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Mutable configuration support for Cooperation2 with Java 21 Virtual Thread support.
 * 
 * <p>This class provides configuration options for controlling Virtual Thread usage in cooperative
 * operations. Virtual Threads are lightweight threads managed by the JVM rather than the OS,
 * making them ideal for I/O-bound operations where threads spend most of their time waiting.</p>
 * 
 * <p>Performance implications:</p>
 * <ul>
 *   <li>Virtual Threads significantly improve scalability for I/O-bound operations</li>
 *   <li>They consume fewer resources than platform threads, allowing for higher concurrency</li>
 *   <li>For CPU-intensive tasks, platform threads may still be more efficient</li>
 *   <li>The useVirtualThreads setting allows fine-tuning based on workload characteristics</li>
 * </ul>
 *
 * @since 3.41
 */
public abstract class MutableConfigSupport
    extends Config
    implements Builder
{
  protected boolean enabled = true;
  
  /**
   * Controls whether Virtual Threads should be used for operations when available (Java 21+).
   * Defaults to true for optimal performance with I/O-bound operations.
   * 
   * @since 3.60
   */
  protected boolean useVirtualThreads = true;
  
  /**
   * Specific timeout in seconds for operations running on Virtual Threads.
   * A value of 0 means use the same timeout as platform threads.
   * 
   * @since 3.60
   */
  protected int virtualThreadTimeoutSeconds = 0;
  
  /**
   * Maximum number of Virtual Threads that can be created for this cooperation point.
   * A value of 0 or negative indicates no limit.
   * 
   * @since 3.60
   */
  protected int maxVirtualThreads = 0;
  
  /**
   * When true, automatically routes I/O-bound operations to Virtual Threads
   * while keeping CPU-intensive operations on platform threads.
   * 
   * @since 3.60
   */
  protected boolean prioritizeVirtualThreadsForIO = true;

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
    return this;
  }

  @Override
  public Builder enabled(final boolean enabled) {
    this.enabled = enabled;
    return this;
  }
  
  @Override
  public Builder useVirtualThreads(final boolean useVirtualThreads) {
    this.useVirtualThreads = useVirtualThreads;
    return this;
  }
  
  @Override
  public Builder virtualThreadTimeout(final Duration virtualThreadTimeout) {
    this.virtualThreadTimeoutSeconds = (int) checkNotNull(virtualThreadTimeout).getSeconds();
    return this;
  }
  
  @Override
  public Builder maxVirtualThreads(final int maxVirtualThreads) {
    this.maxVirtualThreads = maxVirtualThreads;
    return this;
  }
  
  @Override
  public Builder prioritizeVirtualThreadsForIO(final boolean prioritizeVirtualThreadsForIO) {
    this.prioritizeVirtualThreadsForIO = prioritizeVirtualThreadsForIO;
    return this;
  }
  
  /**
   * Returns whether Virtual Threads should be used when available (Java 21+).
   * 
   * @return true if Virtual Threads should be used, false otherwise
   * @since 3.60
   */
  public boolean useVirtualThreads() {
    return useVirtualThreads;
  }
  
  /**
   * Returns the timeout duration specifically for Virtual Thread operations.
   * If set to zero, the regular timeout values will be used.
   * 
   * @return the Virtual Thread specific timeout duration
   * @since 3.60
   */
  public Duration virtualThreadTimeout() {
    return Duration.ofSeconds(virtualThreadTimeoutSeconds);
  }
  
  /**
   * Returns the maximum number of Virtual Threads that can be created.
   * A value of 0 or negative indicates no limit.
   * 
   * @return the maximum number of Virtual Threads
   * @since 3.60
   */
  public int maxVirtualThreads() {
    return maxVirtualThreads;
  }
  
  /**
   * Returns whether I/O-bound operations should be automatically routed to Virtual Threads.
   * 
   * @return true if I/O operations should prioritize Virtual Threads, false otherwise
   * @since 3.60
   */
  public boolean prioritizeVirtualThreadsForIO() {
    return prioritizeVirtualThreadsForIO;
  }
  
  /**
   * Creates a copy of this configuration including Virtual Thread settings.
   *
   * @return a new Config instance with the same values as this one
   */
  @Override
  protected Config copy() {
    Config copy = super.copy();
    if (copy instanceof MutableConfigSupport) {
      MutableConfigSupport mutableCopy = (MutableConfigSupport) copy;
      mutableCopy.enabled = this.enabled;
      mutableCopy.useVirtualThreads = this.useVirtualThreads;
      mutableCopy.virtualThreadTimeoutSeconds = this.virtualThreadTimeoutSeconds;
      mutableCopy.maxVirtualThreads = this.maxVirtualThreads;
      mutableCopy.prioritizeVirtualThreadsForIO = this.prioritizeVirtualThreadsForIO;
    }
    return copy;
  }
}
