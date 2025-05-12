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

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.cooperation2.Cooperation2;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory;
import org.sonatype.nexus.common.cooperation2.datastore.internal.LocalCooperation2;
import org.sonatype.nexus.common.cooperation2.internal.DisabledCooperation2;
import org.sonatype.nexus.common.cooperation2.internal.MutableConfigSupport;

/**
 * Default implementation of {@link Cooperation2Factory} optimized for Java 21 Virtual Threads.
 * 
 * This factory creates cooperation instances that leverage Java 21's Virtual Threads for
 * improved concurrency, especially for I/O-bound operations. Virtual Threads provide significant
 * performance benefits by allowing thousands of concurrent operations with minimal resource overhead.
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
   * Default major timeout duration for cooperation operations.
   */
  private static final Duration DEFAULT_MAJOR_TIMEOUT = Duration.ofMinutes(30);
  
  /**
   * Default minor timeout duration for cooperation operations.
   */
  private static final Duration DEFAULT_MINOR_TIMEOUT = Duration.ofMinutes(10);
  
  /**
   * Default number of threads per key for cooperation operations.
   * With Virtual Threads, this can be set higher than with platform threads
   * as Virtual Threads have much lower overhead.
   */
  private static final int DEFAULT_THREADS_PER_KEY = 8;
  
  @Override
  public Builder configure() {
    return new DefaultCooperation2Builder()
        .majorTimeout(DEFAULT_MAJOR_TIMEOUT)
        .minorTimeout(DEFAULT_MINOR_TIMEOUT)
        .threadsPerKey(DEFAULT_THREADS_PER_KEY)
        .useVirtualThreads(true); // Enable Virtual Threads by default for Java 21
  }

  /**
   * Builder implementation for creating Cooperation2 instances optimized for Java 21.
   * 
   * This builder configures cooperation instances to leverage Virtual Threads for
   * improved concurrency and performance, particularly for I/O-bound operations.
   */
  protected class DefaultCooperation2Builder
      extends MutableConfigSupport
  {
    @Override
    public Cooperation2 build(final String id) {
      if (!enabled) {
        log.debug("Disabled cooperation: {}", id);
        return new DisabledCooperation2(id);
      }
      
      if (log.isDebugEnabled() && useVirtualThreads) {
        log.debug("Creating cooperation with Virtual Thread support: {}", id);
      }
      
      return new LocalCooperation2(id, this.copy());
    }

    @Override
    public Cooperation2 build(final Class<?> id, final String... keys) {
      if (!enabled) {
        log.debug("Disabled cooperation: {}", id);
        return new DisabledCooperation2(stripGuice(id, keys));
      }
      
      String scopeId = stripGuice(id, keys);
      
      if (log.isDebugEnabled() && useVirtualThreads) {
        log.debug("Creating cooperation with Virtual Thread support: {}", scopeId);
      }
      
      return new LocalCooperation2(scopeId, this.copy());
    }
    
    /**
     * Creates a copy of this configuration with all fields properly copied.
     * 
     * @return a new instance with the same configuration values
     */
    @Override
    protected Config copy() {
      Config copy = super.copy();
      // Copy the additional fields from MutableConfigSupport that aren't in the base Config class
      if (copy instanceof MutableConfigSupport) {
        MutableConfigSupport mutableCopy = (MutableConfigSupport) copy;
        mutableCopy.enabled(this.enabled);
        mutableCopy.useVirtualThreads(this.useVirtualThreads);
        // Initialize the concurrency limit with our current value
        mutableCopy.concurrencyLimit().set(this.concurrencyLimit().get());
      }
      return copy;
    }
  }

  /**
   * When classes are enhanced by Guice AOP they can have random strings and we need them to be consistent.
   * This method strips Guice-specific parts from class names to ensure consistent cooperation keys.
   * 
   * @param clazz the class to get the name from
   * @param keys additional key components
   * @return a consistent string identifier for the cooperation
   */
  protected static String stripGuice(final Class<?> clazz, final String... keys) {
    String simpleName = clazz.getSimpleName();
    // this is the normal case due to method interceptors
    if (simpleName.contains("EnhancerByGuice") && clazz.getSuperclass() != null) {
      return stripGuice(clazz.getSuperclass(), keys);
    }

    // Use modern Stream API features for string joining
    return Arrays.stream(keys)
        .collect(Collectors.joining("-", simpleName + '-', ""));
  }
}
