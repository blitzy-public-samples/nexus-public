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
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.cooperation2.Cooperation2;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory;
import org.sonatype.nexus.common.cooperation2.datastore.internal.LocalCooperation2;
import org.sonatype.nexus.common.cooperation2.datastore.internal.VirtualThreadLocalCooperation2;
import org.sonatype.nexus.common.cooperation2.internal.DisabledCooperation2;
import org.sonatype.nexus.common.cooperation2.internal.MutableConfigSupport;

/**
 * Default implementation of {@link Cooperation2Factory} that leverages Java 21 Virtual Threads
 * for improved performance with I/O-bound operations.
 * 
 * <p>This factory creates cooperation instances that can use Virtual Threads to efficiently
 * handle blocking operations without consuming excessive platform thread resources. Virtual Threads
 * are particularly beneficial for operations that spend significant time waiting for I/O, such as
 * network requests, file operations, or database queries.</p>
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Automatic use of Virtual Threads for I/O-bound operations</li>
 *   <li>Context propagation across Virtual Threads</li>
 *   <li>Configurable thread management settings</li>
 *   <li>Backward compatibility with existing cooperation patterns</li>
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
   * Determines if the current JVM supports Virtual Threads (Java 21+).
   * 
   * <p>This method checks for the presence of the {@code Thread.ofVirtual()} method,
   * which is the primary API for creating Virtual Threads in Java 21 and later.</p>
   * 
   * <p>Virtual Threads are lightweight threads that dramatically reduce the effort of writing,
   * maintaining, and debugging high-throughput concurrent applications. They are particularly
   * beneficial for I/O-bound operations where threads spend significant time waiting.</p>
   * 
   * @return true if Virtual Threads are supported, false otherwise
   */
  private static boolean isVirtualThreadSupported() {
    try {
      // Check if Thread class has the ofVirtual method (Java 21+)
      Thread.class.getMethod("ofVirtual");
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }
  
  /**
   * Flag indicating whether Virtual Threads are supported in the current JVM.
   */
  private static final boolean VIRTUAL_THREAD_SUPPORTED = isVirtualThreadSupported();

  @Override
  public Builder configure() {
    return new DefaultCooperation2Builder();
  }

  /**
   * Builder implementation that creates cooperation instances with Virtual Thread support.
   * 
   * <p>This builder creates {@link Cooperation2} instances that can leverage Java 21 Virtual Threads
   * for improved performance with I/O-bound operations. When Virtual Threads are enabled and supported,
   * operations will be executed on lightweight threads managed by the JVM rather than OS threads.</p>
   * 
   * <p>Virtual Threads are particularly beneficial for operations that spend significant time waiting
   * for I/O, as they allow the JVM to efficiently manage thousands of concurrent operations without
   * the overhead of platform threads.</p>
   */
  /**
   * Builder implementation that creates cooperation instances with Virtual Thread support.
   * 
   * <p>This builder creates {@link Cooperation2} instances that can leverage Java 21 Virtual Threads
   * for improved performance with I/O-bound operations. When Virtual Threads are enabled and supported,
   * operations will be executed on lightweight threads managed by the JVM rather than OS threads.</p>
   * 
   * <p>Virtual Threads are particularly beneficial for operations that spend significant time waiting
   * for I/O, as they allow the JVM to efficiently manage thousands of concurrent operations without
   * the overhead of platform threads.</p>
   * 
   * <p>Best practices when using Virtual Threads:</p>
   * <ul>
   *   <li>Avoid synchronized blocks in code that runs on Virtual Threads to prevent "pinning"</li>
   *   <li>Use explicit resource limits (e.g., connection pools, semaphores) rather than relying on
   *       thread count as an implicit throttling mechanism</li>
   *   <li>Be aware that Virtual Threads make it easy to create many more concurrent operations,
   *       which could overwhelm downstream systems if not properly managed</li>
   * </ul>
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
      
      // Use Virtual Thread implementation when supported and enabled
      if (VIRTUAL_THREAD_SUPPORTED && useVirtualThreads) {
        log.debug("Creating Virtual Thread enabled cooperation: {}", id);
        return new VirtualThreadLocalCooperation2(id, this.copy());
      } else {
        log.debug("Creating standard cooperation (Virtual Threads not available or disabled): {}", id);
        return new LocalCooperation2(id, this.copy());
      }
    }

    @Override
    public Cooperation2 build(final Class<?> id, final String... keys) {
      if (!enabled) {
        log.debug("Disabled cooperation: {}", id);
        return new DisabledCooperation2(stripGuice(id, keys));
      }
      
      // Use Virtual Thread implementation when supported and enabled
      if (VIRTUAL_THREAD_SUPPORTED && useVirtualThreads) {
        log.debug("Creating Virtual Thread enabled cooperation: {}", id);
        return new VirtualThreadLocalCooperation2(stripGuice(id, keys), this.copy());
      } else {
        log.debug("Creating standard cooperation (Virtual Threads not available or disabled): {}", stripGuice(id, keys));
        return new LocalCooperation2(stripGuice(id, keys), this.copy());
      }
    }
  }

  /*
   * When classes are enhanced by Guice AOP they can have random strings and we need them to be consistent
   */
  protected static String stripGuice(final Class<?> clazz, final String... keys) {
    String simpleName = clazz.getSimpleName();
    // this is the normal case due to method interceptors
    if (simpleName.contains("EnhancerByGuice") && clazz.getSuperclass() != null) {
      return stripGuice(clazz.getSuperclass(), keys);
    }

    return Arrays.asList(keys).stream().collect(Collectors.joining("-", simpleName + '-', ""));
  }
}
