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
package org.sonatype.nexus.common.cooperation2.datastore;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.cooperation2.Config;
import org.sonatype.nexus.common.cooperation2.Cooperation2;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory;
import org.sonatype.nexus.common.cooperation2.datastore.internal.LocalCooperation2;
import org.sonatype.nexus.common.cooperation2.internal.DisabledCooperation2;
import org.sonatype.nexus.common.cooperation2.internal.MutableConfigSupport;

/**
 * Default implementation of {@link Cooperation2Factory} optimized for Java 21 Virtual Threads.
 * 
 * This factory creates cooperation instances that leverage Virtual Threads for efficient
 * handling of I/O-bound operations, providing high concurrency with minimal resource overhead.
 * Virtual Threads are particularly well-suited for repository operations that involve network
 * or disk I/O, as they allow for thousands of concurrent operations without the overhead of
 * traditional platform threads.
 * 
 * <p>Note on ThreadLocal usage with Virtual Threads:</p>
 * <ul>
 *   <li>Virtual Threads fully support ThreadLocal variables in Java 21</li>
 *   <li>However, since virtual threads are never pooled and never reused by unrelated tasks,
 *       the traditional ThreadLocal caching pattern becomes inefficient</li>
 *   <li>With potentially millions of virtual threads, each with its own ThreadLocal,
 *       memory usage can become a concern</li>
 *   <li>Use the system property {@code jdk.traceVirtualThreadLocals} to trace ThreadLocal usage in virtual threads</li>
 * </ul>
 * 
 * @since 3.41
 */
@Named("local")
@Singleton
public class DefaultCooperation2Factory
    extends ComponentSupport
    implements Cooperation2Factory
{
  /**
   * Creates a new builder for configuring cooperation instances.
   * The resulting cooperation instances will use Java 21 Virtual Threads
   * for optimal I/O performance when enabled.
   * 
   * @return a new builder instance with Virtual Threads enabled by default
   */
  @Override
  public Builder configure() {
    return new DefaultCooperation2Builder();
  }

  /**
   * Builder implementation that creates cooperation instances optimized for Java 21.
   * When enabled, the created instances leverage Virtual Threads for efficient
   * concurrent processing of I/O operations.
   * 
   * This implementation properly propagates thread context when using Virtual Threads
   * and ensures compatibility with Java 21's concurrency model.
   */
  protected class DefaultCooperation2Builder
      extends MutableConfigSupport
  {
    /**
     * Creates a copy of this configuration that preserves Virtual Thread settings.
     * 
     * @return a new Config instance with all settings copied
     */
    @Override
    protected Config copy() {
      Config config = super.copy();
      if (config instanceof MutableConfigSupport) {
        MutableConfigSupport mutableConfig = (MutableConfigSupport) config;
        mutableConfig.useVirtualThreads(this.useVirtualThreads());
        // Initialize with the same concurrency limit
        mutableConfig.threadsPerKey(this.threadsPerKey);
      }
      return config;
    }
    
    @Override
    public Cooperation2 build(final String id) {
      if (!enabled) {
        log.debug("Disabled cooperation: {}", id);
        return new DisabledCooperation2(id);
      }
      log.debug("Creating cooperation with Virtual Threads {}: {}", 
          useVirtualThreads() ? "enabled" : "disabled", id);
      return new LocalCooperation2(id, this.copy());
    }

    @Override
    public Cooperation2 build(final Class<?> id, final String... keys) {
      String scopeId = stripGuice(id, keys);
      if (!enabled) {
        log.debug("Disabled cooperation: {}", scopeId);
        return new DisabledCooperation2(scopeId);
      }
      log.debug("Creating cooperation with Virtual Threads {}: {}", 
          useVirtualThreads() ? "enabled" : "disabled", scopeId);
      return new LocalCooperation2(scopeId, this.copy());
    }
    
    /**
     * Configures whether to monitor ThreadLocal usage in virtual threads.
     * When enabled, the system property {@code jdk.traceVirtualThreadLocals} will be set
     * to trigger stack traces when virtual threads set ThreadLocal values.
     * 
     * <p>This is useful for debugging and optimizing ThreadLocal usage with virtual threads.</p>
     * 
     * @param monitorThreadLocals true to enable monitoring, false to disable
     * @return this builder for fluent API
     * @since 3.60
     */
    public Builder monitorThreadLocals(final boolean monitorThreadLocals) {
      if (monitorThreadLocals) {
        System.setProperty("jdk.traceVirtualThreadLocals", "true");
        log.info("Enabled monitoring of ThreadLocal usage in virtual threads");
      } else {
        System.clearProperty("jdk.traceVirtualThreadLocals");
      }
      return this;
    }
  }

  /**
   * When classes are enhanced by Guice AOP they can have random strings and we need them to be consistent.
   * This method ensures consistent naming regardless of Guice enhancements.
   * 
   * @param clazz the class to get the name from
   * @param keys additional keys to append to the name
   * @return a consistent name for the cooperation instance
   */
  protected static String stripGuice(final Class<?> clazz, final String... keys) {
    String simpleName = clazz.getSimpleName();
    // this is the normal case due to method interceptors
    if (simpleName.contains("EnhancerByGuice") && clazz.getSuperclass() != null) {
      return stripGuice(clazz.getSuperclass(), keys);
    }

    return Arrays.stream(keys).collect(Collectors.joining("-", simpleName + '-', ""));
  }
}
