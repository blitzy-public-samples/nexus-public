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
package org.sonatype.nexus.common.thread;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class for working with Java 21 Virtual Threads.
 *
 * @since 3.60
 */
public final class VirtualThreadExecutors
{
  private VirtualThreadExecutors() {
    // static utility class
  }

  /**
   * Creates a new {@link ExecutorService} that creates a new virtual thread for each task.
   * <p>
   * This executor is suitable for I/O-bound tasks that spend most of their time blocked on network,
   * database, or file system operations. It allows for high concurrency with minimal resource usage.
   * <p>
   * Virtual threads are lightweight threads that are managed by the JVM rather than the operating system.
   * They are designed to be efficient for I/O-bound tasks and can be created in large numbers without
   * significant overhead.
   *
   * @return a new {@link ExecutorService} that creates a new virtual thread for each task
   */
  public static ExecutorService newVirtualThreadPerTaskExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}