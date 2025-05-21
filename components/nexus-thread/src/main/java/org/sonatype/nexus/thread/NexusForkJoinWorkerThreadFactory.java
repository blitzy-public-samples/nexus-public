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
package org.sonatype.nexus.thread;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Nexus {@link ForkJoinWorkerThreadFactory} that provides proper thread naming and group assignment
 * for compatibility with Java 21's ForkJoinPool implementation.
 * <p>
 * This factory supports both platform threads and virtual thread-based workloads, ensuring
 * compatibility with Java 21's threading model changes.
 * 
 * @since 3.20
 */
public class NexusForkJoinWorkerThreadFactory
    implements ForkJoinWorkerThreadFactory
{
  private final String jobPrefix;

  /**
   * Creates a new factory with the specified job prefix for thread naming.
   *
   * @param jobPrefix the prefix to use for thread names
   */
  public NexusForkJoinWorkerThreadFactory(final String jobPrefix) {
    this.jobPrefix = checkNotNull(jobPrefix);
  }

  @Override
  public ForkJoinWorkerThread newThread(final ForkJoinPool pool) {
    // Use the default factory to create the thread, which handles Java 21 compatibility
    final ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
    
    // Set a consistent naming pattern that works with both Java 17 and Java 21
    worker.setName(jobPrefix + worker.getPoolIndex());
    
    return worker;
  }
}