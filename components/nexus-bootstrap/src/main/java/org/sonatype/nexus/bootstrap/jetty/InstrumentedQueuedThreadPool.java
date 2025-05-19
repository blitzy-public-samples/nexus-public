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
package org.sonatype.nexus.bootstrap.jetty;

import java.util.concurrent.Executors;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jetty12.InstrumentedQueuedThreadPool;
import com.codahale.metrics.SharedMetricRegistries;

/**
 * Extension of {@link com.codahale.metrics.jetty12.InstrumentedQueuedThreadPool} that provides
 * default constructor and Virtual Thread support for Java 21.
 * 
 * @since 3.0
 */
public final class InstrumentedQueuedThreadPool
    extends InstrumentedQueuedThreadPool
{
  /**
   * Creates a new instrumented thread pool with Virtual Thread support for I/O-bound operations.
   */
  public InstrumentedQueuedThreadPool() {
    this(SharedMetricRegistries.getOrCreate("nexus"));
  }
  
  /**
   * Creates a new instrumented thread pool with Virtual Thread support for I/O-bound operations.
   *
   * @param registry the metric registry to use
   */
  public InstrumentedQueuedThreadPool(MetricRegistry registry) {
    super(registry);
    // Configure Virtual Thread support for I/O-bound operations
    // Platform threads are maintained for CPU-bound operations by default
    setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }
}